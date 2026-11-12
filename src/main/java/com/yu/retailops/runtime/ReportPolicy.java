package com.yu.retailops.runtime;

import com.yu.retailops.domain.Diagnosis.*;

import java.time.*;
import java.util.*;

import static com.yu.retailops.tool.ReadOnlyGateway.*;

/**
 * Deterministic safety gate shared by Agent and Workflow; never claims a historical root cause.
 */
public class ReportPolicy {
    private final Clock clock;

    public ReportPolicy(Clock clock) {
        this.clock = clock;
    }

    public static Evidence latest(List<Evidence> evidence, String tool) {
        return evidence.stream().filter(e -> e.tool().equals(tool)).reduce((a, b) -> b).orElse(null);
    }

    private boolean is(List<Evidence> all, String tool, ResultStatus status, String outcome) {
        var e = latest(all, tool);
        return e != null && e.validUntil().isAfter(clock.instant()) && e.result().complete()
                && e.result().status() == status && outcome.equals(e.result().outcome());
    }

    public Outcome supported(List<Evidence> all) {
        if (is(all, POS, ResultStatus.SUCCESS, "FOUND")) return Outcome.ALREADY_SYNCED;
        if (!is(all, POS, ResultStatus.NOT_FOUND, "ABSENT")) return Outcome.UNRESOLVED;
        if (is(all, LOCAL, ResultStatus.SUCCESS, "FOUND")) return Outcome.REPUSH_RECOMMENDED;
        if (!is(all, LOCAL, ResultStatus.NOT_FOUND, "ABSENT")) return Outcome.UNRESOLVED;
        if (is(all, PLATFORM, ResultStatus.SUCCESS, "FOUND")) return Outcome.RESTORE_RECOMMENDED;
        if (is(all, PLATFORM, ResultStatus.SUCCESS, "OUTSIDE_WINDOW")) return Outcome.OUTSIDE_SEARCH_WINDOW;
        return Outcome.UNRESOLVED;
    }

    public Report validate(Decision d, List<Evidence> all) {
        if (d.outcome() == null || d.evidenceIds() == null) throw new IllegalArgumentException("INVALID_REPORT");
        Set<String> ids = new HashSet<>(d.evidenceIds());
        if (ids.size() != d.evidenceIds().size() || all.stream().filter(e -> ids.contains(e.id())).count() != ids.size())
            throw new IllegalArgumentException("INVALID_EVIDENCE_REFERENCE");
        var cited = all.stream().filter(e -> ids.contains(e.id())).toList();
        if (d.outcome() != Outcome.UNRESOLVED
                && (supported(cited) != d.outcome() || supported(all) != d.outcome()))
            throw new IllegalArgumentException("UNSUPPORTED_CONCLUSION");
        // Require the latest observations used by a positive conclusion, not selected stale predecessors.
        if (d.outcome() != Outcome.UNRESOLVED) {
            for (String tool : List.of(LOCAL, POS, PLATFORM)) {
                Evidence latest = latest(all, tool);
                if (latest != null && !ids.contains(latest.id()))
                    throw new IllegalArgumentException("LATEST_EVIDENCE_REQUIRED");
            }
        }
        String summary = switch (d.outcome()) {
            case ALREADY_SYNCED -> "POS 查询确认订单已存在，无需补推。";
            case REPUSH_RECOMMENDED -> "本地订单存在且 POS 明确不存在，可提交补推提议；本任务未执行写操作。";
            case RESTORE_RECOMMENDED -> "本地及 POS 明确缺单，平台返回匹配订单，可提交恢复提议；仍需真实接口完整性校验。";
            case OUTSIDE_SEARCH_WINDOW -> "本地及 POS 缺单，平台查询窗口检查不满足，需人工处理。";
            case UNRESOLVED -> "当前证据不足以形成可靠处理结论，需要继续调查或人工处理。";
        };
        return new Report(d.outcome(), summary, List.copyOf(ids), "UNCONFIRMED",
                List.of("业务数据全部为 Stub，不能用于处理真实订单。", "当前状态不证明历史根因；未执行任何修复。"),
                "OBSERVE_ONLY", "STUB");
    }
}
