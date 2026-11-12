package com.yu.retailops.evaluation;

import com.yu.retailops.domain.Diagnosis.*;
import com.yu.retailops.runtime.*;
import com.yu.retailops.adapter.JsonModelPlanner;
import com.yu.retailops.tool.FixtureCatalog;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

@Component
@ConditionalOnProperty(name = "omniops.evaluate", havingValue = "true")
public class EvaluationRunner implements ApplicationRunner {
    private final FixtureCatalog catalog;
    private final DiagnosisRunner runner;
    private final Planner agent, workflow;
    private final JsonMapper json;
    private final ConfigurableApplicationContext context;
    private final String output;
    private final int repeats;
    private final String selectedCases;

    public EvaluationRunner(FixtureCatalog catalog, DiagnosisRunner runner, @Qualifier("agentPlanner") Planner agent,
                            @Qualifier("workflowPlanner") Planner workflow, JsonMapper json, ConfigurableApplicationContext context,
                            @Value("${omniops.evaluation-output:./evaluation-output}") String output,
                            @Value("${omniops.evaluation-repeats:1}") int repeats,
                            @Value("${omniops.evaluation-cases:}") String selectedCases) {
        this.catalog = catalog;
        this.runner = runner;
        this.agent = agent;
        this.workflow = workflow;
        this.json = json;
        this.context = context;
        this.output = output;
        this.repeats = repeats;
        this.selectedCases = selectedCases;
    }

    public record Row(String caseId, int repetition, String strategy, String provider, String model,
                      Outcome expected, Outcome actual, boolean evidenceCovered, boolean correct, Status status,
                      String stopCode,
                      int decisions, int tools, long durationMs, long promptTokens, long completionTokens,
                      String trace) {
    }

    public void run(ApplicationArguments ignored) throws Exception {
        if (repeats < 1 || repeats > 20) throw new IllegalArgumentException("Evaluation repeats must be 1..20");
        List<FixtureCatalog.Fixture> cases = selectedCases.isBlank() ? catalog.evaluationCases()
                : Arrays.stream(selectedCases.split(",")).map(String::trim).distinct().map(catalog::get).toList();
        if (!catalog.evaluationCases().containsAll(cases))
            throw new IllegalArgumentException("Use investigate for investigation fixtures; legacy evaluation only supports order-cases-v1");
        Path dir = Path.of(output).resolve(Instant.now().toString().replace(':', '-'));
        Files.createDirectories(dir.resolve("traces"));
        List<Row> rows = new ArrayList<>();
        evaluation:
        for (int repetition = 1; repetition <= repeats; repetition++) {
            // Alternate order to reduce systematic ordering bias in live model experiments.
            List<Planner> planners = repetition % 2 == 0 ? List.of(agent, workflow) : List.of(workflow, agent);
            for (var fixture : cases)
                for (Planner planner : planners) {
                    String strategy = planner == workflow ? "workflow" : "agent";
                    Task task = new Task();
                    task.id = UUID.randomUUID().toString();
                    task.createdAt = Instant.now();
                    task.provider = planner.provider();
                    task.modelVersion = planner.modelVersion();
                    task.input = new Input("请调查此订单未同步 POS 的情况", "A001", "O100", fixture.id(), strategy);
                    runner.run(task, planner, t -> {
                    }, () -> false);
                    String trace = "traces/" + fixture.id() + "-" + strategy + "-" + repetition + ".json";
                    Files.writeString(dir.resolve(trace), json.writerWithDefaultPrettyPrinter().writeValueAsString(task));
                    Outcome actual = task.report == null ? Outcome.UNRESOLVED : task.report.outcome();
                    String stopCode = task.events.get(task.events.size() - 1).code();
                    boolean evidenceCovered = hasRequiredObservations(fixture, task);
                    boolean correct = actual == fixture.expected() && "REPORT_READY".equals(stopCode) && evidenceCovered;
                    rows.add(new Row(fixture.id(), repetition, strategy, task.provider, task.modelVersion, fixture.expected(), actual,
                            evidenceCovered, correct, task.status, stopCode, task.decisions, task.toolCalls, task.activeMillis,
                            task.promptTokens, task.completionTokens, trace));
                    if (stopCode.startsWith("MODEL_HTTP_") || stopCode.equals("MODEL_REQUEST_BUDGET_EXHAUSTED"))
                        break evaluation;
                }
        }
        Files.writeString(dir.resolve("results.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "datasetVersion", "order-cases-v1", "split", "DEVELOPMENT_NOT_HOLDOUT", "promptVersion", "order-diagnosis-v3",
                "schemaVersion", "decision-v1", "temperature", 0, "businessData", "STUB", "rows", rows,
                "datasetSha256", resourceHash("/evaluation/order-cases-v1.json"),
                "promptSha256", resourceHash("/prompts/order-diagnosis-v3.txt"))));
        StringBuilder md = new StringBuilder("# Stage 1 对照评估\n\n业务数据：STUB；数据集：order-cases-v1；划分：开发回归集，非保留集。\n\n");
        md.append(agent.provider().equals("RULE_STUB_NOT_LLM")
                ? "本次比较规则 Stub 与固定 Workflow，仅验证执行与评分管线，不能证明大模型收益。\n\n"
                : "本次调用真实模型，但业务数据为模拟。仅评估开发场景，不能外推生产收益。\n\n");
        if (agent instanceof JsonModelPlanner remote)
            md.append("本进程模型请求次数（含失败请求）：").append(remote.requestsUsed()).append("。\n\n");
        md.append("指标只评估当前状态与建议动作，不代表历史根因准确率。每例必须完成标准答案要求的最少观测次数并引用相应证据；直接猜未知不计正确。金额成本未知；保留 Token 用量供按实际账单计算。\n\n");
        md.append("|策略|样本数|正确数|准确率|转人工比例|平均工具调用|P95 耗时 ms|\n|---|---:|---:|---:|---:|---:|---:|\n");
        for (String strategy : List.of("workflow", "agent")) {
            var selected = rows.stream().filter(r -> r.strategy().equals(strategy)).toList();
            if (selected.isEmpty()) continue;
            long correct = selected.stream().filter(Row::correct).count();
            long human = selected.stream().filter(r -> r.status() == Status.NEEDS_HUMAN).count();
            var times = selected.stream().map(Row::durationMs).sorted().toList();
            md.append(String.format(Locale.ROOT, "|%s|%d|%d|%.1f%%|%.1f%%|%.2f|%d|%n", strategy, selected.size(), correct,
                    100.0 * correct / selected.size(), 100.0 * human / selected.size(), selected.stream().mapToInt(Row::tools).average().orElse(0),
                    times.get((int) Math.ceil(times.size() * 0.95) - 1)));
        }
        md.append("\n|案例|轮次|策略|预期|实际|正确|工具调用|停止原因|\n|---|---:|---|---|---|---|---:|---|\n");
        for (Row r : rows)
            md.append(String.format("|%s|%d|%s|%s|%s|%s|%d|%s|%n", r.caseId(), r.repetition(), r.strategy(),
                    r.expected(), r.actual(), r.correct(), r.tools(), r.stopCode()));
        md.append("\n完整轨迹见 traces；固定 Workflow 顺序为本地→POS→日志→必要时平台，不做日志驱动复查。规则 Stub 的 POS_RECEIPT 分支是预编程示范，不是未知日志理解。应增加更强的 Workflow 基线和独立保留集后再评价模型增益。\n");
        Files.writeString(dir.resolve("report.md"), md);
        System.out.println("Evaluation report: " + dir.resolve("report.md").toAbsolutePath());
        context.close();
    }

    public static boolean hasRequiredObservations(FixtureCatalog.Fixture fixture, Task task) {
        if (task.report == null || fixture.requiredObservations() == null) return false;
        Set<String> cited = new HashSet<>(task.report.evidenceIds());
        return fixture.requiredObservations().entrySet().stream().allMatch(requirement ->
                task.evidence.stream().filter(e -> e.tool().equals(requirement.getKey()) && cited.contains(e.id())).count() >= requirement.getValue());
    }

    private String resourceHash(String resource) throws Exception {
        try (var in = getClass().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("Missing evaluation resource");
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
        }
    }
}
