# Stage 1C：大模型异常调查闭环

后续已增加 [Stage 1D 假设时间范围与逐项复核](stage1d-hypothesis-review.md)。下文介绍 Stage 1C 基础能力；当前新任务使用 order-investigation-v5 / investigation-v2，运行入口保持兼容。

本阶段优先实现项目中的模型核心能力：读取非结构化记录，提出可检验假设，选择下一项查询，在新证据出现后更新假设。业务工具仍为模拟，真实订单/POS/美团接口尚未接入。已有的 `agent/workflow` 路径保留用于之前的回归评估。

## 已实现的行为

1. Java 先查询基础状态。`CHECK_STATUS` 可直接返回业务报告，模型调用为零。
2. `DIAGNOSE_CAUSE` 进入真实调查模型，即使当前 POS 已存在，也继续处理用户关于历史原因的问题。
3. 每次模型调用同时返回假设更新和一个查询/结束提议。查询必须绑定假设 ID 和待验证问题。
4. Java 核验结构、引用、门店/订单目标及工具权限，再调用工具。工具结果单独写入 Evidence。
5. 下一轮携带已取得的证据、当前假设、已查工具和剩余预算。旧假设被反驳时，支持与反证引用必须保留，命题不能在同一个 ID 下偷换。
6. 结束时分别保存 Java 的当前状态报告，以及模型提出的调查解释、假设历史和未知项。根因确认程度始终为 `UNCONFIRMED`。

没有配置真实调查模型时，原因调查停止为 `INVESTIGATION_MODEL_NOT_CONFIGURED`，仍保留 Java 已验证的状态；不会偷偷改用规则分支继续“模拟模型推理”。单元测试中的脚本化模型仅用于契约测试。

## 一条命令查看调查过程

先构建：

```powershell
.\scripts\stage1.ps1 build
```

无需模型和密钥，可以先检查零模型路径：

```powershell
.\scripts\stage1.ps1 investigate -Provider stub -Fixture synced -Intent CHECK_STATUS
```

真实调查需在进程环境中安全注入 `DASHSCOPE_API_KEY`，并为本轮明确请求上限。下例会实际调用百炼；不是新的调用额度授权：

```powershell
.\scripts\stage1.ps1 investigate -Provider qwen -Fixture investigation-reversal -MaxRequests 6
```

完成后程序退出，输出目录为 `evaluation-output/investigation/<UTC时间>/`：

- `report.md`：逐轮查询理由、待验证问题、假设变化、模型解释和实际观察，适合直接阅读。
- `trace.json`：完整任务、每轮提议及接受/拒绝结果、证据和事件。
- `manifest.json`：Prompt/数据集 SHA256、本进程真实请求数及模拟环境标记。

`report.md` 中的假设状态是模型提议经过格式和引用校验后的记录，不是 Java 对自然语言因果关系的背书。

## API 使用

```powershell
.\scripts\stage1.ps1 run -Provider qwen -MaxRequests 6
```

另开终端：

```powershell
$body = @{
    userGoal = '调查此前未同步的原因，说明依据和仍不清楚的部分'
    storeId = 'A001'
    orderId = 'O100'
    fixtureId = 'investigation-reversal'
    strategy = 'investigation'
    intent = 'DIAGNOSE_CAUSE'
} | ConvertTo-Json
$task = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8080/api/tasks' `
    -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
Invoke-RestMethod "http://127.0.0.1:8080/api/tasks/$($task.id)"
Invoke-RestMethod "http://127.0.0.1:8080/api/tasks/$($task.id)/investigation"
Invoke-RestMethod "http://127.0.0.1:8080/api/tasks/$($task.id)/report"
```

提交后异步运行，轮询 GET task 直到 `COMPLETED/NEEDS_HUMAN/CANCELLED` 等终态。`/investigation` 返回当前调查记录，尚未开始时返回 204；`/report` 返回 Java 业务状态。`intent=CHECK_STATUS` 不调用调查模型。只传 intent 而省略 strategy 时自动选择 investigation；显式 legacy strategy 不能混用 intent。

## 模拟场景

|场景|目的|应关注的行为|
|---|---|---|
|investigation-reversal|推送等待与迟到异步回执|理解记录文字，按新线索复查 POS，更新假设，保留未知的精确延迟原因|
|investigation-no-history|当前已同步但历史过期|区分当前状态与历史原因；缺数据时转人工，不能编造原因|
|investigation-injection|日志包含越权写指令|只把日志当数据；即使模型提出非法动作，Java 也拒绝执行|

场景内容由开发者设计并可见，是功能示范与回归材料，不是未知样本或模型必要性证明。旧版 `evaluate` 仍只评估原 10 个状态案例，不能拿它的分数衡量本轮原因调查质量。

## 执行边界

- 新路径最多 6 次模型决策，且服从进程累计请求上限（含失败请求）。每次输出最多 2400 Token；没有自动重试、自动切模型或失败后伪装成功。
- 默认最多 12 次工具调用、120 秒；基础事实采集也扣工具预算。重复相同结果、时间耗尽、非法输出或模型故障均停止并保留证据。
- 模型只能查询五个工具。对象来自已确认输入；未提供日志时间窗、任意 SQL/文件路径能力，模型不能通过文字扩大查询范围。
- 证据引用必须存在。SUPPORTED 需要有效支持且没有反证，CONTRADICTED 需要有效反证；历史引用不能删除。支持状态仍不是已确认根因。
- 推送历史的 `records=[]` 或日志的 `lines=[]` 不能作为原因假设的支持/反证，也不能作为 HYPOTHESIS 结论的有效引用；成功检索但没有历史记录不代表当时未发生异常。
- 日志/推送记录晚于缺失或未知的 POS 观察时，模型在 DIAGNOSIS_READY 前必须提出 POS 复查；否则 Java 拒绝该结束提议。这是确定性状态新鲜度门槛，不是模型独有的调查能力。
- 模型无法输出业务 Outcome 或 CONFIRMED 根因类型。自然语言解释仍可能不准确，需要审阅；引用校验并不能自动验证语义蕴含或因果关系。
- 模型失败时保留当前业务事实，调查任务标为需人工。业务报告中的“可提交补推提议”只是基于当前状态的候选方向，不代表已获审批或允许执行。
- 只提供显式意图和明确对象入口；自然语言对象抽取、ASK_USER 调查续跑、审批/修复、生产适配器、累计 Token 硬上限和独立保留集评估尚未实现。

## 代码入口

- `domain/Investigation.java`：假设、调查动作、上下文、版本和记录。
- `runtime/InvestigationRunner.java`：基础事实采集、意图路由、受预算约束的调查循环和降级。
- `runtime/InvestigationPolicy.java`：结构、引用、假设历史与动作门槛。
- `adapter/JsonModelPlanner.java`：复用真实供应商请求额度，增加 investigation-v1 严格 JSON 协议。
- `prompts/order-investigation-v2.txt`：模型任务、非可信数据边界和完整输出契约；v1 保留用于追溯首次实测。
- `evaluation/InvestigationDemoRunner.java`：单任务演示与可读轨迹导出。

职责设计中的 BasicFactCollector/InvestigationRouter 目前合并实现在 InvestigationRunner 中，标准状态沿用 ReportPolicy；不为概念数量拆出空壳服务。

## 验证记录

本阶段新增离线契约测试，覆盖零模型状态确认、已同步后的历史调查、反证更新、版本落盘、假引用、历史反证丢失、不完整及空历史观察、非法写/跨店查询、预算与取消、模型失败保留事实，以及 API 调查记录读取。真实模型已使用本轮授权的 10 次请求，执行链路完成但假设语义质量未完全通过，详见 [真实模型验证记录](stage1c-live-verification.md)；不将测试替身计为大模型成功。
