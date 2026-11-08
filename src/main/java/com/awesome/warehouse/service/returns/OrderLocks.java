package com.awesome.warehouse.service.returns;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Serialises return requests for the same order inside this instance. The
 * backend used to lock the order row; orders now live in the backend, so the
 * returnable-quantity check is protected by a striped in-process lock (the
 * service runs as a single instance).
 */
@Component
public class OrderLocks {

    private static final int STRIPES = 64;

    private final ReentrantLock[] locks = new ReentrantLock[STRIPES];

    public OrderLocks() {
        for (int i = 0; i < STRIPES; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    public <T> T withOrderLock(long orderId, Supplier<T> action) {
        ReentrantLock lock = locks[Math.floorMod(Long.hashCode(orderId), STRIPES)];
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
