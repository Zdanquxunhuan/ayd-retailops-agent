package com.yu.retailops.runtime;

import com.yu.retailops.domain.Diagnosis.*;

import java.util.*;

import static com.yu.retailops.tool.ReadOnlyGateway.*;
import static com.yu.retailops.runtime.ReportPolicy.latest;

/**
 * Deterministic demonstration planner, explicitly NOT an LLM or evidence of AI effectiveness.
 */
public class OfflinePlanner implements Planner {
    private final ReportPolicy policy;
    private final boolean workflow;

    public OfflinePlanner(ReportPolicy policy, boolean workflow) {
        this.policy = policy;
        this.workflow = workflow;
    }

    public ModelReply next(Context c) {
        var evidence = c.evidence();
        String next = null;
        if (workflow) {
            // Fixed investigative sequence, then the same report gate as the adaptive demonstration.
            for (String tool : List.of(LOCAL, POS, LOGS))
                if (latest(evidence, tool) == null) {
                    next = tool;
                    break;
                }
        } else {
            if (latest(evidence, POS) == null) next = POS;
            else if (policy.supported(evidence) == Outcome.ALREADY_SYNCED) return finish(c);
            else if (latest(evidence, LOCAL) == null) next = LOCAL;
        }
        if (next == null) {
            Evidence local = latest(evidence, LOCAL);
            if (local != null && "ABSENT".equals(local.result().outcome()) && latest(evidence, PLATFORM) == null)
                next = PLATFORM;
            else if (latest(evidence, LOGS) == null) next = LOGS;
            else if (!workflow && evidence.stream().filter(e -> e.tool().equals(POS)).count() < 2
                    && String.valueOf(latest(evidence, LOGS).result().data().getOrDefault("message", "")).contains("POS_RECEIPT"))
                next = POS;
        }
        return next == null ? finish(c) : new ModelReply(Decision.call(next, c.input(), "按已观测结果选择下一项只读调查。"), 0, 0);
    }

    private ModelReply finish(Context c) {
        return new ModelReply(Decision.finish(policy.supported(c.evidence()), c.evidence(), "依据实时工具结果提出结论；历史根因未确认。"), 0, 0);
    }

    public String provider() {
        return workflow ? "JAVA_WORKFLOW" : "RULE_STUB_NOT_LLM";
    }

    public String modelVersion() {
        return workflow ? "fixed-workflow-v1" : "adaptive-demo-v1";
    }
}
