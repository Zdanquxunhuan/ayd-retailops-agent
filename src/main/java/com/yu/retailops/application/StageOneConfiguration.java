package com.yu.retailops.application;

import com.yu.retailops.adapter.JsonModelPlanner;
import com.yu.retailops.persistence.FileTaskRepository;
import com.yu.retailops.runtime.*;
import com.yu.retailops.tool.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Clock;
import java.util.*;

@Configuration
public class StageOneConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    FixtureCatalog fixtureCatalog(JsonMapper json, Environment env) throws Exception {
        Set<String> profiles = new HashSet<>(Arrays.asList(env.getActiveProfiles()));
        if (!profiles.isEmpty() && !Set.of("stub", "test").containsAll(profiles))
            throw new IllegalStateException("Stage 1 currently provides only STUB business adapters; production/integration profiles are blocked");
        if (!"127.0.0.1".equals(env.getProperty("server.address")))
            throw new IllegalStateException("Local demonstration must bind to 127.0.0.1; production authentication is not implemented");
        return new FixtureCatalog(json);
    }

    @Bean
    ReadOnlyGateway gateway(FixtureCatalog catalog, @Value("${omniops.allowed-store}") String store, Clock clock, Environment env) throws Exception {
        var definitions = new ArrayList<>(new FixtureToolAdapter(catalog).registry().all());
        String mode = env.getProperty("omniops.logs.mode", "fixture");
        if ("local".equals(mode)) {
            var logs = new LocalLogAdapter(Path.of(env.getRequiredProperty("omniops.logs.directory")),
                    env.getRequiredProperty("omniops.logs.date"));
            definitions.removeIf(d -> d.name().equals(ReadOnlyGateway.LOGS));
            definitions.add(logs.definition());
        } else if (!"fixture".equals(mode)) throw new IllegalArgumentException("Unknown log mode");
        return new ReadOnlyGateway(new ToolRegistry(definitions), store, clock);
    }

    @Bean
    ReportPolicy reportPolicy(Clock clock) {
        return new ReportPolicy(clock);
    }

    @Bean("agentPlanner")
    Planner agentPlanner(ReportPolicy policy, Environment env) throws Exception {
        return switch (env.getRequiredProperty("omniops.model.provider")) {
            case "stub" -> new OfflinePlanner(policy, false);
            case "qwen" -> JsonModelPlanner.forQwen(env.getRequiredProperty("omniops.qwen.endpoint"),
                    env.getRequiredProperty("omniops.qwen.model"), env.getRequiredProperty("omniops.qwen.api-key"),
                    env.getProperty("omniops.model.max-requests", Integer.class, 10));
            case "remote" -> new JsonModelPlanner(env.getRequiredProperty("omniops.model.endpoint"),
                    env.getRequiredProperty("omniops.model.name"), env.getRequiredProperty("omniops.model.api-key"),
                    env.getProperty("omniops.model.max-requests", Integer.class, 10));
            default -> throw new IllegalArgumentException("Unknown model provider");
        };
    }

    @Bean("workflowPlanner")
    Planner workflowPlanner(ReportPolicy policy) {
        return new OfflinePlanner(policy, true);
    }

    @Bean(destroyMethod = "close")
    FileTaskRepository repository(JsonMapper json, @Value("${omniops.data-dir}") String dir) throws Exception {
        return new FileTaskRepository(Path.of(dir), json);
    }

    @Bean(destroyMethod = "close")
    DiagnosisRunner runner(ReadOnlyGateway gateway, ReportPolicy policy, Clock clock,
                           @Value("${omniops.max-decisions}") int decisions, @Value("${omniops.max-tools}") int calls,
                           @Value("${omniops.timeout-seconds}") int seconds) {
        return new DiagnosisRunner(gateway, policy, clock, decisions, calls, seconds);
    }

    @Bean(destroyMethod = "close")
    TaskService taskService(FileTaskRepository repository, DiagnosisRunner runner,
                            @Qualifier("agentPlanner") Planner agent, @Qualifier("workflowPlanner") Planner workflow,
                            FixtureCatalog fixtures, ReadOnlyGateway gateway, Clock clock) {
        return new TaskService(repository, runner, agent, workflow, fixtures, gateway, clock);
    }
}

