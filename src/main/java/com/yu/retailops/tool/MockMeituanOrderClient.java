package com.yu.retailops.tool;

import com.yu.retailops.domain.Diagnosis.*;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.util.*;

/** Immutable raw responses. No business identity is rewritten and no mutable cursor is kept. */
public final class MockMeituanOrderClient implements MeituanOrderClient {
    private final Response response;
    public MockMeituanOrderClient(Response response) { this.response = response; }
    @Override public Response search(Request request) { return response; }

    public static final class Scenarios {
        public record Scenario(String fixtureId, List<Response> responses) {}
        private final Map<String, List<Response>> cases;
        public Scenarios(JsonMapper json) throws IOException {
            try (var in = getClass().getResourceAsStream("/evaluation/meituan-order-responses-v1.json")) {
                if (in == null) throw new IOException("Missing Meituan mock responses");
                var values = new LinkedHashMap<String, List<Response>>();
                for (Scenario scenario : json.readValue(in, Scenario[].class)) {
                    if (scenario.responses() == null || scenario.responses().isEmpty()
                            || values.putIfAbsent(scenario.fixtureId(), List.copyOf(scenario.responses())) != null)
                        throw new IOException("Invalid Meituan mock scenarios");
                }
                cases = Map.copyOf(values);
            }
        }
        public MeituanOrderClient forInvocation(Input input, List<Evidence> history) {
            var responses = cases.get(input.fixtureId());
            if (responses == null) return new MockMeituanOrderClient(null);
            // Persisted observations, not a process-wide counter: restart and concurrent tasks stay isolated.
            long prior = history.stream().filter(e -> ReadOnlyGateway.PLATFORM.equals(e.tool())).count();
            return new MockMeituanOrderClient(responses.get((int) Math.min(prior, responses.size() - 1)));
        }
    }
}
