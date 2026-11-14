package com.yu.retailops.evaluation;

import com.yu.retailops.adapter.JsonModelPlanner;
import com.yu.retailops.domain.Diagnosis.*;
import com.yu.retailops.domain.Investigation.Intent;
import com.yu.retailops.persistence.FileTaskRepository;
import com.yu.retailops.runtime.*;
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

/**
 * A single real-model investigation, with a readable trace. This is not an effectiveness benchmark.
 */
@Component
@ConditionalOnProperty(name = "omniops.investigate", havingValue = "true")
public class InvestigationDemoRunner implements ApplicationRunner {
    private final DiagnosisRunner runner;
    private final Planner planner;
    private final FileTaskRepository repository;
    private final JsonMapper json;
    private final FixtureCatalog fixtures;
    private final ConfigurableApplicationContext context;
    private final String fixture, output;
    private final Intent intent;

    public InvestigationDemoRunner(DiagnosisRunner runner, @Qualifier("agentPlanner") Planner planner,
                                   FileTaskRepository repository, JsonMapper json, FixtureCatalog fixtures, ConfigurableApplicationContext context,
                                   @Value("${omniops.investigation-fixture:investigation-reversal}") String fixture,
                                   @Value("${omniops.investigation-output:./evaluation-output/investigation}") String output,
                                   @Value("${omniops.investigation-intent:DIAGNOSE_CAUSE}") Intent intent) {
        this.runner = runner;
        this.planner = planner;
        this.repository = repository;
        this.json = json;
        this.fixtures = fixtures;
        this.context = context;
        this.fixture = fixture;
        this.output = output;
        this.intent = intent;
    }

    public void run(ApplicationArguments ignored) throws Exception {
        fixtures.get(fixture);
        Task task = new Task();
        task.id = UUID.randomUUID().toString();
        task.createdAt = Instant.now();
        task.provider = planner.provider();
        task.modelVersion = planner.modelVersion();
        task.input = new Input(intent == Intent.CHECK_STATUS ? "确认订单当前是否已同步" : "调查这笔订单此前未同步的原因，说明依据、反证和还不清楚的部分。",
                "A001", "O100", fixture, "investigation", intent);
        runner.run(task, planner, repository::save, () -> false);
        Path dir = Path.of(output).resolve(task.createdAt.toString().replace(':', '-'));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("trace.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(task));
        Files.writeString(dir.resolve("report.md"), render(task));
        Files.writeString(dir.resolve("manifest.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "promptSha256", hash("/prompts/" + task.promptVersion + ".txt"),
                "datasetSha256", Map.of(
                        "order-cases-v1", hash("/evaluation/order-cases-v1.json"),
                        "investigation-cases-v1", hash("/evaluation/investigation-cases-v1.json"),
                        "meituan-investigation-cases-v1", hash("/evaluation/meituan-investigation-cases-v1.json"),
                        "meituan-order-responses-v1", hash("/evaluation/meituan-order-responses-v1.json")),
                "requestsUsed", planner instanceof JsonModelPlanner model ? model.requestsUsed() : 0,
                "dataSource", task.report.dataSource(), "purpose", "FUNCTIONAL_DEMONSTRATION_NOT_BENCHMARK")));
        System.out.println("Investigation report: " + dir.resolve("report.md").toAbsolutePath());
        System.out.println("Stop: " + task.investigation.stopCode + "; model decisions: " + task.decisions + "; tools: " + task.toolCalls);
        context.close();
    }

    public static String render(Task task) {
        StringBuilder md = new StringBuilder("# 大模型订单调查记录\n\n");
        md.append("## 用日常使用的话说明\n\n")
                .append("你问：").append(cell(task.input.userGoal())).append("\n\n")
                .append("当前查到：").append(task.report.summary()).append("\n\n")
                .append("原因调查：").append("INVESTIGATION_READY".equals(task.investigation.stopCode) ? "已生成供审阅的调查解释，仍未确认历史根因。" : "尚未完成可靠的原因判断；以下状态事实仍保留。")
                .append("\n\n系统实际没有执行补推、恢复或其他业务修改。各条证据需按 simulated/source 区分来源；模拟观察不能用于处理真实订单。\n\n")
                .append("接下来：历史原因需要核对推送记录、回执和业务日志；如果调查被程序拦截，应先修复调查流程再重跑。\n\n## 技术追踪记录\n\n");
        md.append("业务数据：").append(task.report.dataSource()).append("。模型：").append(task.provider).append(" / ").append(task.modelVersion)
                .append("。停止原因：").append(task.investigation.stopCode).append("。\n\n")
                .append("当前业务结论（Java）：").append(task.report.outcome()).append("。 ").append(task.report.summary()).append("\n\n")
                .append("历史根因：UNCONFIRMED。未执行任何修复。模型解释只供审阅，不等于已验证的因果事实。\n\n")
                .append("模型请求次数（含审查）：").append(task.decisions).append("；工具调用：").append(task.toolCalls)
                .append("；输入/输出 Token：").append(task.promptTokens).append(" / ").append(task.completionTokens).append("。\n\n")
                .append("## 模型每一步做了什么\n\n|轮次|动作|校验|简短理由|要验证的问题|\n|---:|---|---|---|---|\n");
        for (var turn : task.investigation.turns) {
            var p = turn.proposal();
            md.append('|').append(turn.round()).append('|').append(p == null ? "INVALID" : p.action()).append('|')
                    .append(turn.validationCode()).append('|').append(cell(p == null ? "" : p.reasonSummary())).append('|')
                    .append(cell(p == null || p.query() == null ? "" : p.query().tool() + ": " + p.query().questionToVerify())).append("|\n");
        }
        md.append("\n## 假设变化（均为模型提议）\n\n|轮次|假设|状态|支持证据|反证|尚缺证据|\n|---:|---|---|---|---|---|\n");
        for (var revision : task.investigation.hypothesisHistory) {
            var h = revision.hypothesis();
            md.append('|').append(revision.round()).append('|').append(cell(h.id() + ": " + h.statement())).append('|').append(h.status())
                    .append('|').append(cell(h.supportingEvidenceIds().toString())).append('|').append(cell(h.contradictingEvidenceIds().toString()))
                    .append('|').append(cell(h.missingEvidence().toString())).append("|\n");
        }
        md.append("\n## 假设时间范围与反驳条件\n\n|假设|范围|时段|命题类型|可推翻条件|\n|---|---|---|---|---|\n");
        for (var h : task.investigation.hypotheses) md.append('|').append(h.id()).append('|').append(h.temporalScope())
                .append('|').append(cell(h.timeWindow())).append('|').append(h.proposition()).append('|').append(cell(h.falsificationCriterion())).append("|\n");
        md.append("\n## 每轮旧假设复核（包含被拒绝的提议）\n\n|轮次|校验|假设|决定|考虑的新证据|理由|\n|---|---|---|---|---|---|\n");
        for (var turn : task.investigation.turns) if (turn.proposal() != null && turn.proposal().reviews() != null)
            for (var review : turn.proposal().reviews()) if (review != null)
                md.append('|').append(turn.round()).append('|').append(turn.validationCode()).append('|').append(cell(review.hypothesisId()))
                        .append('|').append(review.disposition()).append('|').append(cell(String.valueOf(review.assessedEvidenceIds())))
                        .append('|').append(cell(review.reason())).append("|\n");
        md.append("\n## 调查解释与未解决问题\n\n");
        md.append("\n### 独立语义审查（不确认根因）\n\n");
        for (var review : task.investigation.semanticReviews)
            md.append("- approved=").append(review.approved()).append("; findings=")
                    .append(cell(String.valueOf(review.findings()))).append("\n");
        for (var c : task.investigation.claims)
            md.append("- [").append(c.qualification()).append("] ").append(cell(c.text()))
                    .append(" 引用：").append(c.evidenceIds()).append("\n");
        for (String q : task.investigation.unresolvedQuestions) md.append("- 未解决：").append(cell(q)).append("\n");
        md.append("\n## 工具实际观察\n\n|证据 ID|工具|状态|结果|数据|\n|---|---|---|---|---|\n");
        for (var e : task.evidence)
            md.append('|').append(e.id()).append('|').append(e.tool()).append('|').append(e.result().status()).append('|')
                    .append(e.result().outcome()).append('|').append(cell(e.result().data().toString())).append("|\n");
        return md.append("\n完整提议、拒绝记录、假设版本、证据时间和事件见 trace.json。这是功能演示，不是模型收益或根因准确率评估。\n").toString();
    }

    private static String cell(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("|", "&#124;").replace("\r", " ").replace("\n", " ");
    }

    private String hash(String resource) throws Exception {
        try (var stream = getClass().getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("Missing resource");
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
        }
    }
}
