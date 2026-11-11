package com.awesome.warehouse.service.event;

import com.awesome.warehouse.client.BackendClient;
import com.awesome.warehouse.client.EventFeedPage;
import com.awesome.warehouse.client.FeedEvent;
import com.awesome.warehouse.controller.exception.CustomException;
import com.awesome.warehouse.entity.event.EventCursorEntity;
import com.awesome.warehouse.entity.event.ProcessedEventEntity;
import com.awesome.warehouse.repository.event.EventCursorRepository;
import com.awesome.warehouse.repository.event.ProcessedEventRepository;
import com.awesome.warehouse.service.fulfilment.ShipmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Pulls the backend's domain event feed ({@code GET /internal/v1/events}) from the stored
 * cursor and reacts to the events this service cares about:
 * {@code order.cancelled} cancels the order's shipment while it is AWAITING_PICK or PICKED.
 *
 * <p>Each event is handled in its own local transaction that locks the cursor row (so
 * several instances never handle one event concurrently), skips events already in
 * {@code processed_events}, applies the change, records the event as processed and
 * advances the cursor. A backend outage ends the run; the next tick resumes from the
 * cursor. An event whose handling fails is logged with its id and skipped, so one bad
 * event never blocks the feed. {@code ShipmentReconciler} remains as a slow safety net.
 */
@Slf4j
@Component
public class EventFeedPoller {

    /** Cursor row of this service in {@code event_cursors}. */
    public static final String CONSUMER = "warehouse-service";
    static final String ORDER_CANCELLED = "order.cancelled";
    static final List<String> TYPES = List.of(ORDER_CANCELLED);
    static final int PAGE_SIZE = 100;
    /** Upper bound of pages read per run, so a large backlog does not monopolise the scheduler thread. */
    static final int MAX_PAGES_PER_RUN = 10;

    private final BackendClient backendClient;
    private final ShipmentService shipmentService;
    private final EventCursorRepository cursors;
    private final ProcessedEventRepository processedEvents;
    private final TransactionTemplate transaction;
    private final Clock warehouseClock;
    private final boolean enabled;

    public EventFeedPoller(BackendClient backendClient, ShipmentService shipmentService,
                           EventCursorRepository cursors, ProcessedEventRepository processedEvents,
                           PlatformTransactionManager transactionManager, Clock warehouseClock,
                           @Value("${warehouse.events.enabled:true}") boolean enabled) {
        this.backendClient = backendClient;
        this.shipmentService = shipmentService;
        this.cursors = cursors;
        this.processedEvents = processedEvents;
        this.transaction = new TransactionTemplate(transactionManager);
        this.warehouseClock = warehouseClock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${warehouse.events.poll-interval:PT5S}",
            initialDelayString = "${warehouse.events.poll-interval:PT5S}")
    public void scheduledPoll() {
        if (enabled) {
            poll();
        }
    }

    /**
     * Reads the feed from the stored cursor until it is drained (at most
     * {@link #MAX_PAGES_PER_RUN} pages) or the backend cannot be reached.
     *
     * @return the number of events handled in this run (events seen before are not counted)
     */
    public synchronized int poll() {
        ensureCursor();
        int handled = 0;
        for (int pageNumber = 0; pageNumber < MAX_PAGES_PER_RUN; pageNumber++) {
            long cursor = cursor();
            EventFeedPage page;
            try {
                page = backendClient.events(cursor, PAGE_SIZE, TYPES);
            } catch (CustomException e) {
                log.warn("Event feed not read after sequence {}: {}; retrying on the next run", cursor, e.getMessage());
                return handled;
            }
            for (FeedEvent event : page.events()) {
                Outcome outcome = process(event);
                if (outcome == Outcome.STOPPED) {
                    return handled;
                }
                if (outcome == Outcome.HANDLED) {
                    handled++;
                }
            }
            // The backend may have scanned past events of other types; nextCursor skips them too.
            transaction.executeWithoutResult(status -> advance(page.nextCursor()));
            if (!page.hasMore()) {
                break;
            }
        }
        return handled;
    }

    /** The stored cursor; 0 (the start of the feed) before the first event. */
    public long cursor() {
        return cursors.findById(CONSUMER).map(EventCursorEntity::getLastSequence).orElse(0L);
    }

    private Outcome process(FeedEvent event) {
        try {
            return transaction.execute(status -> {
                EventCursorEntity cursor = lockCursor();
                if (processedEvents.existsById(event.id())) {
                    moveCursor(cursor, event.sequence());
                    return Outcome.SEEN;
                }
                handle(event);
                markProcessed(event, cursor);
                return Outcome.HANDLED;
            });
        } catch (RuntimeException e) {
            log.error("Event {} ({} #{}) could not be handled and is skipped", event.id(), event.type(),
                    event.sequence(), e);
            return skip(event);
        }
    }

    private void handle(FeedEvent event) {
        if (ORDER_CANCELLED.equals(event.type())) {
            shipmentService.followOrderCancellation(orderId(event));
        } else {
            log.debug("Ignoring event {} of type {}", event.id(), event.type());
        }
    }

    private static Long orderId(FeedEvent event) {
        if (event.orderId() != null) {
            return event.orderId();
        }
        if (event.data().get("orderId") instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalArgumentException("Event " + event.id() + " has no orderId");
    }

    /** Records a failed event as processed so the feed moves on; STOPPED when even that fails. */
    private Outcome skip(FeedEvent event) {
        try {
            transaction.executeWithoutResult(status -> {
                EventCursorEntity cursor = lockCursor();
                if (processedEvents.existsById(event.id())) {
                    moveCursor(cursor, event.sequence());
                } else {
                    markProcessed(event, cursor);
                }
            });
            return Outcome.SKIPPED;
        } catch (RuntimeException e) {
            log.error("Could not record skipped event {}; the run stops and retries it next time", event.id(), e);
            return Outcome.STOPPED;
        }
    }

    private void markProcessed(FeedEvent event, EventCursorEntity cursor) {
        processedEvents.save(new ProcessedEventEntity(event.id(), now()));
        moveCursor(cursor, event.sequence());
    }

    private void advance(long sequence) {
        moveCursor(lockCursor(), sequence);
    }

    private void moveCursor(EventCursorEntity cursor, long sequence) {
        if (sequence > cursor.getLastSequence()) {
            cursor.setLastSequence(sequence);
            cursor.setUpdatedAt(now());
            cursors.save(cursor);
        }
    }

    /** Locks this consumer's cursor row (created by {@link #ensureCursor()}). */
    private EventCursorEntity lockCursor() {
        return cursors.findForUpdate(CONSUMER).orElseThrow();
    }

    /** Creates the cursor row at sequence 0 on the first run (another instance may win the insert). */
    private void ensureCursor() {
        try {
            transaction.executeWithoutResult(status -> {
                if (!cursors.existsById(CONSUMER)) {
                    cursors.saveAndFlush(new EventCursorEntity(CONSUMER, 0L, now()));
                }
            });
        } catch (DataIntegrityViolationException e) {
            log.debug("Event cursor created concurrently");
        }
    }

    private enum Outcome { HANDLED, SEEN, SKIPPED, STOPPED }

    private LocalDateTime now() {
        return LocalDateTime.now(warehouseClock);
    }
}
