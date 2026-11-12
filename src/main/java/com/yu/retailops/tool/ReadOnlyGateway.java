package com.yu.retailops.tool;

import com.yu.retailops.domain.Diagnosis.*;
import java.time.*;
import java.util.*;

public class ReadOnlyGateway implements AutoCloseable {
    public static final String LOCAL = "order.local.query", POS = "pos.order.query",
            PLATFORM = "meituan.order.search", LOGS = "logs.search", PUSH_HISTORY = "order.push.history";
    public static final Set<String> TOOLS = Set.of(LOCAL, POS, PLATFORM, LOGS, PUSH_HISTORY);
    private final ToolRegistry registry;
    private final ToolExecutor executor = new ToolExecutor();
    private final String allowedStore;
    private final Clock clock;
    public ReadOnlyGateway(FixtureCatalog catalog, String allowedStore, Clock clock) {
        this(new FixtureToolAdapter(catalog).registry(), allowedStore, clock);
    }
    public ReadOnlyGateway(ToolRegistry registry, String allowedStore, Clock clock) {
        this.registry=registry;this.allowedStore=allowedStore;this.clock=clock;
    }
    public List<com.yu.retailops.domain.Investigation.ToolDefinition> definitions() {
        return registry.all().stream().map(t -> new com.yu.retailops.domain.Investigation.ToolDefinition(t.name(),t.purpose())).toList();
    }
    public void close() { executor.close(); }
    public void authorize(Input input) {
        if (!allowedStore.equals(input.storeId())) throw new SecurityException("STORE_DENIED");
    }
    public void validate(Input input, Decision decision) {
        authorize(input);
        registry.require(decision.tool());
        if (decision.arguments() == null || !Objects.equals(input.storeId(), decision.arguments().storeId())
                || !Objects.equals(input.orderId(), decision.arguments().orderId()))
            throw new SecurityException("TARGET_MISMATCH");
    }
    public Evidence invoke(Input input, Decision decision, List<Evidence> history) {
        validate(input, decision);
        var definition = registry.require(decision.tool());
        ToolResult result = executor.execute(definition, input, history);
        var now = clock.instant();
        return new Evidence(UUID.randomUUID().toString(), decision.tool(), decision.arguments(), result, now, now.plusSeconds(120), definition.simulated());
    }
}

