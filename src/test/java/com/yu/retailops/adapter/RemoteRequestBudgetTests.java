package com.yu.retailops.adapter;

import org.junit.jupiter.api.Test;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class RemoteRequestBudgetTests {
    @Test void reservesAtMostNineAttemptsEvenConcurrently() {
        var budget = new RemoteRequestBudget(9);
        long allowed = IntStream.range(0, 100).parallel().filter(i -> {
            try { budget.reserve(); return true; } catch (ModelCallException e) { return false; }
        }).count();
        assertEquals(9, allowed); assertEquals(9, budget.used());
        assertEquals("MODEL_REQUEST_BUDGET_EXHAUSTED", assertThrows(ModelCallException.class, budget::reserve).code());
    }
    @Test void refusesZeroBudget() { assertThrows(IllegalArgumentException.class, () -> new RemoteRequestBudget(0)); }
}
