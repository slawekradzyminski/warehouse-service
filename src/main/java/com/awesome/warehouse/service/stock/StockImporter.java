package com.awesome.warehouse.service.stock;

import com.awesome.warehouse.client.BackendClient;
import com.awesome.warehouse.client.ExportedMovement;
import com.awesome.warehouse.client.ExportedStockItem;
import com.awesome.warehouse.client.InventoryExportPage;
import com.awesome.warehouse.correlation.TracingSupport;
import com.awesome.warehouse.entity.stock.StockItemEntity;
import com.awesome.warehouse.repository.stock.StockItemRepository;
import com.awesome.warehouse.repository.stock.StockMovementRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one-time import of the backend's stock ledger (contract 6). On startup, when the local
 * ledger tables are empty, it reads {@code GET /internal/v1/inventory/export} page by page (no
 * database connection is held meanwhile) and stores everything in one transaction: every item
 * with {@code ledgerVersion} 1, every movement with its backend id and timestamp, the movement
 * identity moved past the largest id, and one level per item in the outbox, so the backend
 * projection gets version 1. Items that already exist are skipped. A failed import (for example
 * the backend is not reachable yet) is retried with backoff; until it succeeds
 * {@link StockReadiness} keeps every stock endpoint at 503.
 */
@Slf4j
@Component
public class StockImporter {

    static final int PAGE_SIZE = 500;
    private static final Duration FIRST_RETRY = Duration.ofSeconds(1);
    private static final Duration MAX_RETRY = Duration.ofSeconds(30);
    private static final String INSERT_ITEM = "insert into %sstock_items (product_id, quantity, ledger_version, "
            + "created_at, updated_at) values (?, ?, 1, ?, ?)";
    private static final String INSERT_MOVEMENT = "insert into %sstock_movements (id, product_id, order_id, type, "
            + "delta, quantity_after, actor, reason, request_id, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final BackendClient backendClient;
    private final StockItemRepository items;
    private final StockMovementRepository movements;
    private final StockLevelOutbox levelOutbox;
    private final StockReadiness readiness;
    private final StockLedger ledger;
    private final JdbcTemplate jdbc;
    private final TracingSupport tracing;
    private final TransactionTemplate transaction;
    private final boolean enabled;
    /** {@code <schema>.} of the JPA tables, for the plain JDBC statements (empty without a default schema). */
    private final String schemaPrefix;
    private volatile boolean running = true;

    public StockImporter(BackendClient backendClient, StockItemRepository items, StockMovementRepository movements,
                         StockLevelOutbox levelOutbox, StockReadiness readiness, StockLedger ledger, JdbcTemplate jdbc,
                         TracingSupport tracing, PlatformTransactionManager transactionManager,
                         @Value("${warehouse.stock.import-enabled:true}") boolean enabled,
                         @Value("${spring.jpa.properties.hibernate.default_schema:}") String schema) {
        this.backendClient = backendClient;
        this.items = items;
        this.movements = movements;
        this.levelOutbox = levelOutbox;
        this.readiness = readiness;
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.tracing = tracing;
        this.transaction = new TransactionTemplate(transactionManager);
        this.enabled = enabled;
        this.schemaPrefix = schema == null || schema.isBlank() ? "" : schema.trim() + ".";
    }

    /** Starts the import on its own thread once the application is up; disabled, the ledger is ready at once. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled) {
            log.warn("Stock ledger import disabled; the ledger starts from its local tables");
            readiness.markReady();
            return;
        }
        Thread thread = new Thread(this::importUntilReady, "stock-import");
        thread.setDaemon(true);
        thread.start();
    }

    @PreDestroy
    void stop() {
        running = false;
    }

    private void importUntilReady() {
        Duration delay = FIRST_RETRY;
        while (running && !readiness.isReady()) {
            try {
                tracing.forJob().run(this::importLedger);
            } catch (RuntimeException e) {
                log.warn("Stock ledger import failed ({}); retrying in {}s", e.getMessage(), delay.toSeconds());
                if (!sleep(delay)) {
                    return;
                }
                delay = delay.multipliedBy(2).compareTo(MAX_RETRY) > 0 ? MAX_RETRY : delay.multipliedBy(2);
            }
        }
    }

    private static boolean sleep(Duration delay) {
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Imports the backend's ledger when the local one is empty, then marks the ledger ready.
     *
     * @return the number of items imported (0 when the local ledger already had data)
     */
    public synchronized int importLedger() {
        if (Boolean.TRUE.equals(transaction.execute(status -> hasData()))) {
            log.info("Stock ledger present; no import needed");
            readiness.markReady();
            return 0;
        }
        List<ExportedStockItem> exported = fetchAll();
        Integer imported = transaction.execute(status -> store(exported));
        readiness.markReady();
        log.info("Stock ledger imported from the backend: {} items, {} movements", imported,
                exported.stream().mapToInt(item -> item.movements().size()).sum());
        return imported == null ? 0 : imported;
    }

    private boolean hasData() {
        return items.count() > 0 || movements.count() > 0;
    }

    private List<ExportedStockItem> fetchAll() {
        List<ExportedStockItem> exported = new ArrayList<>();
        Long after = null;
        do {
            InventoryExportPage page = backendClient.exportInventory(after, PAGE_SIZE);
            exported.addAll(page.items());
            Long next = page.nextAfterProductId();
            if (next != null && after != null && next <= after) {
                throw new IllegalStateException("Inventory export did not advance after product " + after);
            }
            after = next;
        } while (after != null);
        return exported;
    }

    /** Stores the export; another instance that imported first leaves nothing to do. */
    private int store(List<ExportedStockItem> exported) {
        if (hasData()) {
            log.info("Stock ledger was imported concurrently");
            return 0;
        }
        LocalDateTime now = ledger.now();
        int imported = 0;
        for (ExportedStockItem item : exported) {
            if (items.existsById(item.productId())) {
                log.info("Product {} already has a stock item; its export is skipped", item.productId());
                continue;
            }
            jdbc.update(INSERT_ITEM.formatted(schemaPrefix), item.productId(), item.quantity(), now, now);
            jdbc.batchUpdate(INSERT_MOVEMENT.formatted(schemaPrefix), item.movements().stream().map(movement -> row(item, movement)).toList());
            StockItemEntity stored = new StockItemEntity(item.productId(), item.quantity(), 1, now);
            levelOutbox.enqueue(stored);
            imported++;
        }
        moveIdentityPastMaximum();
        return imported;
    }

    private static Object[] row(ExportedStockItem item, ExportedMovement movement) {
        return new Object[] {movement.id(), item.productId(), movement.orderId(), movement.type().name(),
            movement.delta(), movement.quantityAfter(), movement.actor(), movement.reason(), movement.requestId(),
            movement.createdAt()};
    }

    /** New movements continue after the largest imported id. */
    private void moveIdentityPastMaximum() {
        long max = movements.maxId();
        String database = jdbc.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getDatabaseProductName());
        if (database != null && database.toLowerCase(Locale.ROOT).contains("postgres")) {
            jdbc.queryForObject("select setval(pg_get_serial_sequence(?, 'id'), ?, ?)",
                    Long.class, schemaPrefix + "stock_movements", Math.max(max, 1), max > 0);
        } else {
            jdbc.execute("alter table " + schemaPrefix + "stock_movements alter column id restart with " + (max + 1));
        }
    }
}
