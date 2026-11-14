package com.yu.retailops.tool;

import com.yu.retailops.domain.Diagnosis.ResultStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Internal read contract, not an assertion about the vendor SDK schema. */
public interface MeituanOrderClient {
    record Request(String storeId, String orderId) {}
    record Item(String skuId, int quantity, BigDecimal unitPrice) {}
    record Order(String storeId, String orderId, Instant createdAt, String status, List<Item> items) {}
    record Response(ResultStatus status, boolean complete, String scopeStoreId, String scopeOrderId,
                    Instant evaluatedAt, Instant requestedOrderTime, List<Order> candidates, String errorCode) {}
    Response search(Request request) throws Exception;
}
