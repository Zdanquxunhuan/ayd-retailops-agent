package com.yu.retailops.tool;

import com.yu.retailops.domain.Diagnosis.*;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.util.*;

/** Expected labels never enter a planner context. Fixtures are development cases, not a holdout set. */
public class FixtureCatalog {
    public record Fixture(String id, String description, Outcome expected, Map<String, Integer> requiredObservations,
                          Map<String, List<ToolResult>> responses) {}
    private final List<Fixture> fixtures;
    private final List<Fixture> investigationFixtures;
    private final List<Fixture> meituanFixtures;
    private final MockMeituanOrderClient.Scenarios meituan;
    public FixtureCatalog(JsonMapper json) throws IOException {
        try (var in = getClass().getResourceAsStream("/evaluation/order-cases-v1.json")) {
            if (in == null) throw new IOException("Fixture resource missing");
            fixtures = List.of(json.readValue(in, Fixture[].class));
        }
        try (var in = getClass().getResourceAsStream("/evaluation/investigation-cases-v1.json")) {
            if (in == null) throw new IOException("Investigation fixture resource missing");
            investigationFixtures = List.of(json.readValue(in, Fixture[].class));
        }
        try (var in = getClass().getResourceAsStream("/evaluation/meituan-investigation-cases-v1.json")) {
            if (in == null) throw new IOException("Meituan fixtures missing");
            meituanFixtures = List.of(json.readValue(in, Fixture[].class));
        }
        meituan = new MockMeituanOrderClient.Scenarios(json);
    }
    public MeituanOrderClient meituanClient(Input input, List<Evidence> history) {
        return meituan.forInvocation(input, history);
    }
    public List<Fixture> all() { return java.util.stream.Stream.of(fixtures, investigationFixtures, meituanFixtures).flatMap(List::stream).toList(); }
    public List<Fixture> evaluationCases() { return fixtures; }
    public Fixture get(String id) {
        return all().stream().filter(f -> f.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown fixtureId"));
    }
}
