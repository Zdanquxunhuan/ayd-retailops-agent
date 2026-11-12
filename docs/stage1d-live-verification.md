# Stage 1D 真实模型验证

日期：2026-11-11。北京区百炼 qwen-flash，模拟业务数据。用户单独授权本轮最多 10 次请求，失败也计数；不混用此前额度。

## 第一次：Prompt v3，反证场景

实际请求 2 次，输入 5175 / 输出 372 Token。模型第一轮建立 HISTORICAL 假设并选择推送记录；第二轮继续选择日志，但漏掉了所有 reviews，同时试图把原假设的反驳条件从“接收端响应”改成“落库成功”。

Java 以 HYPOTHESIS_REVIEW_REQUIRED 拒绝第二轮，未执行日志查询、未覆盖旧假设。此轮调查未完成，不能计作模型成功。校验成功拦截了契约违规。

原始轨迹：`evaluation-output/stage1d-live/2026-11-11T15-14-17.776536400Z/`。

随后保留 v3，新增 Prompt v4，将复核要求和不可偷换命题的要求放在开头，增加后续轮次示例。没有放松 Java 校验或把模型输出自动改成合规内容。重新构建及 63 项测试通过，日志见 `.build-cache/stage1d-v4-build.log`。

## 第二次：Prompt v4，反证场景

3 次请求，输入 8903 / 输出 1282 Token。模型完成推送历史、日志查询，第二轮 MAINTAIN、第三轮 REVISE 的复核记录齐全。第三轮根据日志提出反驳旧假设，但未复查 POS 就输出 DIAGNOSIS_READY，被 POS_RECHECK_REQUIRED 拦截。整轮被拒绝，因此该轮假设更新也未提交。

结果：复核契约有改善，完整调查仍未通过。轨迹：`evaluation-output/stage1d-live/2026-11-11T15-16-11.982055500Z/`。

## 第三次：Prompt v4，历史缺失场景

1 次请求，输入 2678 / 输出 166 Token。模型选择推送历史查询，但引用不存在的 H1，hypothesisUpdates 为空。Java 以 INVALID_INVESTIGATION_QUERY 拦截，没有实际查询历史数据，不能判定模型对历史缺失的处理是否正确。

轨迹：`evaluation-output/stage1d-live/2026-11-11T15-17-54.644307500Z/`。

## 一次受控纠正

增加 validationFeedback，包含错误码、明确纠正要求和被拒绝提议；被拒绝的提议不是事实。每个任务最多一次额外纠正机会，继续受任务决策、时间及进程请求总额限制。仅处理 INVALID_INVESTIGATION_QUERY、HYPOTHESIS_REVIEW_REQUIRED、POS_RECHECK_REQUIRED；越权、伪造引用等其他错误直接停止。纠正前不会执行查询、覆盖假设或推进已复核证据位置。

新 Prompt v5 说明反馈的含义。新增“纠正成功”和“重复错误最多纠正一次”回归测试，65 项测试全部通过，见 `.build-cache/stage1d-v5-build.log`。

## 第四次：Prompt v5，历史缺失场景

1 次请求，输入 2757 / 输出 271 Token。模型未查历史记录即尝试结束，并在解释中说仍需查询历史；被 INSUFFICIENT_INVESTIGATION 拦截。此错误不在纠正白名单内，按设计直接停止。本轮未触发纠正，不能把离线纠正测试表述为真实模型已纠正成功。

轨迹：`evaluation-output/stage1d-live/2026-11-11T15-21-08.492770700Z/`。

## 本轮结论与额度

|运行|Prompt|请求数|完整调查|停止原因|
|---|---|---:|---|---|
|反证 1|v3|2|未通过|HYPOTHESIS_REVIEW_REQUIRED|
|反证 2|v4|3|未通过|POS_RECHECK_REQUIRED|
|历史缺失 1|v4|1|未通过|INVALID_INVESTIGATION_QUERY|
|历史缺失 2|v5|1|未通过|INSUFFICIENT_INVESTIGATION|

本轮授权 10 次，实际 **7 次**，剩余 **3 次未使用**。合计输入 **19513** / 输出 **2091** Token。各运行 manifest.json 记录实际请求数；本轮没有使用此前剩余额度。没有执行任何业务写操作，根因保持 UNCONFIRMED。

Stage 1D 本轮未达到真实模型完整调查验收。安全拦截有效，不等于模型调查有效；当前状态报告由 Java 生成，也不能计作模型完成原因调查。所有场景为开发可见模拟数据，这些运行不能证明生产质量或与其他模型的优劣。

下一步优先简化单轮输出：由服务端提供稳定假设定义及复核对象，模型专注提出增量修改、简短依据和下一步查询；继续保留原有事实与权限门槛。是否提升可靠性须重新测试，不能据本轮结果断言模型本身无法胜任。
