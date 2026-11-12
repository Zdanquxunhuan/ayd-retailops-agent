# OmniOps Agent

当前进展：[Stage 1E 增量契约](docs/stage1e-delta-contract.md)，Prompt v8，68 项测试通过。本轮 50 次额度已使用 28 次。真实模型的未知处理与日志注入样本有改善，反证调查仍未完整通过。先看 [正常使用的例子](docs/stage1e-user-examples.md)，再看 [实测结果](docs/stage1e-live-verification.md)。

此前阶段：[Stage 1D 假设修正](docs/stage1d-hypothesis-review.md) 与其 [实测记录](docs/stage1d-live-verification.md)。当前实现继续保留时间范围、逐项复核和一次受预算约束的契约纠正。

基于 V0.2 实现的 Stage 1 订单只读诊断原型。Java 17、Spring Boot 4.1.2。支持任务提交、补充对象、动态工具选择、证据落盘、受控结论、取消和 Workflow 对照评估。

**当前业务工具全部为 Stub。默认决策器也是规则 Stub，不是大模型。** 百炼 qwen-flash 已跑通状态诊断与 Stage 1C 多轮调查调用链；实测也暴露了模型假设与最新事实不一致的问题，原因解释仍需审阅。没有接入生产订单、POS 或美团接口，也没有任何写工具。最新结果见 `docs/stage1c-live-verification.md`；此前记录见 `docs/stage1b-qwen-verification.md` 和 `docs/stage1b-live-probe.md`。

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

`GET /api/fixtures` 查看可选场景；`strategy=workflow` 使用固定顺序对照。fixtureId 仅用于本地模拟，不是将来的生产业务参数。更换 orderId 只改变模拟查询上下文，不会访问真实订单；样例请使用 O100。

缺少 storeId 或 orderId 时进入 CLARIFYING。通过 `POST /api/tasks/{id}/clarifications` 提交 `storeId、orderId、expectedVersion`，其中版本从 GET task 获取。版本冲突返回 409。`POST /api/tasks/{id}/cancel` 请求取消；若模型调用中，最多等待该轮有界调用退出，查询任务确认最终状态。

## 对照评估

```powershell
.\scripts\stage1.ps1 evaluate
```

生成 `evaluation-output/<UTC时间>/report.md`、`results.json` 和每次任务的 `traces/*.json`。应用运行一次评估后自动退出。评估使用独立任务目录，可与本地 API 同时运行。

10 个开发场景包括已同步、补推建议、恢复建议、窗口外、POS 超时、平台超时、不完整结果、平台空结果、矛盾证据和日志注入。两种策略使用相同工具、证据时效、预算与报告安全门槛；每个任务重置工具响应序列。

报告记录逐例预期/实际结果、停止原因、调用数、耗时、Token、模型/Prompt/Schema/数据集版本和完整轨迹。失败或被安全校验拒绝，即使回退为预期 UNRESOLVED，也不计为正确诊断。金额成本不编造。

当前数据为开发回归集，所有案例对开发者可见；不是独立保留集。固定 Workflow v1 不根据日志再次查询，基线较弱。不能将离线 Stub 得分差异写成“LLM 准确率提升”。真实收益评估还需更强 Workflow、独立保留集、真实脱敏样本、人工复核和多次模型运行。

## 接入真实模型

业务数据仍是 Stub，仅将决策器替换成真实模型。先在当前终端通过环境变量设置凭据，避免写入仓库或命令历史中的字面密钥：

```powershell
# OMNIOPS_MODEL_API_KEY 通过本地安全方式注入环境；不要提交到代码仓库。
$env:OMNIOPS_MODEL_NAME = '填写账户实际可用并支持 JSON 输出的模型名称'
.\scripts\stage1.ps1 run -Provider remote
# 或执行多次对照评估（会产生实际 API 调用费用）
.\scripts\stage1.ps1 evaluate -Provider remote -Repeats 3
```

真实模型请求默认按**进程累计最多 10 次**，包含失败请求，不会自动重试。该限制独立于单任务决策预算；重启进程会重新计数，不是账户级累计额度。可用 `-MaxRequests` 显式设置本次进程上限。首次验证可只选两个场景：

```powershell
$env:OMNIOPS_MODEL_NAME = 'deepseek-flash'
.\scripts\stage1.ps1 evaluate -Provider remote -Cases 'synced,contradiction' -MaxRequests 9
```

这里的 9 次适用于此前已用 1 次请求查询官方模型列表、总共允许 10 次请求的情形。重复三轮完整评估会受默认 10 次总额度限制；扩大额度前先确认预算。HTTP 失败或请求额度耗尽会中止余下评估，已完成的案例仍生成报告。错误记录只包含本地错误码，不保存上游响应体或密钥。

DeepSeek 官方接口于 2026-11-11 返回 `deepseek-flash`、`deepseek-v4-pro`；`deepseek` 是服务商名称，不是本次可用模型 ID。当前适配器对官方 DeepSeek 显式关闭 thinking，以便在本轮有限输出预算内取得 JSON 决策；该设置不是所有服务商通用参数。[官方模型与调用说明](https://api-docs.deepseek.com/)

默认请求 `https://api.deepseek.com/chat/completions`；配置项 `omniops.model.endpoint` 可调整为支持该协议的 HTTPS 接口。发送 `response_format=json_object`，本地再做严格字段解析、工具授权和结论验证，不把 JSON 可解析等同于决策安全。HTTP 错误、截断、无效输出与超时安全停止，不自动切回 Stub 伪装成功。[DeepSeek JSON 输出文档](https://api-docs.deepseek.com/guides/json_mode/)

模型可见用户问题、明确业务对象、允许工具与本次证据；看不到 fixtureId、标准答案或评分。模型决策摘要保存在 planHistory，仅供审阅，不作为事实；最终业务报告由 Java 根据证据生成。当前没有自然语言实体抽取器，实体缺失由 API 明确补齐。

## 更低成本的百炼候选

新增 `-Provider qwen` 预设，使用北京区 `qwen-flash`，单独读取 `DASHSCOPE_API_KEY`，不会回退使用 `OMNIOPS_MODEL_API_KEY`。默认官方兼容地址为 `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`；若控制台给出工作空间地址，可以通过 `DASHSCOPE_ENDPOINT` 指定 `https://实际工作空间ID.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions`。本预设只允许北京区官方域名。

本地安全设置 `DASHSCOPE_API_KEY` 后，用已确认的调用额度启动。例如下一轮另行确认最多 10 次时：

```powershell
.\scripts\stage1.ps1 evaluate -Provider qwen -Cases 'synced,contradiction' -MaxRequests 10
```

此前 DeepSeek 试验累计使用 9/10 次；后续单独授权的百炼 10 次额度也已使用 9 次，不能通过重启进程重置用户授权额度。百炼真实调用已经执行，Prompt v3 的“已同步”场景通过；新 Prompt 下的反证调查尚未复测。此示例命令仅供下一轮另行确认额度后使用，不自动授权新增调用。

2026-11-11 核对的北京区原价：单请求输入不超过 128K Token 时，普通输入 ¥0.15/百万 Token，输出 ¥1.5/百万 Token。作为计算示例，整个任务累计输入 10,000、输出 1,000 Token，且各请求都在这一档，费用约 ¥0.003；这是预算估算，非实测账单，缓存和其他优惠另算。[百炼模型与价格](https://help.aliyun.com/zh/model-studio/qwen-flash)

百炼调用关闭 thinking，使用相同的严格 JSON 决策校验、证据门槛及进程请求总上限。模型变更后需重新评估，不承诺便宜模型能修复之前的调查或响应格式问题。[官方兼容接口](https://help.aliyun.com/en/model-studio/qwen-api-via-openai-chat-completions)

## 工程边界与限制

- 网关注册五个查询工具，新增 `order.push.history`（模拟推送历史）；每次调用检查门店范围、订单目标和白名单。默认范围为 A001。
- 结果区分 FOUND、ABSENT、TIMEOUT、PARTIAL 等；查询失败无法支持缺单结论，最新反证和过期证据不能被忽略。
- 默认上限为 8 次决策、12 次工具调用、120 秒活动时间。相同工具最多三次，相同结果连续出现后停止无进展循环。
- JSON 原子快照保存任务、计划、证据和事件，文件锁限制一个进程使用同一目录。重启将未结束调查安全转人工，保留证据；不是自动断点续跑。
- 这是本地单用户原型：没有生产身份认证、跨用户隔离、分布式 Worker、关系库迁移和请求幂等。调查路径已支持假设版本及引用校验，尚无根因自动确认器和人工复核状态机。绑定非回环地址及 production/integration profile 会拒绝启动。
- 只报告当前状态和处理建议，历史根因始终标为未确认。真实接口返回的订单完整性、多键唯一性及五天边界尚未接入，本版本的窗口外场景只是接口检查结果 Stub。
- 不记录 API 密钥和原始模型错误响应；本地任务目录仍需按实际数据敏感性保护。本版本请只使用模拟内容。

文件数据库、规则 Stub 与本地身份是假设明确的阶段实现，不能宣称已达到完整生产 Stage 1 准入标准。下一步用真实只读适配器与真实模型跑相同契约测试，再替换元数据存储、接入身份源并补齐生产门禁。

## 代码导航

- `domain/Diagnosis.java`：任务、工具、决策、证据、报告契约。
- `runtime/DiagnosisRunner.java`：调查循环、预算、停止条件。
- `runtime/ReportPolicy.java`：可证实结论的确定性门槛。
- `tool/ReadOnlyGateway.java`：白名单与对象权限；FixtureCatalog 提供模拟数据。
- `adapter/JsonModelPlanner.java`：真实模型 JSON 决策适配器。
- `application/TaskService.java`、`api/TaskController.java`：异步任务与 API。
- `persistence/FileTaskRepository.java`：单机原子快照。
- `evaluation/EvaluationRunner.java`：同条件对照、逐例评分与轨迹导出。
- `docs/design/`：V0.2 架构评审与阶段设计；原设计描述目标，本 README 描述已实现范围。
