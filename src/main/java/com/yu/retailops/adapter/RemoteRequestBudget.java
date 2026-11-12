package com.yu.retailops.adapter;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * One shared budget for all tasks in this process; reserve before sending, including failed attempts.
 */
public final class RemoteRequestBudget {
    private final int limit;
    private final AtomicInteger used = new AtomicInteger();

    public RemoteRequestBudget(int limit) {
        if (limit < 1) throw new IllegalArgumentException("Request limit must be positive");
        this.limit = limit;
    }

    public void reserve() {
        while (true) {
            int current = used.get();
            if (current >= limit) throw new ModelCallException("MODEL_REQUEST_BUDGET_EXHAUSTED");
            if (used.compareAndSet(current, current + 1)) return;
        }
    }

    public int used() {
        return used.get();
    }
}
