package com.yu.retailops.runtime;

import com.yu.retailops.domain.Investigation.*;
import java.util.List;

/** Independent semantic review; approval never confirms a root cause. */
public interface DiagnosisReviewer {
    record Finding(String code, String explanation, List<String> evidenceIds) {}
    record ReviewResult(Boolean approved, List<Finding> findings) {}
    record ReviewReply(ReviewResult result, long promptTokens, long completionTokens) {}
    ReviewReply review(Context context, Proposal proposal) throws Exception;
}
