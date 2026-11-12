# Stage 1B 真实模型接入验证记录

日期：2026-11-11，Asia/Shanghai。最新状态：余额问题解除，已收到真实模型决策，但两场景闭环尚未通过验收。累计请求 9/10，已停止真实调用；不代表生产就绪或模型效果达标。

## 初次接入结果

用户授权使用 DeepSeek 官方服务，本轮最多 10 次请求。实际发送 2 次：

1. `GET https://api.deepseek.com/models` 返回 HTTP 200，可用模型为 `deepseek-flash` 与 `deepseek-v4-pro`。因此将本轮使用的模型 ID 确定为 `deepseek-flash`，而非服务商名称 `deepseek`。
2. Java 项目发送首次 `POST /chat/completions`，返回 HTTP 402，记录为 `MODEL_HTTP_402`。没有取得模型决策，未调用任何业务工具。

按照[DeepSeek 官方错误码说明](https://api-docs.deepseek.com/quick_start/error_codes/)，402 表示账户余额不足。未查询账户余额数值，未执行充值，没有继续发送请求。不能从这次失败评价模型的诊断正确率。

原计划验证 `synced` 与 `contradiction` 两个开发场景。仅运行了 synced 的固定 Workflow 和一次失败的模型尝试；contradiction 未运行。业务数据始终为 Stub，真实订单/POS/美团系统没有接入。

运行产物：`evaluation-output/live-probe/2026-11-11T03-28-05.554774Z/`，包括 report.md、results.json 和 traces。报告中的 0/1 反映端到端调用失败，不代表模型回答错误；本次没有成功的模型回答，Token 记账为 0 表示未收到 usage，不是对平台账单的断言。

## 已落实的接入保护

- 1 次模型列表请求后，Java 进程的模型请求硬上限设为 9 次，失败请求同样扣减，实际仅使用 1 次。
- 请求预算由进程内所有任务共享，通过并发原子计数在发送前预留；不自动重试。
- HTTP 错误或请求预算耗尽立即结束余下评估，保留已完成结果。Runtime 记录本地错误码，不记录上游响应体或凭据。
- DeepSeek 官方接口显式关闭 thinking，并使用 JSON 输出与本地契约校验。本轮尚无法验证成功响应的兼容性。
- 凭据只经内存与子进程标准输入传递，再注入子进程环境；没有写入代码、配置文件或应用日志。子进程已退出，持有凭据的会话变量引用已清除。
- 新增预算并发测试；构建及 33 项测试全部通过。未执行新的真实模型请求来重复验证代码。

## 历史恢复测试配置

初次失败后，需要用户确认可用余额并授权继续。用户随后已确认并要求继续，以下为当时的恢复额度，不是当前剩余额度。

若继续计入本轮总额度，最多还可发起 8 次请求；不要把重启进程后的默认 10 次当作剩余额度。示例：

```powershell
.\scripts\stage1.ps1 evaluate -Provider remote -Cases 'synced,contradiction' -MaxRequests 8
```

恢复评估已按这组参数执行。当前只剩 1 次额度，不能再次按 8 次启动。完成两场景也只能证明小样本闭环可用，仍不足以声称大模型优于 Workflow。

## 余额恢复后的实际测试

### 请求台账

| 批次 | 内容 | 实际请求数 | 累计 |
| --- | --- | ---: | ---: |
| 初次接入 | 模型列表查询与一次 HTTP 402 | 2 | 2 |
| 恢复测试 | Prompt v1，synced 与 contradiction | 5 | 7 |
| 定向复测 | Prompt v2，仅 synced | 1 | 8 |
| 响应诊断 | Prompt v2，直接检查公开 content 的格式 | 1 | 9 |

没有重试模型列表、读取账户余额或执行生产业务操作。最后剩余 1 次不足以完成一个至少两轮的闭环，因此未继续消耗。

### Prompt v1 结果

产物：`evaluation-output/live-probe/2026-11-11T03-31-42.640548300Z/`。

- synced：真实模型查了本地订单，在第二次决策触发 INVALID_DECISION，安全转人工。旧版在字段校验前没有保存不合法提议，不能据此确定具体哪个字段违规。运行记录收到 1260 个输入 Token、134 个输出 Token。
- contradiction：模型只做两次业务查询就结束为 REPUSH_RECOMMENDED，没有检索日志并复查 POS，不符合场景预期 ALREADY_SYNCED。该次循环正常收尾，但调查质量未达标；记录收到 2071 个输入 Token、234 个输出 Token。
- 同条件 Workflow 为 1/2，模型为 0/2。这是极小开发样本，不能外推整体能力，也不能挑选后续复测替换这次失败结果。

### Prompt v2 复测与格式诊断

产物：`evaluation-output/live-probe/2026-11-11T03-34-24.460771600Z/`。synced 第一轮即 MODEL_ERROR，没有完成工具查询。旧版错误码粒度不足，未保存此轮公开 content，无法确认它与后续诊断具有完全相同的原因。

随后用同一模型、Prompt v2 和初始上下文直接做一次格式诊断，返回 HTTP 200、finish_reason=stop，但 content 拼接了两个相同的 CALL_TOOL JSON 对象。诊断记录在 `evaluation-output/live-probe/structured-output-diagnostic.json`，只保存公开决策 content 与用量，不保存私有推理、请求凭据或认证头。此请求返回 759 个输入 Token、103 个输出 Token。

这条诊断证实服务可能返回违反单对象契约的内容，但不证明所有失败都由重复 JSON 引起。HTTP 200、JSON 输出模式和 stop 均不能替代服务端严格解析。本次无法计算完整账单成本：失败解析阶段未保留 usage；未收到 usage 不能按免费或零消耗解释。

## 根据测试完成的改进

1. 保留 Prompt v1，新建 `order-diagnosis-v2`，明确 CALL_TOOL 的 evidenceIds 必须为空、outcome 必须为 null；要求在补推/恢复建议前调查可能遗漏的日志证据。版本与 Prompt hash 已随新评估记录保存。
2. 解析成功的模型提议在字段校验前进入 planHistory，即使随后被拒绝也能复盘；这些提议不属于 Evidence，也不能直接执行。
3. Runtime 只透传本地白名单校验错误码，例如 INVALID_CALL、INVALID_FINISH、INVALID_EVIDENCE_REFERENCE，不记录可能含敏感内容的原始异常消息。
4. JSON 解码显式拒绝尾随对象、未知字段、空内容、未完成输出；分别产生安全错误码。不会截取首个对象、自动执行或静默回退 Stub。
5. 添加非法提议保留和拼接 JSON 回归测试。最终构建及 35 项测试全部通过；JAR 已重新打包。最后的解析错误码改进仅做本地回归，没有再次发送真实请求。

## 当前结论与下一步

已验证真实模型请求、结构化决策返回与部分受控工具调用。尚未验证两个场景全部正确、反证重规划、修复能力或生产接入。Prompt v2 对矛盾场景的改进也未完成真实复测。

下一次需单独确定请求额度，继续验证单对象输出可靠性与反证调查。可对原生 Tool Calling 做受限兼容性试验，与当前 JSON 决策协议比较，但不能在测试之前宣称原生调用必然消除该问题。历史失败轨迹需保留，开发集调优结果与独立保留集评估应分开。
