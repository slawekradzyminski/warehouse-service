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
import com.awesome.warehouse.service.stock.StockReadiness;
import com.awesome.warehouse.service.stock.StockRestoreConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.SQLTransientConnectionException;
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
import java.util.stream.Stream;

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
    private final StockRestoreConsumer stockRestores = mock(StockRestoreConsumer.class);
    private final StockReadiness stockReadiness = mock(StockReadiness.class);
    private final EventCursorRepository cursors = mock(EventCursorRepository.class);
    private final ProcessedEventRepository processed = mock(ProcessedEventRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final EventCursorEntity cursor = new EventCursorEntity(EventFeedPoller.CONSUMER, 0L, NOW.minusDays(1));
    private final List<UUID> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(stockReadiness.isReady()).thenReturn(true);
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
        return new EventFeedPoller(backend, shipments, stockRestores, stockReadiness, cursors, processed, transactions,
                CLOCK,
                com.awesome.warehouse.correlation.TracingSupport.disabled(), enabled, "feed");
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
        verify(backend).events(0, EventFeedPoller.PAGE_SIZE, List.of("order.cancelled", "stock.restore-requested"));
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
    void aPoisonEventIsSkippedAndTheRunContinues() {
        doAnswer(invocation -> {
            throw new IllegalArgumentException("Event has no orderId");
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
    void droppedConnectionKeepsCursor() {
        FeedEvent cancelled = event(1, "order.cancelled", 7L);
        FeedEvent next = event(2, "order.cancelled", 8L);
        when(backend.events(anyLong(), anyInt(), any())).thenReturn(new EventFeedPage(List.of(cancelled, next), 2, false));
        when(shipments.followOrderCancellation(7L))
                .thenThrow(new DataAccessResourceFailureException("Unable to acquire JDBC Connection"))
                .thenReturn(true);
        EventFeedPoller poller = poller(true);

        assertThat(poller.poll()).isZero();

        assertThat(cursor.getLastSequence()).isZero();
        assertThat(stored).isEmpty();
        verify(processed, never()).save(any());
        verify(shipments, never()).followOrderCancellation(8L);

        assertThat(poller.poll()).isEqualTo(2);

        verify(shipments, times(2)).followOrderCancellation(7L);
        assertThat(stored).containsExactly(cancelled.id(), next.id());
        assertThat(cursor.getLastSequence()).isEqualTo(2);
    }

    @Test
    void queryTimeoutKeepsCursor() {
        FeedEvent cancelled = event(1, "order.cancelled", 7L);
        when(backend.events(anyLong(), anyInt(), any())).thenReturn(new EventFeedPage(List.of(cancelled), 1, false));
        when(shipments.followOrderCancellation(7L)).thenThrow(new QueryTimeoutException("canceling statement due to "
                + "statement timeout"));

        assertThat(poller(true).poll()).isZero();

        assertThat(cursor.getLastSequence()).isZero();
        assertThat(stored).isEmpty();
    }

    static Stream<RuntimeException> infrastructureFailures() {
        return Stream.of(
                new DataAccessResourceFailureException("connection reset"),
                new CannotCreateTransactionException("Could not open JPA EntityManager for transaction"),
                new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection"),
                new JpaSystemException(new RuntimeException("connection closed")),
                new TransactionSystemException("Could not commit JPA transaction"),
                new QueryTimeoutException("statement timeout"),
                new DataIntegrityViolationException("duplicate key"),
                new IllegalArgumentException("wrapped", new SQLTransientConnectionException("pool exhausted")),
                new jakarta.persistence.PersistenceException("connection lost"),
                new CustomException("Database unavailable", HttpStatus.SERVICE_UNAVAILABLE),
                new IllegalStateException("unexpected"),
                new ArithmeticException("integer overflow"));
    }

    @ParameterizedTest
    @MethodSource("infrastructureFailures")
    void aFailureOutsideTheEventStopsTheRunWithTheCursorUnchanged(RuntimeException failure) {
        when(backend.events(anyLong(), anyInt(), any())).thenReturn(new EventFeedPage(
                List.of(event(1, "order.cancelled", 7L), event(2, "order.cancelled", 8L)), 2, false));
        when(shipments.followOrderCancellation(7L)).thenThrow(failure);

        assertThat(poller(true).poll()).isZero();

        verify(shipments, never()).followOrderCancellation(8L);
        assertThat(stored).isEmpty();
        assertThat(cursor.getLastSequence()).isZero();
    }

    @ParameterizedTest
    @MethodSource("infrastructureFailures")
    void aRestoreFailureOutsideTheEventStopsTheRunWithTheCursorUnchanged(RuntimeException failure) {
        FeedEvent restore = event(1, "stock.restore-requested", 42L);
        when(backend.events(anyLong(), anyInt(), any())).thenReturn(new EventFeedPage(List.of(restore), 1, false));
        when(stockRestores.restore(restore)).thenThrow(failure).thenReturn(true);
        EventFeedPoller poller = poller(true);

        assertThat(poller.poll()).isZero();

        assertThat(stored).isEmpty();
        assertThat(cursor.getLastSequence()).isZero();

        assertThat(poller.poll()).isEqualTo(1);

        assertThat(stored).containsExactly(restore.id());
        assertThat(cursor.getLastSequence()).isEqualTo(1);
    }

    static Stream<RuntimeException> poisonFailures() {
        return Stream.of(
                new IllegalArgumentException("Event has no orderId"),
                new NumberFormatException("For input string: \"x\""),
                new tools.jackson.core.exc.StreamReadException(null, "Unexpected character"),
                new java.time.format.DateTimeParseException("bad date", "yesterday", 0),
                new CustomException("Order not found", HttpStatus.NOT_FOUND));
    }

    @ParameterizedTest
    @MethodSource("poisonFailures")
    void aPoisonEventIsStillSkipped(RuntimeException failure) {
        FeedEvent poison = event(1, "order.cancelled", 7L);
        FeedEvent next = event(2, "order.cancelled", 8L);
        when(backend.events(anyLong(), anyInt(), any())).thenReturn(new EventFeedPage(List.of(poison, next), 2, false));
        when(shipments.followOrderCancellation(7L)).thenThrow(failure);

        assertThat(poller(true).poll()).isEqualTo(1);

        assertThat(stored).containsExactly(poison.id(), next.id());
        assertThat(cursor.getLastSequence()).isEqualTo(2);
    }

    @Test
    void whenAFailedEventCannotEvenBeRecordedTheRunStopsBeforeIt() {
        doAnswer(invocation -> {
            throw new IllegalArgumentException("malformed");
        }).when(shipments).followOrderCancellation(7L);
        FeedEvent handled = event(1, "order.cancelled", 6L);
        when(processed.existsById(any())).thenReturn(false);
        org.mockito.Mockito.doReturn(null).doThrow(new DataAccessResourceFailureException("database down"))
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
            throw new IllegalArgumentException("malformed");
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

    @Test
    void theFeedIsNotReadBeforeTheStockLedgerIsInitialised() {
        when(stockReadiness.isReady()).thenReturn(false);

        assertThat(poller(true).poll()).isZero();

        verify(backend, never()).events(anyLong(), anyInt(), any());
        verify(cursors, never()).existsById(any());
    }

    @Test
    void aRestoreEventIsAppliedInItsOwnTransactionBeforeItIsRecorded() {
        FeedEvent restore = event(1, "stock.restore-requested", 42L);
        when(backend.events(anyLong(), anyInt(), any())).thenReturn(new EventFeedPage(List.of(restore), 1, false));
        when(stockRestores.restore(restore)).thenAnswer(invocation -> {
            assertThat(stored).as("recorded only after the restore").isEmpty();
            return true;
        });

        assertThat(poller(true).poll()).isEqualTo(1);

        verify(stockRestores).restore(restore);
        verify(shipments, never()).followOrderCancellation(anyLong());
        assertThat(stored).containsExactly(restore.id());
        assertThat(cursor.getLastSequence()).isEqualTo(1);
    }

    @Test
    void aCancellationFollowsTheShipmentButNeverRestoresStock() {
        FeedEvent cancelled = event(1, "order.cancelled", 42L);
        when(backend.events(anyLong(), anyInt(), any())).thenReturn(new EventFeedPage(List.of(cancelled), 1, false));

        assertThat(poller(true).poll()).isEqualTo(1);

        verify(shipments).followOrderCancellation(42L);
        verify(stockRestores, never()).restore(any());
    }

    @Test
    void aTransientRestoreFailureStopsTheRunBeforeTheEventIsRecorded() {
        FeedEvent restore = event(1, "stock.restore-requested", 42L);
        FeedEvent later = event(2, "order.cancelled", 43L);
        when(backend.events(anyLong(), anyInt(), any())).thenReturn(new EventFeedPage(List.of(restore, later), 2, false));
        when(stockRestores.restore(restore)).thenThrow(new org.springframework.dao.QueryTimeoutException("slow"));

        assertThat(poller(true).poll()).isZero();

        verify(shipments, never()).followOrderCancellation(anyLong());
        assertThat(stored).isEmpty();
        assertThat(cursor.getLastSequence()).isZero();
    }

    @Test
    void aMalformedRestoreEventIsLoggedAndRecorded() {
        FeedEvent restore = event(1, "stock.restore-requested", 42L);
        when(backend.events(anyLong(), anyInt(), any())).thenReturn(new EventFeedPage(List.of(restore), 1, false));
        when(stockRestores.restore(restore)).thenThrow(new IllegalArgumentException("bad restore"));

        assertThat(poller(true).poll()).isEqualTo(1);

        assertThat(stored).containsExactly(restore.id());
        assertThat(cursor.getLastSequence()).isEqualTo(1);
    }
}
