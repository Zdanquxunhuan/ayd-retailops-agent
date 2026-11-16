# OmniOps Agent

**开发主线：先完成美团订单调查与受控补推闭环，再开发库存。** 后续阶段、验收标准及下一项任务统一见 [开发路线与 Agent 能力设计](docs/design/04-agent-development-roadmap-v0.3.md)（2026-11-16 更新，沿用原文件路径）。

下一项开发 **Stage 4A：订单 SOP RAG**，随后是可信案例 Memory、自然语言入口与 Context 工程、Stage 1F 调查评测、Stage 5 审批恢复/补推、Stage 6 演示交付。当前 v15 过早结束等问题已登记至 Stage 1F；先推进新能力，阻塞缺陷仍随阶段处理。路线同时列出每项能力对应的简历证据，计划不代表已实现。

## 当前进度与文档入口

| 范围 | 已实现与待验收 |
| --- | --- |
| [Stage 2：State / Context / Checkpoint](docs/stage2-stateful-investigation.md) | 单机补问、暂停恢复、累计预算保留；新增真实模型补问质量仍待验收 |
| [Stage 3A：工具执行层](docs/stage3a-tool-execution.md) | 工具注册、对象范围校验、有界执行和错误语义 |
| [Stage 3B：日志事件](docs/stage3b-log-events.md) | 读取本地日志副本、关联事件与脱敏；[副本配置](docs/stage3b-log-snapshots.md) |
| [Stage 3B：JDBC 只读适配](docs/stage3b-jdbc-readonly.md) | 本地订单与 POS 适配代码及自动化测试已完成，真实库联调待完成 |
| [Stage 3C：美团模拟与模型调查](docs/stage3c-meituan-order-investigation.md) | 平台响应校验、模型选择美团/日志查询、补问恢复与 HTTP 回归；百炼真实效果仍待验收 |
| [Stage 3D：语义审查](docs/stage3d-semantic-review.md) | 来源语义、按需审查、受限纠正及报告分层已实现；当前 v15 真实运行仍有调查覆盖不足 |
| 下一步：Stage 4A～4C（订单） | SOP RAG → 可信案例 Memory → 自然语言入口与预算化 Context；详细开发批次见主路线第 10 节 |

美团继续模拟，尚未接入 SDK/AppKey；恢复、补推与库存同步尚未实现。JDBC 默认关闭，启用时需本地配置只读账号与门店映射。真实与模拟结果分别标记，模拟结果不能支撑真实修复执行。当前没有生产身份认证，也未完成最新 JDBC/日志组合的真实模型端到端验收。

[原分阶段设计](docs/design/02-phased-design-v0.2.md) 保留历史编号与协议细节；开发顺序以主路线为准。以下保留 Stage 1 的演示用法与历史评估记录，默认配置使用模拟业务数据；Stage 2 恢复及 Stage 3B 实际只读接入以对应文档为准。

## Stage 1 历史实现与验证

Stage 1E 记录：[增量契约](docs/stage1e-delta-contract.md)，Prompt v8，68 项测试通过。当轮 50 次额度使用 28 次。真实模型的未知处理与日志注入样本有改善，反证调查仍未完整通过。先看 [正常使用的例子](docs/stage1e-user-examples.md)，再看 [实测结果](docs/stage1e-live-verification.md)。这些历史记录不代表最新代码与 Prompt 的验收结果，也不构成新增调用授权。

此前阶段：[Stage 1D 假设修正](docs/stage1d-hypothesis-review.md) 与其 [实测记录](docs/stage1d-live-verification.md)。当前实现继续保留时间范围、逐项复核和一次受预算约束的契约纠正。

基于 V0.2 实现的 Stage 1 订单只读诊断原型。Java 17、Spring Boot 4.1.2。支持任务提交、补充对象、动态工具选择、证据落盘、受控结论、取消和 Workflow 对照评估。

**以下 Stage 1 默认演示使用 Stub 业务数据；默认 agent 决策器也是规则 Stub，不是大模型。** 百炼 qwen-flash 已跑通状态诊断与 Stage 1C 多轮调查调用链；实测也暴露了模型假设与最新事实不一致的问题，原因解释仍需审阅。该阶段未接入生产订单、POS 或美团接口，也没有任何写工具。该阶段结果见 `docs/stage1c-live-verification.md`；此前记录见 `docs/stage1b-qwen-verification.md` 和 `docs/stage1b-live-probe.md`。

新增 **Stage 1C：大模型异常调查闭环**。使用 `strategy=investigation`，Java 采集基础状态，大模型提出假设、选择只读查询、读取日志、根据反证更新假设。每轮提议、校验结果、假设版本和实际观察分别保留。此路径不会使用规则 Stub 冒充调查模型。运行与当前验证范围见 [Stage 1C 使用说明](docs/stage1c-investigation.md)。

## 快速运行

在项目根目录打开 PowerShell。脚本默认使用本机 `F:\ayd\jdk-17`，仅修改当前进程的 JAVA_HOME，不修改系统环境。

```powershell
.\scripts\stage1.ps1 test
.\scripts\stage1.ps1 build
.\scripts\stage1.ps1 run
```

其他 JDK 位置可传 `-JdkHome 'C:\path\to\jdk-17'`。依赖由 Maven Wrapper 下载到项目 `.build-cache/repository`，需要网络。服务只监听 `127.0.0.1:8080`。

另开 PowerShell 提交一个任务：

```powershell
$body = @{
    userGoal = '请调查这个订单为何没有同步到 POS'
    storeId = 'A001'
    orderId = 'O100'
    fixtureId = 'contradiction'
    strategy = 'agent'
} | ConvertTo-Json
$task = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8080/api/tasks' `
    -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
$task.id
Invoke-RestMethod "http://127.0.0.1:8080/api/tasks/$($task.id)"
Invoke-RestMethod "http://127.0.0.1:8080/api/tasks/$($task.id)/events?afterSequence=0"
Invoke-RestMethod "http://127.0.0.1:8080/api/tasks/$($task.id)/report"
```

POST 返回 202，报告生成前 GET report 返回 204。`contradiction` 的规则 Stub 轨迹为 POS→本地→日志→重新查询 POS；根据日志回执线索重新验证后，结论变为 ALREADY_SYNCED。这个分支是预编程的演示，不能当作模型理解未知日志的证明。

`GET /api/fixtures` 查看可选场景；`strategy=workflow` 使用固定顺序对照。fixtureId 仅用于本地模拟，不是将来的生产业务参数。在默认模拟配置下，更换 orderId 只改变模拟查询上下文，不会访问真实订单；样例请使用 O100。启用 JDBC 后应按 Stage 3B 文档传入真实规范订单号，不使用此样例。

缺少 storeId 或 orderId 时进入 CLARIFYING。通过 `POST /api/tasks/{id}/clarifications` 提交 `storeId、orderId、expectedVersion`，其中版本从 GET task 获取。版本冲突返回 409。`POST /api/tasks/{id}/cancel` 请求取消；若模型调用中，最多等待该轮有界调用退出，查询任务确认最终状态。

## 对照评估

```powershell
.\scripts\stage1.ps1 evaluate
```

生成 `evaluation-output/<UTC时间>/report.md`、`results.json` 和每次任务的 `traces/*.json`。应用运行一次评估后自动退出。评估使用独立任务目录，可与本地 API 同时运行。

10 个开发场景包括已同步、补推建议、恢复建议、窗口外、POS 超时、平台超时、不完整结果、平台空结果、矛盾证据和日志注入。两种策略使用相同工具、证据时效、预算与报告安全门槛；每个任务重置工具响应序列。

报告记录逐例预期/实际结果、停止原因、调用数、耗时、Token、模型/Prompt/Schema/数据集版本和完整轨迹。失败或被安全校验拒绝，即使回退为预期 UNRESOLVED，也不计为正确诊断。金额成本不编造。

当前数据为开发回归集，所有案例对开发者可见；不是独立保留集。固定 Workflow v1 不根据日志再次查询，基线较弱。不能将离线 Stub 得分差异写成“LLM 准确率提升”。真实收益评估还需更强 Workflow、独立保留集、真实脱敏样本、人工复核和多次模型运行。

## 使用已配置的百炼模型

后续开发继续使用 `-Provider qwen`，默认模型为仓库配置中的 `qwen-flash`，可通过 `DASHSCOPE_MODEL` 覆盖。凭据读取 `DASHSCOPE_API_KEY`，不回退 DeepSeek；已有本地环境配置可继续使用，不需要把密钥发到聊天或写进仓库。默认 provider 仍为 stub，避免未显式选择模型时产生请求。

```powershell
.\scripts\stage1.ps1 build
# 单例调查与报告导出，会调用百炼；按本次预算设置请求上限。
.\scripts\stage1.ps1 investigate -Provider qwen -Fixture meituan-db-failure -MaxRequests 6
# 需要交互补问时使用服务模式，与上述一次性演示二选一。
.\scripts\stage1.ps1 run -Provider qwen -MaxRequests 6
```

本例默认使用模拟订单，不能用于证明真实美团或 POS 接入。当前调查 Prompt 为 v15，最新用户运行仍出现过早结束并被 `INSUFFICIENT_INVESTIGATION` 拦截；这是待处理的质量问题。下一项开发先进入订单 RAG，问题归入主路线 Stage 1F，不要求反复运行同一个案例才能推进。

请求上限按**进程累计**，包含失败请求；重启重新计数，任务恢复时决策预算仍累计。它不是账户级费用限制，也不代表可以因重启而超出原定测试总预算。程序不自动增加额度，模型失败不切回 Stub 伪装成功。

默认地址为 `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`。工作空间端点通过已有 `DASHSCOPE_ENDPOINT` 配置，具体允许的北京区域地址见 [Stage 3C](docs/stage3c-meituan-order-investigation.md)。这些是当前代码配置，不保证所有账户都支持相同模型或端点；本文不维护实时价格。

`evaluate` 是早期状态对照评估，`investigate` 是单次调查导出，两者不是同一项验收。批量调查评估、有/无 RAG 与 Memory 的对照属于后续计划。历史 DeepSeek/百炼试验记录保留在 [Stage 1B](docs/stage1b-live-probe.md)、[百炼验证](docs/stage1b-qwen-verification.md)及 Stage 1C～1E 文档，不作为当前版本的效果结论。

模型可见用户问题、业务对象、允许工具与脱敏证据；看不到 fixtureId、标准答案和评分。当前接口仍要求显式门店/订单号，自然语言实体识别待 Stage 4C 实现。模型解释属于假设，当前业务状态由 Java 校验后生成。

## Stage 1 默认演示边界与后续变化

- 网关注册五个查询工具，新增 `order.push.history`（模拟推送历史）；每次调用检查门店范围、订单目标和白名单。默认范围为 A001。
- 结果区分 FOUND、ABSENT、TIMEOUT、PARTIAL 等；查询失败无法支持缺单结论，最新反证和过期证据不能被忽略。
- 默认上限为 8 次决策、12 次工具调用、120 秒活动时间。相同工具最多三次，相同结果连续出现后停止无进展循环。
- JSON 原子快照保存任务、计划、证据和事件，文件锁限制一个进程使用同一目录。旧 agent/workflow 路径重启后转人工；Stage 2 的 investigation 路径支持 WAITING_USER/PAUSED 后显式恢复，详见对应文档。
- 这是本地单用户原型：没有生产身份认证、跨用户隔离、分布式 Worker、关系库迁移和请求幂等。调查路径已支持假设版本及引用校验，尚无根因自动确认器和人工复核状态机。绑定非回环地址及 production/integration profile 会拒绝启动。
- 只报告当前状态和处理建议，历史根因始终标为未确认。真实接口返回的订单完整性、多键唯一性及五天边界尚未接入，本版本的窗口外场景只是接口检查结果 Stub。
- 不记录 API 密钥和原始模型错误响应；本地任务目录仍需按实际数据敏感性保护。默认演示使用模拟内容；实际日志与 JDBC 数据联调需按 Stage 3B 文档限定字段与范围，向外部模型发送前复核脱敏结果。

文件数据库、规则 Stub 与本地身份是假设明确的阶段实现，不能宣称已达到完整生产准入标准。真实只读联调、身份源和生产门禁仍有缺口；Stage 3C 已补充适配与回归，下一项按主路线进入订单 RAG/Memory；真实模型验收继续单列。

## 代码导航

- `domain/Diagnosis.java`：任务、工具、决策、证据、报告契约。
- `runtime/DiagnosisRunner.java`：调查循环、预算、停止条件。
- `runtime/ReportPolicy.java`：可证实结论的确定性门槛。
- `tool/ReadOnlyGateway.java`：白名单与对象权限；FixtureCatalog 提供模拟数据。
- `adapter/JsonModelPlanner.java`：真实模型 JSON 决策适配器。
- `application/TaskService.java`、`api/TaskController.java`：异步任务与 API。
- `persistence/FileTaskRepository.java`：单机原子快照。
- `evaluation/EvaluationRunner.java`：同条件对照、逐例评分与轨迹导出。
- `docs/design/04-agent-development-roadmap-v0.3.md`：当前执行路线、进度、订单验收标准与库存延后范围；其他设计文档保留历史协议供参考。

Stage 3D 诊断语义审查与验证边界：[说明](docs/stage3d-semantic-review.md)。
