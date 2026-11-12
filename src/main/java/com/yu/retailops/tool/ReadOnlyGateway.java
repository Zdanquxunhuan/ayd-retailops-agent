package com.yu.retailops.tool;

import com.yu.retailops.domain.Diagnosis.*;
import java.time.*;
import java.util.*;

public class ReadOnlyGateway {
    public static final String LOCAL = "order.local.query", POS = "pos.order.query",
            PLATFORM = "meituan.order.search", LOGS = "logs.search", PUSH_HISTORY = "order.push.history";
    public static final Set<String> TOOLS = Set.of(LOCAL, POS, PLATFORM, LOGS, PUSH_HISTORY);
    private final FixtureCatalog catalog;
    private final String allowedStore;
    private final Clock clock;
    public ReadOnlyGateway(FixtureCatalog catalog, String allowedStore, Clock clock) {
        this.catalog = catalog; this.allowedStore = allowedStore; this.clock = clock;
    }
    public void authorize(Input input) {
        if (!allowedStore.equals(input.storeId())) throw new SecurityException("STORE_DENIED");
    }
    public void validate(Input input, Decision decision) {
        authorize(input);
        if (!TOOLS.contains(decision.tool())) throw new SecurityException("TOOL_NOT_ALLOWLISTED");
        if (decision.arguments() == null || !Objects.equals(input.storeId(), decision.arguments().storeId())
                || !Objects.equals(input.orderId(), decision.arguments().orderId()))
            throw new SecurityException("TARGET_MISMATCH");
    }
    public Evidence invoke(Input input, Decision decision, List<Evidence> history) {
        validate(input, decision);
        var responses = catalog.get(input.fixtureId()).responses().get(decision.tool());
        long prior = history.stream().filter(e -> e.tool().equals(decision.tool())).count();
        ToolResult result = responses == null || responses.isEmpty()
                ? new ToolResult(ResultStatus.UNKNOWN, "INDETERMINATE", false, Map.of(), "FIXTURE_UNAVAILABLE")
                : responses.get((int) Math.min(prior, responses.size() - 1));
        // Fixture payloads are templates for the requested synthetic object, never real order data.
        if (result.data().containsKey("orderId")) {
            var data = new HashMap<>(result.data()); data.put("orderId", input.orderId());
            result = new ToolResult(result.status(), result.outcome(), result.complete(), Map.copyOf(data), result.errorCode());
        }
        var now = clock.instant();
        return new Evidence(UUID.randomUUID().toString(), decision.tool(), decision.arguments(), result, now, now.plusSeconds(120), true);
    }
}
