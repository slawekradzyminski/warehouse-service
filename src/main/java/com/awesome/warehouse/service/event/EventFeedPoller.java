package com.awesome.warehouse.service.event;

import com.awesome.warehouse.client.BackendClient;
import com.awesome.warehouse.client.EventFeedPage;
import com.awesome.warehouse.client.FeedEvent;
import com.awesome.warehouse.controller.exception.CustomException;
import com.awesome.warehouse.correlation.TracingSupport;
import com.awesome.warehouse.entity.event.EventCursorEntity;
import com.awesome.warehouse.entity.event.ProcessedEventEntity;
import com.awesome.warehouse.repository.event.EventCursorRepository;
import com.awesome.warehouse.repository.event.ProcessedEventRepository;
import com.awesome.warehouse.service.fulfilment.ShipmentService;
import com.awesome.warehouse.service.stock.StockReadiness;
import com.awesome.warehouse.service.stock.StockRestoreConsumer;
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
import java.util.Map;

/**
 * Pulls the backend's domain event feed ({@code GET /internal/v1/events}) from the stored
 * cursor and reacts to the events this service cares about:
 * {@code order.cancelled} cancels the order's shipment while it is AWAITING_PICK or PICKED, and
 * the backend's internal {@code stock.restore-requested} restores a cancelled order's stock in
 * the ledger ({@link StockRestoreConsumer}, once per order). The feed is not read before the
 * stock ledger is initialised, so no restore is skipped during the startup import.
 *
 * <p>Each event is handled in its own local transaction that locks the cursor row (so
 * several instances never handle one event concurrently), skips events already in
 * {@code processed_events}, applies the change, records the event as processed and
 * advances the cursor. A backend outage ends the run; the next tick resumes from the
 * cursor. Only a poison event, one whose own content can never be handled
 * ({@link EventFailures#classify}), is logged with its id and skipped, so one bad event never
 * blocks the feed; any database or transaction failure (a dropped connection, a pool or query
 * timeout, a failed commit) or other unexpected failure stops the run with the cursor unchanged
 * and the next run retries the event. A stock restore runs in its own transaction before the event
 * is recorded as processed and follows the same rule: a malformed restore event is logged and
 * skipped, any other restore failure stops the run.
 * {@code ShipmentReconciler} remains as a slow safety net.
 *
 * <p>Each scheduled run has its own generated correlation ids; while an event is handled the
 * MDC (and so every log line and backend call made for it) carries the event's
 * {@code requestId} and {@code traceId}, or generated ones when the event has none.
 *
 * <p>With tracing on each event is handled in a {@code CONSUMER} span {@code process <type>}
 * whose parent is the event's {@code traceId}/{@code spanId}, so the backend request that wrote
 * the event and what this service did about it are one trace (see {@link TracingSupport#consume}).
 * The poll run itself is not recorded ({@code TracingConfig}).
 *
 * <p>With {@code warehouse.events.transport=broker} the poller does not run: {@link EventBrokerListener}
 * hands every event to {@link #processDelivered}, which deduplicates by event id only and leaves the
 * cursor where the feed left it, so switching back to the feed resumes from there.
 */
@Slf4j
@Component
public class EventFeedPoller {

    /** Cursor row of this service in {@code event_cursors}. */
    public static final String CONSUMER = "warehouse-service";
    static final String ORDER_CANCELLED = "order.cancelled";
    static final String STOCK_RESTORE_REQUESTED = StockRestoreConsumer.STOCK_RESTORE_REQUESTED;
    static final List<String> TYPES = List.of(ORDER_CANCELLED, STOCK_RESTORE_REQUESTED);
    static final int PAGE_SIZE = 100;
    /** Upper bound of pages read per run, so a large backlog does not monopolise the scheduler thread. */
    static final int MAX_PAGES_PER_RUN = 10;

    private final BackendClient backendClient;
    private final ShipmentService shipmentService;
    private final StockRestoreConsumer stockRestores;
    private final StockReadiness stockReadiness;
    private final EventCursorRepository cursors;
    private final ProcessedEventRepository processedEvents;
    private final TransactionTemplate transaction;
    private final Clock warehouseClock;
    private final TracingSupport tracing;
    private final boolean enabled;

    public EventFeedPoller(BackendClient backendClient, ShipmentService shipmentService,
                           StockRestoreConsumer stockRestores, StockReadiness stockReadiness,
                           EventCursorRepository cursors, ProcessedEventRepository processedEvents,
                           PlatformTransactionManager transactionManager, Clock warehouseClock,
                           TracingSupport tracing,
                           @Value("${warehouse.events.enabled:true}") boolean enabled,
                           @Value("${warehouse.events.transport:feed}") String transport) {
        this.backendClient = backendClient;
        this.shipmentService = shipmentService;
        this.stockRestores = stockRestores;
        this.stockReadiness = stockReadiness;
        this.cursors = cursors;
        this.processedEvents = processedEvents;
        this.transaction = new TransactionTemplate(transactionManager);
        this.warehouseClock = warehouseClock;
        this.tracing = tracing;
        // With the broker transport the listener receives the events; the poller stays off.
        this.enabled = enabled && EventTransport.from(transport) == EventTransport.FEED;
    }

    @Scheduled(fixedDelayString = "${warehouse.events.poll-interval:PT5S}",
            initialDelayString = "${warehouse.events.poll-interval:PT5S}")
    public void scheduledPoll() {
        if (enabled) {
            tracing.forJob().run(this::poll);
        }
    }

    /**
     * Reads the feed from the stored cursor until it is drained (at most
     * {@link #MAX_PAGES_PER_RUN} pages) or the backend cannot be reached.
     *
     * @return the number of events handled in this run (events seen before are not counted)
     */
    public synchronized int poll() {
        if (!stockReadiness.isReady()) {
            log.debug("Event feed not read: the stock ledger is not initialised yet");
            return 0;
        }
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
                Outcome outcome = tracing.consume("process " + event.type(), event.requestId(), event.traceId(),
                        event.spanId(), spanTags(event), () -> restoreThenProcess(event));
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

    /**
     * Handles one event delivered by the broker: a stock restore first (in its own transaction, at most
     * once per order), then, in one transaction, the event's other effect and its {@code processed_events}
     * row; an event id already recorded is skipped. Unlike the poller nothing is skipped on failure and the
     * cursor is not used (broker deliveries are not in sequence order once one is redelivered): every
     * exception, a malformed restore included, propagates, so the broker redelivers the message and finally
     * dead-letters it.
     *
     * @return false when the event was a duplicate
     */
    public boolean processDelivered(FeedEvent event) {
        return tracing.consume("process " + event.type(), event.requestId(), event.traceId(), event.spanId(),
                spanTags(event), () -> {
                    try {
                        if (STOCK_RESTORE_REQUESTED.equals(event.type())) {
                            stockRestores.restore(event);
                        }
                        Boolean handled = transaction.execute(status -> {
                            if (processedEvents.existsById(event.id())) {
                                log.info("Event {} ({} #{}) was already processed; duplicate delivery acknowledged",
                                        event.id(), event.type(), event.sequence());
                                return false;
                            }
                            handle(event);
                            processedEvents.save(new ProcessedEventEntity(event.id(), now()));
                            return true;
                        });
                        if (Boolean.TRUE.equals(handled)) {
                            log.info("Handled event {} ({} #{}) from the broker", event.id(), event.type(),
                                    event.sequence());
                        }
                        return Boolean.TRUE.equals(handled);
                    } catch (RuntimeException e) {
                        log.warn("Event {} ({} #{}) failed; the broker will redeliver it: {}", event.id(), event.type(),
                                event.sequence(), e.getMessage());
                        throw e;
                    }
                });
    }

    /** Attributes of the event's span. */
    static Map<String, String> spanTags(FeedEvent event) {
        return Map.of("event.id", String.valueOf(event.id()), "event.type", String.valueOf(event.type()),
                "event.sequence", Long.toString(event.sequence()));
    }

    private Outcome restoreThenProcess(FeedEvent event) {
        if (STOCK_RESTORE_REQUESTED.equals(event.type()) && !restoreStock(event)) {
            return Outcome.STOPPED;
        }
        return process(event);
    }

    /** @return false when the restore failed for a reason other than a poison event and the run must stop */
    private boolean restoreStock(FeedEvent event) {
        try {
            stockRestores.restore(event);
            return true;
        } catch (RuntimeException e) {
            switch (EventFailures.classify(e)) {
                case POISON -> {
                    log.error("Restore event {} is invalid; its stock is not restored: {}", event.id(), String.valueOf(e));
                    return true;
                }
                case INFRASTRUCTURE -> log.warn("Stock restore of event {} failed; the run stops and retries it next "
                        + "time: {}", event.id(), String.valueOf(e));
                case UNEXPECTED -> log.error("Stock restore of event {} failed unexpectedly; the run stops and retries "
                        + "it next time", event.id(), e);
            }
            return false;
        }
    }

    private Outcome process(FeedEvent event) {
        try {
            Outcome outcome = transaction.execute(status -> {
                EventCursorEntity cursor = lockCursor();
                if (processedEvents.existsById(event.id())) {
                    moveCursor(cursor, event.sequence());
                    return Outcome.SEEN;
                }
                handle(event);
                markProcessed(event, cursor);
                return Outcome.HANDLED;
            });
            if (outcome == Outcome.HANDLED) {
                log.info("Handled event {} ({} #{})", event.id(), event.type(), event.sequence());
            }
            return outcome;
        } catch (RuntimeException e) {
            switch (EventFailures.classify(e)) {
                case POISON -> {
                    log.error("Event {} ({} #{}) cannot be handled and is skipped", event.id(), event.type(),
                            event.sequence(), e);
                    return skip(event);
                }
                case INFRASTRUCTURE -> log.warn("Event {} ({} #{}) failed for a reason outside the event (database, "
                        + "transaction or backend); the run stops and retries it next time: {}", event.id(),
                        event.type(), event.sequence(), String.valueOf(e));
                case UNEXPECTED -> log.error("Event {} ({} #{}) failed unexpectedly; the run stops and retries it next "
                        + "time", event.id(), event.type(), event.sequence(), e);
            }
            return Outcome.STOPPED;
        }
    }

    private void handle(FeedEvent event) {
        if (ORDER_CANCELLED.equals(event.type())) {
            shipmentService.followOrderCancellation(orderId(event));
        } else if (STOCK_RESTORE_REQUESTED.equals(event.type())) {
            log.debug("Restore event {} applied by the stock ledger", event.id());
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

    /** Records a poison event as processed so the feed moves on; STOPPED when even that fails. */
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
