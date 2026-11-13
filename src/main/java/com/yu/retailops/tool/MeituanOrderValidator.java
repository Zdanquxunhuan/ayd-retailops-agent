package com.yu.retailops.tool;

import com.yu.retailops.domain.Diagnosis.*;
import com.yu.retailops.tool.MeituanOrderClient.*;
import java.time.Duration;
import java.util.*;

/** Deterministic simulation-contract checks; this never diagnoses a historical cause. */
public final class MeituanOrderValidator {
    public static final String CONTRACT = "meituan-simulation-v1";
    public ToolResult validate(Request request, Response response) {
        var data = new LinkedHashMap<String, Object>();
        data.put("source", "MEITUAN_MOCK");
        data.put("validationContract", CONTRACT);
        data.put("payloadValidated", false);
        data.put("targetMatched", false);
        data.put("productionPayloadValidated", false);
        data.put("timeBasis", "SIMULATED_SCENARIO");
        if (response == null || response.status() == null)
            return result(ResultStatus.UNKNOWN, "INDETERMINATE", false, data, "MOCK_RESPONSE_UNAVAILABLE");
        // Transport/business errors always win over window and absence claims; upstream text is never returned.
        if (Set.of(ResultStatus.TIMEOUT, ResultStatus.ERROR, ResultStatus.DENIED, ResultStatus.UNKNOWN).contains(response.status())) {
            String code = switch (response.status()) {
                case TIMEOUT -> "PLATFORM_TIMEOUT";
                case DENIED -> "PLATFORM_DENIED";
                case UNKNOWN -> "PLATFORM_UNKNOWN";
                default -> "RATE_LIMITED".equals(response.errorCode()) ? "PLATFORM_RATE_LIMITED" : "PLATFORM_REJECTED";
            };
            return result(response.status(), "INDETERMINATE", false, data, code);
        }
        if (!Objects.equals(request.storeId(), response.scopeStoreId())
                || !Objects.equals(request.orderId(), response.scopeOrderId()))
            return result(ResultStatus.DENIED, "INDETERMINATE", false, data, "PLATFORM_SCOPE_MISMATCH");
        if (response.candidates() == null || response.evaluatedAt() == null)
            return result(ResultStatus.UNKNOWN, "INDETERMINATE", false, data, "PLATFORM_INVALID_RESPONSE");
        data.put("candidateCount", response.candidates().size());
        data.put("evaluatedAt", response.evaluatedAt().toString());
        var start = response.evaluatedAt().minus(Duration.ofDays(5));
        data.put("windowStart", start.toString());
        data.put("windowEnd", response.evaluatedAt().toString());
        if (!response.complete() || response.status() == ResultStatus.PARTIAL)
            return result(ResultStatus.PARTIAL, "INDETERMINATE", false, data, "PLATFORM_PARTIAL_RESPONSE");
        if (response.requestedOrderTime() != null) {
            data.put("requestedOrderTime", response.requestedOrderTime().toString());
            if (response.requestedOrderTime().isAfter(response.evaluatedAt()))
                return result(ResultStatus.UNKNOWN, "INDETERMINATE", false, data, "PLATFORM_TIME_INVALID");
            if (response.requestedOrderTime().isBefore(start)) {
                if (!response.candidates().isEmpty())
                    return result(ResultStatus.UNKNOWN, "INDETERMINATE", false, data, "PLATFORM_WINDOW_CONFLICT");
                return result(ResultStatus.SUCCESS, "OUTSIDE_WINDOW", true, data, null);
            }
        }
        var orders = response.candidates();
        if (orders.isEmpty()) return result(ResultStatus.NOT_FOUND, "ABSENT", true, data, null);
        if (response.status() == ResultStatus.NOT_FOUND)
            return result(ResultStatus.UNKNOWN, "INDETERMINATE", false, data, "PLATFORM_STATUS_CONFLICT");
        // Reject mixed/foreign candidates without leaking the other object's values.
        if (orders.stream().anyMatch(o -> o == null || !Objects.equals(o.storeId(), request.storeId())
                || !Objects.equals(o.orderId(), request.orderId())))
            return result(ResultStatus.DENIED, "INDETERMINATE", false, data, "PLATFORM_TARGET_MISMATCH");
        if (orders.size() != 1) return result(ResultStatus.UNKNOWN, "MULTIPLE", true, data, "PLATFORM_MULTIPLE_ORDERS");
        data.put("targetMatched", true);
        Order order = orders.get(0);
        var missing = new ArrayList<String>();
        if (order.createdAt() == null) missing.add("createdAt");
        if (order.status() == null || order.status().isBlank()) missing.add("status");
        if (order.items() == null || order.items().isEmpty() || order.items().size() > 500) missing.add("items");
        else if (order.items().stream().anyMatch(i -> i == null || i.skuId() == null || i.skuId().isBlank()
                || i.quantity() <= 0 || i.unitPrice() == null || i.unitPrice().signum() < 0)) missing.add("itemFields");
        data.put("missingFields", List.copyOf(missing));
        if (!missing.isEmpty()) return result(ResultStatus.SUCCESS, "FOUND", true, data, "PLATFORM_PAYLOAD_INCOMPLETE");
        if (order.createdAt().isBefore(start) || order.createdAt().isAfter(response.evaluatedAt())
                || (response.requestedOrderTime() != null && !order.createdAt().equals(response.requestedOrderTime())))
            return result(ResultStatus.UNKNOWN, "INDETERMINATE", false, data, "PLATFORM_TIME_CONFLICT");
        data.put("payloadValidated", true);
        data.put("orderId", order.orderId());
        data.put("storeId", order.storeId());
        data.put("createdAt", order.createdAt().toString());
        data.put("itemCount", order.items().size());
        // No raw order, customer fields, arbitrary status strings or recovery payload enters the model.
        return result(ResultStatus.SUCCESS, "FOUND", true, data, null);
    }
    private ToolResult result(ResultStatus status, String outcome, boolean complete, Map<String,Object> data, String error) {
        return new ToolResult(status, outcome, complete, Map.copyOf(data), error);
    }
}
