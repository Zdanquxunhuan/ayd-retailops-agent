package com.yu.retailops.runtime;

import com.yu.retailops.domain.Diagnosis.*;

import java.util.List;

public interface Planner {
    // No fixture ID, expected label, or previous evaluator score is exposed to a planner.
    record Context(String userGoal, String storeId, String orderId, List<Evidence> evidence, int remainingCalls) {
        public Input input() {
            return new Input(userGoal, storeId, orderId, null, null);
        }
    }

    ModelReply next(Context context) throws Exception;

    String provider();

    String modelVersion();
}
