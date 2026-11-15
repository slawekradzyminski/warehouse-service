package com.awesome.warehouse.service.stock;

import com.awesome.warehouse.client.BackendClient;
import com.awesome.warehouse.client.StockLevel;
import com.awesome.warehouse.client.StockLevelsRequest;
import com.awesome.warehouse.client.StockLevelsResponse;
import com.awesome.warehouse.controller.exception.CustomException;
import com.awesome.warehouse.correlation.TracingSupport;
import com.awesome.warehouse.entity.stock.StockLevelOutboxEntity;
import com.awesome.warehouse.repository.stock.StockLevelOutboxRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockLevelDispatcherTest {

    private final StockLevelOutboxRepository repository = mock(StockLevelOutboxRepository.class);
    private final BackendClient backend = mock(BackendClient.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final List<StockLevelOutboxEntity> rows = new ArrayList<>();
    private StockLevelDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(repository.findAllByOrderByIdAsc(any())).thenAnswer(invocation -> rows.stream()
                .limit(invocation.<org.springframework.data.domain.Pageable>getArgument(0).getPageSize()).toList());
        org.mockito.Mockito.doAnswer(invocation -> {
            rows.removeIf(row -> invocation.<List<Long>>getArgument(0).contains(row.getId()));
            return null;
        }).when(repository).deleteAllByIdInBatch(any());
        when(backend.pushStockLevels(any())).thenReturn(new StockLevelsResponse(1, 0));
        dispatcher = new StockLevelDispatcher(repository, backend, transactions, TracingSupport.disabled(), true);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        dispatcher.shutdown();
    }

    private void row(long id, long productId, int quantity, long version) {
        rows.add(StockLevelOutboxEntity.builder().id(id).productId(productId).quantity(quantity)
                .ledgerVersion(version).build());
    }

    @Test
    void theNewestLevelOfEachProductIsPushedAndTheRowsAreDeleted() {
        row(1, 2, 5, 3);
        row(2, 1, 9, 7);
        row(3, 2, 4, 4);
        row(4, 2, 6, 2);

        assertThat(dispatcher.dispatchPending()).isEqualTo(2);

        verify(backend).pushStockLevels(new StockLevelsRequest(List.of(new StockLevel(1L, 9, 7),
                new StockLevel(2L, 4, 4))));
        verify(repository).deleteAllByIdInBatch(List.of(1L, 2L, 3L, 4L));
        assertThat(rows).isEmpty();
    }

    @Test
    void aRefusedPushKeepsTheRowsWithTheErrorAndStopsTheRun() {
        row(1, 1, 5, 3);
        when(backend.pushStockLevels(any()))
                .thenThrow(new CustomException("Backend unavailable", HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(dispatcher.dispatchPending()).isZero();

        verify(repository).recordFailure(List.of(1L), "503 Backend unavailable");
        verify(repository, never()).deleteAllByIdInBatch(any());
        verify(backend, times(1)).pushStockLevels(any());
    }

    @Test
    void fullBatchesArePushedUntilTheOutboxIsEmpty() {
        IntStream.rangeClosed(1, StockLevelDispatcher.BATCH_SIZE + 1).forEach(i -> row(i, i, 1, 1));

        assertThat(dispatcher.dispatchPending()).isEqualTo(StockLevelDispatcher.BATCH_SIZE + 1);

        verify(backend, times(2)).pushStockLevels(any());
        assertThat(rows).isEmpty();
    }

    @Test
    void anEmptyOutboxPushesNothing() {
        assertThat(dispatcher.dispatchPending()).isZero();
        verify(backend, never()).pushStockLevels(any());
    }

    @Test
    void pushingNeverRunsInsideATransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(dispatcher::dispatchPending).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theAfterCommitTriggerAndTheScheduledRetryPush() {
        row(1, 1, 5, 3);
        dispatcher.afterEnqueue(new StockLevelOutbox.Enqueued());
        assertThat(dispatcher.awaitIdle(Duration.ofSeconds(5))).isTrue();
        row(2, 1, 6, 4);
        dispatcher.retryDue();
        new StockLevelDispatcher(repository, backend, transactions, TracingSupport.disabled(), false).retryDue();

        verify(backend, times(2)).pushStockLevels(any());
        assertThat(rows).isEmpty();
    }

    @Test
    void aLongErrorIsTruncatedForItsColumn() {
        row(1, 1, 5, 3);
        when(backend.pushStockLevels(any())).thenThrow(new CustomException("x".repeat(600), HttpStatus.CONFLICT));

        dispatcher.dispatchPending();

        verify(repository).recordFailure(List.of(1L), ("409 " + "x".repeat(600)).substring(0, 500));
    }
}
