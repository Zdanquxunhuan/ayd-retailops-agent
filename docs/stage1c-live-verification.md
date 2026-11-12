# Stage 1C 真实模型验证记录

日期：2026-11-11。模型：北京区百炼 qwen-flash。业务数据：SIMULATION/STUB。用户单独授权本轮最多 10 次请求，失败也计数；此前百炼剩余 1 次额度没有使用。

## 首轮：发现过早结束

Prompt order-investigation-v1，3 次请求，输入 6297 / 输出 1328 Token。

模型自主选择推送记录、关联日志，提出两个假设，并将其中一个假设根据日志标为 CONTRADICTED。但没有复查 POS 就结束，Java 只能基于旧观察给出 REPUSH_RECOMMENDED，未达到该场景的预期。接口返回 INVESTIGATION_READY 不等于场景通过。

原始记录：`evaluation-output/investigation-live/2026-11-11T08-20-22.828695400Z/`。保留失败轨迹，不改写为成功。

修正：Prompt v2 明确处理建议不能作为原因成立的证据；Java 增加结束门槛：缺失/未知的 POS 观察之后又读取日志或推送记录，必须复查 POS 才可 DIAGNOSIS_READY。该门槛是确定性业务保护，不属于模型独有能力。

## 第二轮：多轮链路完成，语义质量仍有限

Prompt order-investigation-v2，4 次请求，输入 9508 / 输出 1597 Token。

实际顺序：Java 查 POS 和本地 → 模型选择推送记录 → 模型选择日志 → 模型提出 POS 复查 → 模型输出调查结果。共 5 次工具调用。最新 POS 为 FOUND，Java 输出 ALREADY_SYNCED，未执行补推。

模型保留了假设 H1 的历史并将其更新为 CONTRADICTED，完整证据和假设版本均已落盘。但 H2“推送已成功发出但接收端未完成持久化落库”仍被模型标为 SUPPORTED，未正确吸收最新 POS 的反证。这说明引用存在、结构合法和调查完成不能证明因果解释正确。

本轮仅判为**执行链路完成、当前状态正确，假设语义质量未完全通过**。根因仍为 UNCONFIRMED，不应将此结果描述为“根因诊断准确”或“模型必要性已证明”。

原始记录：`evaluation-output/investigation-live/2026-11-11T14-52-17.153374300Z/`，包括 report.md、trace.json 和包含真实请求数及资源哈希的 manifest.json。

## 第三轮：历史缺失处理未通过

Prompt v2，3 次请求，输入 6445 / 输出 968 Token。模型读取推送历史和日志，虽然文字中承认无法确认历史原因，却将“曾发生失败”的假设用空记录标为 CONTRADICTED，错误地把缺失证据当作反证。本场景未通过。

原始记录：`evaluation-output/investigation-live/2026-11-11T14-53-35.626031Z/`。

实测后补充 Java 校验：推送记录 `records=[]`、日志 `lines=[]` 不能用于 SUPPORTED/CONTRADICTED 或有证据的原因结论；非法更新停止为 NEEDS_HUMAN，保留之前的假设和当前状态。新增回归测试验证这条保护。此修正完成了本地验证，因额度已耗尽，没有再调用真实模型复测。

本轮累计请求 **10/10**，输入 **22250** / 输出 **3893** Token，未自动重试，未使用此前剩余额度。不将 Token 数换算成未经核对的实际账单。

## 本地工程验证

JDK 17 构建成功，54 项测试全部通过，记录见 `.build-cache/investigation-build.log`。覆盖假设修正与持久化、非法引用、反证丢失、空历史证据、越权写入、模型失败降级、预算/取消、结束前 POS 复查门槛及 API。修复了被中断的工作线程无法使用 FileChannel 保存最终快照的问题，保留同步落盘和原子替换。

零模型 CLI 实测：`synced + CHECK_STATUS`，1 次 POS 工具查询、0 次模型调用，STATUS_CHECK_READY。

后续质量改进方向是让假设明确指向时间范围、目标及可验证命题，并检查每次关键观察后哪些假设尚未复核。不能仅增加引用检查就宣称自然语言因果判断已可靠。
