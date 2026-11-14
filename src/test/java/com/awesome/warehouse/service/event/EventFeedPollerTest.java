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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Edge cases of {@link EventFeedPoller}; the end-to-end behaviour is in {@code EventFeedFlowTest}. */
class EventFeedPollerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 11, 12, 0);
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    private final BackendClient backend = mock(BackendClient.class);
    private final ShipmentService shipments = mock(ShipmentService.class);
    private final EventCursorRepository cursors = mock(EventCursorRepository.class);
    private final ProcessedEventRepository processed = mock(ProcessedEventRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final EventCursorEntity cursor = new EventCursorEntity(EventFeedPoller.CONSUMER, 0L, NOW.minusDays(1));
    private final List<UUID> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(cursors.existsById(EventFeedPoller.CONSUMER)).thenReturn(true);
        when(cursors.findById(EventFeedPoller.CONSUMER)).thenAnswer(invocation -> Optional.of(cursor));
        when(cursors.findForUpdate(EventFeedPoller.CONSUMER)).thenAnswer(invocation -> Optional.of(cursor));
        when(processed.existsById(any())).thenAnswer(invocation -> stored.contains(invocation.<UUID>getArgument(0)));
        when(processed.save(any())).thenAnswer(invocation -> {
            stored.add(invocation.<ProcessedEventEntity>getArgument(0).getEventId());
            return invocation.getArgument(0);
        });
    }

    private EventFeedPoller poller(boolean enabled) {
        return new EventFeedPoller(backend, shipments, cursors, processed, transactions, CLOCK,
                com.awesome.warehouse.correlation.TracingSupport.disabled(), enabled);
    }

    private static FeedEvent event(long sequence, String type, Long orderId) {
        return new FeedEvent(sequence, UUID.randomUUID(), type, Instant.EPOCH, orderId, "client", null);
    }

    @Test
    void theScheduledPollRunsOnlyWhenEnabled() {
        when(backend.events(anyLong(), anyInt(), any())).thenReturn(new EventFeedPage(null, 0, false));

        poller(false).scheduledPoll();
        verify(backend, never()).events(anyLong(), anyInt(), any());
        poller(true).scheduledPoll();
        verify(backend).events(0, EventFeedPoller.PAGE_SIZE, List.of("order.cancelled"));
    }

    @Test
    void eachEventIsHandledUnderItsOwnCorrelationIdsAndTheRunUnderAGeneratedOne() {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        FeedEvent correlated = new FeedEvent(1, UUID.randomUUID(), "order.cancelled", Instant.EPOCH, 7L, "client",
                null, "cancel-7", traceId);
        FeedEvent invalid = new FeedEvent(2, UUID.randomUUID(), "order.cancelled", Instant.EPOCH, 8L, "client",
                null, "not valid!", "0".repeat(32));
        List<Map<String, String>> contexts = new ArrayList<>();
        doAnswer(invocation -> contexts.add(org.slf4j.MDC.getCopyOfContextMap()))
                .when(shipments).followOrderCancellation(any());
        when(backend.events(anyLong(), anyInt(), any())).thenAnswer(invocation -> {
            contexts.add(org.slf4j.MDC.getCopyOfContextMap());
            return new EventFeedPage(List.of(correlated, invalid), 2, false);
        });

        poller(true).scheduledPoll();

        Map<String, String> run = contexts.get(0);
        assertThat(run.get("requestId")).matches("[0-9a-f-]{36}");
        assertThat(run.get("traceId")).matches("[0-9a-f]{32}");
        assertThat(contexts.get(1)).containsEntry("requestId", "cancel-7").containsEntry("traceId", traceId);
        assertThat(contexts.get(1).get("spanId")).matches("[0-9a-f]{16}");
        assertThat(contexts.get(2).get("requestId")).matches("[0-9a-f-]{36}").isNotEqualTo(run.get("requestId"));
        assertThat(contexts.get(2).get("traceId")).matches("[0-9a-f]{32}").isNotEqualTo("0".repeat(32))
                .isNotEqualTo(run.get("traceId"));
        assertThat(org.slf4j.MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void afterAnEventTheMdcOfTheRunIsBack() {
        FeedEvent event = new FeedEvent(1, UUID.randomUUID(), "order.cancelled", Instant.EPOCH, 7L, "client",
                null, "cancel-7", null);
        when(backend.events(anyLong(), anyInt(), any())).thenReturn(new EventFeedPage(List.of(event), 1, false));
        com.awesome.warehouse.correlation.CorrelationContext run =
                com.awesome.warehouse.correlation.CorrelationContext.generate();

        run.run(() -> {
            poller(true).poll();
            assertThat(org.slf4j.MDC.get("requestId")).isEqualTo(run.requestId());
            assertThat(org.slf4j.MDC.get("spanId")).isEqualTo(run.spanId());
        });
    }

    @Test
    void theFirstRunCreatesTheCursorAtTheStartOfTheFeed() {
        when(cursors.existsById(EventFeedPoller.CONSUMER)).thenReturn(false);
        when(backend.events(anyLong(), anyInt(), any())).thenReturn(new EventFeedPage(List.of(), 0, false));

        poller(true).poll();

        ArgumentCaptor<EventCursorEntity> created = ArgumentCaptor.forClass(EventCursorEntity.class);
        verify(cursors).saveAndFlush(created.capture());
        assertThat(created.getValue().getConsumer()).isEqualTo(EventFeedPoller.CONSUMER);
        assertThat(created.getValue().getLastSequence()).isZero();
        assertThat(created.getValue().getUpdatedAt()).isEqualTo(NOW);
        assertThat(created.getValue().isNew()).isTrue();
    }

    @Test
    void aCursorCreatedConcurrentlyByAnotherInstanceIsUsed() {
        when(cursors.existsById(EventFeedPoller.CONSUMER)).thenReturn(false);
        when(cursors.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));
        when(backend.events(anyLong(), anyInt(), any()))
                .thenReturn(new EventFeedPage(List.of(event(1, "order.cancelled", 7L)), 1, false));

        assertThat(poller(true).poll()).isEqualTo(1);
        verify(shipments).followOrderCancellation(7L);
    }

    @Test
    void eachHandledEventAdvancesTheCursorAndIsRecorded() {
        when(backend.events(anyLong(), anyInt(), any()))
                .thenReturn(new EventFeedPage(List.of(event(3, "order.cancelled", 7L), event(5, "order.cancelled", 8L)),
                        9, false));

        assertThat(poller(true).poll()).isEqualTo(2);

        verify(shipments).followOrderCancellation(7L);
        verify(shipments).followOrderCancellation(8L);
        assertThat(stored).hasSize(2);
        assertThat(cursor.getLastSequence()).isEqualTo(9);
        assertThat(cursor.getUpdatedAt()).isEqualTo(NOW);
        verify(cursors, times(3)).save(cursor);
    }

    @Test
    void theCursorNeverMovesBackwards() {
        cursor.setLastSequence(10);
        when(backend.events(anyLong(), anyInt(), any()))
                .thenReturn(new EventFeedPage(List.of(event(4, "order.cancelled", 7L)), 10, false));

        poller(true).poll();

        assertThat(cursor.getLastSequence()).isEqualTo(10);
        verify(cursors, never()).save(any());
    }

    @Test
    void anEventAlreadyProcessedIsNotHandledAgainButMovesTheCursor() {
        FeedEvent seen = event(3, "order.cancelled", 7L);
        stored.add(seen.id());
        when(backend.events(anyLong(), anyInt(), any())).thenReturn(new EventFeedPage(List.of(seen), 0, false));

        assertThat(poller(true).poll()).isZero();

        verify(shipments, never()).followOrderCancellation(any());
        verify(processed, never()).save(any());
        assertThat(cursor.getLastSequence()).isEqualTo(3);
    }

    @Test
    void eventsOfUnknownTypesAreRecordedButNotHandled() {
        when(backend.events(anyLong(), anyInt(), any()))
                .thenReturn(new EventFeedPage(List.of(event(1, "shipment.shipped", 7L)), 1, false));

        assertThat(poller(true).poll()).isEqualTo(1);

        verify(shipments, never()).followOrderCancellation(any());
        assertThat(stored).hasSize(1);
        assertThat(cursor.getLastSequence()).isEqualTo(1);
    }

    @Test
    void theOrderIdFallsBackToTheEventData() {
        FeedEvent fromData = new FeedEvent(1, UUID.randomUUID(), "order.cancelled", Instant.EPOCH, null, "client",
                Map.of("orderId", 12));
        when(backend.events(anyLong(), anyInt(), any())).thenReturn(new EventFeedPage(List.of(fromData), 1, false));

        poller(true).poll();

        verify(shipments).followOrderCancellation(12L);
    }

    @Test
    void aHandlerFailureIsSkippedAndTheRunContinues() {
        doAnswer(invocation -> {
            throw new IllegalStateException("boom");
        }).when(shipments).followOrderCancellation(7L);
        FeedEvent failing = event(1, "order.cancelled", 7L);
        when(backend.events(anyLong(), anyInt(), any()))
                .thenReturn(new EventFeedPage(List.of(failing, event(2, "order.cancelled", 8L)), 2, false));

        assertThat(poller(true).poll()).isEqualTo(1);

        assertThat(stored).contains(failing.id()).hasSize(2);
        verify(shipments).followOrderCancellation(8L);
        assertThat(cursor.getLastSequence()).isEqualTo(2);
    }

    @Test
    void whenAFailedEventCannotEvenBeRecordedTheRunStopsBeforeIt() {
        doAnswer(invocation -> {
            throw new IllegalStateException("database down");
        }).when(shipments).followOrderCancellation(7L);
        FeedEvent handled = event(1, "order.cancelled", 6L);
        when(processed.existsById(any())).thenReturn(false);
        org.mockito.Mockito.doReturn(null).doThrow(new IllegalStateException("database down"))
                .when(processed).save(any());
        when(backend.events(anyLong(), anyInt(), any()))
                .thenReturn(new EventFeedPage(List.of(handled, event(2, "order.cancelled", 7L),
                        event(3, "order.cancelled", 8L)), 3, false));

        assertThat(poller(true).poll()).isEqualTo(1);

        verify(shipments, never()).followOrderCancellation(8L);
        assertThat(cursor.getLastSequence()).isEqualTo(1);
    }

    @Test
    void aFailedEventAlreadyRecordedByAnotherInstanceOnlyMovesTheCursor() {
        FeedEvent failing = event(1, "order.cancelled", 7L);
        doAnswer(invocation -> {
            stored.add(failing.id());
            throw new IllegalStateException("lost a race");
        }).when(shipments).followOrderCancellation(7L);
        // nextCursor 0: only the skip itself can move the cursor past the event.
        when(backend.events(anyLong(), anyInt(), any()))
                .thenReturn(new EventFeedPage(List.of(failing), 0, false));

        assertThat(poller(true).poll()).isZero();

        verify(processed, never()).save(any());
        assertThat(cursor.getLastSequence()).isEqualTo(1);
    }

    @Test
    void aBackendOutageEndsTheRunWithWhatWasHandledSoFar() {
        when(backend.events(eq(0L), anyInt(), any()))
                .thenReturn(new EventFeedPage(List.of(event(1, "order.cancelled", 7L)), 1, true));
        when(backend.events(eq(1L), anyInt(), any()))
                .thenThrow(new CustomException("Backend unavailable", HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(poller(true).poll()).isEqualTo(1);
        assertThat(cursor.getLastSequence()).isEqualTo(1);
    }

    @Test
    void oneRunReadsAtMostTenPages() {
        when(backend.events(anyLong(), anyInt(), any())).thenAnswer(invocation -> {
            long after = invocation.getArgument(0);
            List<FeedEvent> page = LongStream.rangeClosed(after + 1, after + 2)
                    .mapToObj(sequence -> event(sequence, "order.cancelled", sequence)).toList();
            return new EventFeedPage(page, after + 2, true);
        });

        assertThat(poller(true).poll()).isEqualTo(20);

        verify(backend, times(EventFeedPoller.MAX_PAGES_PER_RUN)).events(anyLong(), anyInt(), any());
        assertThat(cursor.getLastSequence()).isEqualTo(20);
    }
}
