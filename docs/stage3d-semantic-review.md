# Stage 3D：诊断语义审查

2026-11-14 百炼失败案例：推送中心 received 被解释为 POS 收到，saveData 数据库连接失败被错误用来支持“没有响应”的旧假设。流程跑通不代表诊断正确。

## 实现

ContextBuilder 按服务端工具契约补充 sourceSystem、businessStage、limitations；不修改原始证据，不根据 received 猜阶段。日志阶段默认 UNKNOWN。
调查提示词 v13 要求分别表达事实、假设与缺口；命题变化时新增假设。
真实模型最终诊断使用独立提示词再调用百炼审查；检查来源、时序、因果和假设支持关系。审查结果持久化至 trace.investigation.semanticReviews。根因仍是 UNCONFIRMED。
审查消耗 decisions、请求预算和 token；预留一轮。未通过、超时或预算不足均不发布待审结论，保留业务事实并转人工。拒绝后最多允许一次修正，且至少剩余两轮预算才启动；修正后必须再次审查。预算不足保守结束，不自动增加 MaxRequests。
脚本模型测试不表示真实模型质量；同模型审查也可能漏错。

## 验收

```powershell
.\scripts\stage1.ps1 build
.\scripts\stage1.ps1 investigate -Provider qwen -Fixture meituan-db-failure -MaxRequests 6
```

检查 trace.semanticReviews（实际字段位于 investigation 下）、decisions、token 和停止原因；最终 claims 不得将 received 解释成 POS 已接收。拒绝错误结论属于防护成功，不等于成功查明原因。
对 meituan-db-failure、meituan-late-receipt、meituan-timeout、meituan-partial 分别重复运行，记录误归因、结论拒绝、预算耗尽、调用次数和 token。加入日志改写与未知异常后再判断泛化。尚未执行新版本真实百炼评测。

## 后续

本版补齐一次受限纠正及再审查；下一步补充更广的真实模型评测集及人工标注，再进入 Stage 4 订单 RAG 与可信案例记忆。未确认的诊断不得作为已验证根因进入案例库。

## 真实运行反馈修复

调查轮次 investigationRounds 与总请求 decisions 分离，审查不再造成提议轮次跳号。semanticCorrectionUsed 写入 checkpoint，恢复不会重置纠正次数。审查拒绝不应用提议；报告刷新为当前业务结论和历史因果缺口。提示词移除“无响应”与固定工具的示例诱导，审查区分来源混淆和因果过强。
原案例在 5 次调查后进入审查会耗尽 6 次预算，此时仍转人工；不能保证每次都有纠正机会。仍使用原命令 MaxRequests 6；真实模型效果需重新运行验证。
