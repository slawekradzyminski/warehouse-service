package com.awesome.warehouse.service.stock;

import com.awesome.warehouse.controller.exception.CustomException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Whether the stock ledger may be used: false until {@link StockImporter} has imported the
 * backend's ledger (or found the local one). Until then every stock command and read answers
 * 503 "Stock ledger not initialised", and nothing else writes to the ledger.
 */
@Component
public class StockReadiness {

    public static final String NOT_INITIALISED = "Stock ledger not initialised";

    private boolean ready;

    public synchronized boolean isReady() {
        return ready;
    }

    /** @throws CustomException 503 while the ledger is not initialised */
    public void requireReady() {
        if (!isReady()) {
            throw new CustomException(NOT_INITIALISED, HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    synchronized void markReady() {
        ready = true;
        notifyAll();
    }

    /** Back to "not initialised" (tests of the import). */
    synchronized void markNotReady() {
        ready = false;
    }

    /**
     * Waits until the ledger is ready.
     *
     * @return whether it became ready within the timeout
     */
    public synchronized boolean awaitReady(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (!ready) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                wait(Math.max(1, left / 1_000_000));
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
