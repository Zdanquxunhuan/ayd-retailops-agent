package com.yu.retailops.tool;

import com.yu.retailops.domain.Diagnosis.*;
import java.util.List;
import java.util.function.BiFunction;

/** Read-only simulated adapter. Real SDK integration requires a separate contract review. */
public final class MeituanOrderAdapter {
    private final BiFunction<Input,List<Evidence>,MeituanOrderClient> clients;
    public MeituanOrderAdapter(MeituanOrderClient client) { this((input, history) -> client); }
    public MeituanOrderAdapter(BiFunction<Input,List<Evidence>,MeituanOrderClient> clients) { this.clients = clients; }
    public ToolRegistry.Definition definition() {
        return new ToolRegistry.Definition(ReadOnlyGateway.PLATFORM, MeituanOrderValidator.CONTRACT,
                "查询美团订单模拟响应；区分查询完整性与载荷完整性，返回窗口依据与缺失字段，不证明历史根因。",
                2000, 12000, true, (input, history) -> {
                    var request = new MeituanOrderClient.Request(input.storeId(), input.orderId());
                    return new MeituanOrderValidator().validate(request, clients.apply(input, history).search(request));
                });
    }
}
