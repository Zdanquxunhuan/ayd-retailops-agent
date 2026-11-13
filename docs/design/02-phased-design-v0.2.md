# OmniOps Agent 分阶段技术设计

日期：2026-11-11。依据 V0.2 与配套《架构评审》。本文是待实现的设计，不表示相关代码、接口或生产条件已经具备。沿用原 Stage 0–6 编号，详细设计近期 Stage 0–1，并明确后续阶段的协议与准入条件。

> **路线更新（2026-11-13）：** 本文保留 V0.2 历史阶段编号与协议设计，不再作为开发先后顺序或当前完成状态的依据。最新路线见 [后续开发路线与 Agent 能力设计](04-agent-development-roadmap-v0.3.md)：Stage 2 → Stage 3（订单）→ Stage 4（订单）→ Stage 1F（订单）→ Stage 5（订单）→ Stage 6（订单交付）→ 库存扩展。先完成美团订单闭环，再开发库存；缺少美团凭据时按明确标记的模拟适配推进。
>
> 本文“Stage 2 订单写操作协议”对应新路线 Stage 5；本文库存阶段延后。仍适用的权限、审批、幂等和验证设计继续保留；业务语义以最新实现记录及新路线的明确修订为准，例如 POS 流水存在仅证明接口接收，不代表后续业务全部完成。

## 1 阶段范围与依赖（历史设计）

| 阶段 | 目标与交付物 | 前置条件 | 退出门槛 |
| --- | --- | --- | --- |
| Stage 0 基础与接入 | 模块边界、工具契约、身份上下文、元数据迁移、Stub、只读适配器、库存日志协议、选型 ADR | 可用开发环境；真实接入另需业务资料 | 工具可脱离模型独立测试，状态/错误语义一致，越权查询被拦截；真实工具未接通时明确标为 Stub，不宣称接入完成 |
| Stage 1 订单只读 Agent | 动态调查 Runtime、证据与假设、报告、模型适配、基线评估 | Stage 0 工具契约可用 | 完成本地/POS/美团/日志诊断；支持矛盾后重规划、未知结论、预算停止；无写工具执行路径 |
| Stage 2 订单受控修复 | 修复提议、审批、持久幂等、恢复/补推、验证/对账、生产开关 | R01–R03、R05 关闭，订单真实接口契约确定 | 重复与并发不造成预期外副作用；超时不盲重试；POS 独立验证；恢复半成功可续查 |
| Stage 3 库存只读 Agent | 配置、本地库存、平台 SKU、映射和编码检查、同步记录查询 | Stage 1 Runtime 稳定；库存只读接口完成 | 区分差异、映射歧义与历史根因未知；不使用上架/销售状态计算库存 |
| Stage 4 库存受控修复 | 单商品同步、快照审批、版本检查、平台验证与漂移处理 | Stage 2 通用执行底座与 Stage 3；R04 关闭 | 目标唯一；无法证明版本安全时停止；平台验证与本地漂移分开报告 |
| Stage 5 知识与评估增强 | 有权限和版本的 RAG、审核后 Memory、上下文压缩、扩大保留测试集 | 真实闭环和基础评估已稳定 | 知识不覆盖实时事实；过期/越权资料不被使用；新模型和 Prompt 可回归比较 |
| Stage 6 多平台扩展 | 美团退货、京东等 Adapter 与场景策略 | 单平台闭环稳定、取得新增业务契约 | 复用 Runtime/网关/审计；平台规则独立，新增工具不破坏既有测试 |

原先允许订单等待接入时并行推进库存的安排已被订单优先路线替代；库存待订单交付后启动，写能力仍需满足通用执行准入。暂不承诺工期：人力、接口可用性与部署条件尚未给出。以退出门槛安排里程碑，而不是把缺少接入条件的阶段写成确定日期。

## 2 Stage 0 模块与依赖

建议保持当前 Maven 单模块，在 `com.yu.retailops` 下按职责分包；后续确有独立部署或编译隔离需求再拆模块。

| 包 | 职责与主要接口 | 禁止依赖方向 |
| --- | --- | --- |
| api | TaskController、ApprovalController、请求校验、身份映射 | 不直接调用业务写客户端 |
| application | TaskService、RepairCoordinator、ReportService | 不将模型文本直接转为数据库更新 |
| runtime | AgentRunner、DecisionValidator、ContextBuilder、BudgetGuard | 不持有生产库与平台凭据 |
| domain | Task、Decision、Evidence、Hypothesis、RepairOperation、状态规则 | 不依赖 Spring 或厂商 SDK 的私有 DTO |
| tool | ToolRegistry、ToolGateway、ToolDefinition、ToolResult | 不接收模型定义的风险等级与权限 |
| policy | AuthorizationService、RepairPolicy、ApprovalValidator | 不由 LLM 输出覆盖规则 |
| adapter | localorder、pos、meituan、inventory、logs、model | 不修改任务状态；只返回标准结果 |
| persistence | Repository、迁移、工作项领取、唯一约束 | 不执行生产业务写 SQL |
| observability | 脱敏审计、trace、指标 | 不记录完整敏感订单或模型私有思维链 |
| evaluation | 测试夹具、评分、Workflow 对照 | 不使用保留测试集调优 Prompt |

运行环境分 `stub`、`integration`、`production`。Stub 数据必须在工具结果和报告中标识，生产配置禁止加载 Stub 适配器。首期写能力默认禁用；没有安全配置时启动失败或只读降级，不能默认为自动修复。

### 2.1 工具协议

ToolDefinition 至少包含 `name, version, inputSchema, outputSchema, effect, requiredPermissions, timeout, retryPolicy, maxResultSize`。effect 与权限由注册表定义，模型 riskHint 只作提示。

调用上下文由服务端注入 `taskId, invocationId, principalId, organizationId, authorizedStoreScope, traceId, deadline`。即使首期单组织，也必须限定门店范围。模型参数不能携带或覆盖这些值。

沿用 V0.2 的 ToolResult.status，并增加以下字段：

| 字段 | 语义 |
| --- | --- |
| outcome | FOUND、ABSENT、MULTIPLE、NOT_APPLICABLE、INDETERMINATE 等业务结果；按工具 Schema 限定 |
| completeness | COMPLETE、PARTIAL、UNKNOWN；分页未完成不能得出完整空结果 |
| queryScope | 平台、门店、对象与时间范围；保留查询是否覆盖目标的依据 |
| sourceVersion / sourceObservedAt | 数据源版本与源时间；没有版本时显式空值，不伪造 |
| observedAt / validUntil | 本系统观测时间与策略给出的失效时间 |
| dataRef / evidenceIds | 脱敏数据或受控原文引用，以及由系统生成的证据 ID |
| errorCode / retryAdvice | 区分参数错误、权限、限流、业务拒绝、查询超时和发送后结果未知 |

INVALID_ARGUMENT 使用 `status=ERROR` 配合 errorCode；HTTP 成功但业务拒绝不映射为工具成功。`retryable=true` 不能独立触发写重试，所有副作用恢复都归 RepairCoordinator。

首期工具参数收敛为单对象查询：`order.local.query`、`pos.order.query` 使用经过解析的业务键；`meituan.order.search` 必须带门店、订单标识及受限时间条件；`logs.search` 接收逻辑 sourceId，后台映射固定服务与路径。工具不得让模型指定任意数据库、表名、主机或目录。

对象业务键建议为订单 `(organization, platform, store, platformOrderId)`，库存 `(organization, platform, store, appSpuCode, resolvedSkuId)`；实际唯一性由接口和业务表验证。appSpuCode 对应多个 SKU 时不能任意取第一项或自行求和。

### 2.2 持久化设计

所有记录均携带组织范围、createdAt 和关联标识。时间统一存 UTC，面向业务展示按 Asia/Shanghai；美团接口时间边界另按确认契约转换。

| 实体 | 主要字段及约束 | 首次阶段 |
| --- | --- | --- |
| agent_task | id、createdBy、mode、businessKey、status、outcome、version、budgetUsed、deadline、leaseOwner、leaseUntil；状态更新使用 version CAS | 0 |
| task_event / work_item | taskId、sequence、eventType、payloadRef、availableAt、claimVersion；taskId+sequence 唯一；状态变更与事件同事务 | 0 |
| agent_plan_version | taskId、version、goal、decisionSummary、model/prompt/schemaVersion；taskId+version 唯一 | 1 |
| tool_invocation | invocationId、taskId、toolVersion、argsHash、状态、起止时间、attempt、resultRef；调用前先落记录 | 0 |
| evidence | evidenceId、taskId、invocationId、kind、source、queryScope、valueSummary、observedAt、validUntil、sourceVersion；追加保存 | 0 |
| hypothesis / hypothesis_event | taskId、description、状态、支持/反证引用及版本历史 | 1 |
| repair_operation | operationId、businessKey、type、intentRevision、payloadHash、snapshotRef、idempotencyKey、executionStatus、verificationStatus；幂等键唯一 | 2 |
| approval_record | operationId、payloadHash、snapshotVersion、policyVersion、approver、expiresAt、decision、revokedAt；绑定具体提议 | 2 |
| active_operation | businessKey 唯一、operationId、ownerVersion；未知结果继续占用，不能因租约到期自动允许下一次写入 | 2 |
| audit_event | actor、action、target、decision、policyVersion、traceId、脱敏摘要；业务层仅追加，运维保留策略单独授权 | 0 |
| inventory_sync_event | 精确目标、实际发送 stock、版本、请求/响应时间、状态、平台验证引用 | 3，协议于 0 定义 |

数据库 DDL 在选定数据库后落地，避免把逻辑模型当成可直接执行的迁移。业务库不放 Agent 元数据。证据删除按保留策略处理；保留引用墓碑，不能留下看似可验证的悬空成功结论。

## 3 Stage 1 Runtime 详细设计

### 3.1 任务状态与业务结果

保留 V0.2 主状态，补充 WAITING_RETRY、CANCEL_REQUESTED、CANCELLED。不得把图中的箭头理解成唯一线性流程。

| 当前状态 | 事件与守卫 | 下一状态 |
| --- | --- | --- |
| CREATED | 身份及范围有效，必要实体完整/不完整 | PLANNING / CLARIFYING |
| CLARIFYING | 用户补充并校验通过 | PLANNING |
| PLANNING | 合法 CALL_TOOL 且预算足够 | EXECUTING_TOOL |
| EXECUTING_TOOL | 结果与 Evidence 已持久化 | REASONING |
| REASONING | 有新调查方向 / 需补充 / 有修复建议 | PLANNING / CLARIFYING / PROPOSED_REPAIR |
| PLANNING、REASONING | 合法 FINISH 且报告校验通过 | COMPLETED |
| 调查态 | 数据不足、关键矛盾未解、预算耗尽 | NEEDS_HUMAN，附明确 reasonCode |
| EXECUTING_TOOL | 可重试只读失败且剩余预算足够 | WAITING_RETRY，再返回 PLANNING |
| PROPOSED_REPAIR | Observe-only / 后续阶段允许审批 | COMPLETED，outcome=REPAIR_RECOMMENDED / AWAITING_APPROVAL |
| 非终态 | 系统故障且无法安全恢复 | FAILED |
| 非终态 | 收到取消，尚未发送副作用 | CANCELLED |
| EXECUTING_REPAIR | 收到取消，但请求已发送或无法确定 | CANCEL_REQUESTED；继续必要对账后收敛，不假称已撤回 |

任务 outcome 独立记录 `DIAGNOSED、ALREADY_CONSISTENT、REPAIR_RECOMMENDED、REPAIR_VERIFIED、UNRESOLVED`。COMPLETED 仅表示任务收尾；只有独立验证通过才能报告 REPAIR_VERIFIED。

只读调用重启后可以按预算重新获取新证据；旧 Evidence 不覆盖。处于发送中或结果未知的写操作只进入对账，绝不自动回到“可发送”。长期审批等待不占 Worker，并设有效期；过期后转 NEEDS_HUMAN 或生成新提议。

### 3.2 单轮执行协议

1. Worker 领取任务并得到版本凭据，读取最新状态、预算、计划和证据。
2. ContextBuilder 选择有效业务证据、尚未解决的矛盾及允许工具；保留证据引用，不把所有日志堆进 Prompt。
3. ModelProvider 返回 AgentDecision。用 decisionType 判别联合类型：CALL_TOOL 必须有 toolCall，ASK_USER 必须有 userQuestion，PROPOSE_REPAIR 必须有 proposal；拒绝多种互斥 payload、额外权限字段和未知工具。
4. DecisionValidator 校验参数来源、证据引用、对象权限、模式和剩余预算。Schema 修正最多允许一次，计入预算；仍错误则结束为明确系统错误或 NEEDS_HUMAN。
5. 在短事务内记录计划、调用意图、状态及工作项；执行只读工具；再原子记录结果、证据、消耗和下一事件。
6. 模型只能提出假设更新。服务端检查引用属于当前可访问任务且来源有效，保留反证历史。
7. FINISH 经过 ReportValidator 检查；缺乏因果支持时只报告现象和未确认原因。引用 ID 存在不代表内容支持结论，关键案例仍需人工复核和语义评估。

首版串行工具调用足以验证动态调查；后续仅对无依赖只读工具开启有界并发。动态性来自根据结果选择下一动作，而非并行数量。

初始开发预算建议：模型调用 8 次、工具调用 12 次、单个只读调用额外重试最多 2 次、活动执行总时长 120 秒。均为可配置的起始值，不是业务 SLA；基于联调测量调整。审批/用户等待使用独立截止时间；Token、费用和调用数跨重启累计。重复相同查询且无新证据时触发无进展停止。

### 3.3 最小 API

| 接口 | 输入与结果 | 行为 |
| --- | --- | --- |
| POST /api/tasks | userGoal、platform、businessType、用户提供实体；返回 taskId、status | 202 接收异步任务；mode 由服务端策略裁决 |
| GET /api/tasks/{id} | 状态、outcome、版本、预算摘要 | 对任务所属组织与门店再次授权 |
| POST /api/tasks/{id}/clarifications | 用户补充、expectedVersion | 只在 CLARIFYING 接受；版本冲突返回 409 |
| GET /api/tasks/{id}/events?afterSequence= | 递增事件与脱敏摘要 | 可轮询恢复进度，后续按需要增加 SSE |
| GET /api/tasks/{id}/report | 事实、假设、证据、动作、验证、不确定事项 | 报告也按字段与对象权限裁剪 |
| POST /api/tasks/{id}/cancel | expectedVersion | 记录取消意图；返回真实是否仍需对账 |
| POST /api/operations/{id}/approvals | decision、payloadHash、expectedVersion、comment | Stage 2 开放；审批人从登录身份获取，不从请求接受 |

任务创建支持请求级幂等键，作用域为调用者与组织；相同键不同内容返回冲突。它与业务写操作幂等是两套机制，不可混用。是否允许发起人审批自身任务由业务角色策略确定，未配置时拒绝写审批。

### 3.4 订单只读决策边界

以下是结果约束，不强制工具执行顺序：

- POS 已存在：报告已同步；不生成补推操作。
- 本地存在，POS 权威查询明确不存在：生成补推建议；历史失败原因仍需日志支持。
- 本地缺失，美团搜索返回匹配完整订单：可建议恢复；Stage 1 不执行。
- POS 查询错误、返回不完整或门店归属不一致：结论未知，不建议直接补推。
- 五天范围外：仅阻止依赖该搜索接口的恢复；本地已有订单是否能补推仍按本地与 POS 条件判断。
- 搜索无结果：检查范围、门店和接口状态，不能直接确认平台没有订单。

## 4 Stage 2 订单写操作协议

操作生命周期为 `PROPOSED → AWAITING_APPROVAL → READY → DISPATCHING → ACKNOWLEDGED / FAILED_CONFIRMED / UNKNOWN`。verificationStatus 独立为 `NOT_STARTED、VERIFYING、VERIFIED、MISMATCH、INCONCLUSIVE`。ACKNOWLEDGED 仅表示接口受理或返回成功。

1. 服务端根据可信快照生成 operationId、payloadHash、intentRevision 与幂等键；模型不得提交完整订单作为可信写 payload，恢复数据从可信 dataRef 读取并由 Java 校验。
2. 审批绑定具体操作、对象、参数摘要和版本。恢复与补推默认分别授权；若未来批量审批，必须明确列出两个子操作和各自前置条件。
3. 执行时重查身份权限、审批有效性、策略版本、业务快照、POS 状态与对象占用；变更导致审批失效，重新提议。
4. 本地短事务记录发送意图并占用对象。重试使用原 operationId 和幂等键；不同 taskId 不得绕过同一业务意图的唯一约束。
5. 调用既有业务服务。已知未发出的失败可以有限重试；已发出或无法判断是否发出的失败进入 UNKNOWN，并禁止相同对象的新写操作。
6. 恢复后查本地，再查 POS。若恢复方法已经推送且 POS 存在，跳过补推。若本地已恢复但 POS 查询未知，保留部分结果，转对账或人工。
7. 补推后独立查 POS。超时或失败但 POS 已存在，报告业务目标已达到，同时保留接口异常；仅能确认状态，不能无依据声称一定由本次动作造成。
8. 对账超出时限仍不确定，转 NEEDS_HUMAN，保留对象占用直到按受控流程解除。不得以自动删除本地订单作为补偿。

幂等键不应只包含 taskId，也不应永久封死同一商品未来同步：键由业务对象、操作类型、已批准意图版本和参数摘要生成；重试沿用，后续确有新需求时创建新意图。订单下游唯一性、原同步程序并发和平台重复请求能力必须实测。应用锁只能限制本 Agent，不能提供跨系统的唯一执行承诺。

## 5 Stage 3 与 Stage 4 库存协议

Stage 3 的确定性检查输出本地记录 FOUND/NOT_FOUND/MULTIPLE、配置状态、编码差异、映射唯一性、localStock/platformStock/delta，以及查询时间与版本。未知根因可以与明确的当前库存差异并存；安全重新同步不等于确认历史故障原因。

Stage 4 提议保存 `storeId、appSpuCode、resolvedSkuId、localStock、localVersion、mappingVersion、configVersion、observedAt`。若源系统没有版本，时间戳或内容 hash 只能检测部分变化，不能证明未发生并发修改。

执行前重新读取上述快照；值或版本变化，旧审批失效。现有同步方法必须能报告实际发送的目标值与读取时间。如果该方法自行读取最新 stock，需在业务接口内落实版本检查与发送的一致性，不能由 Agent 在外部“先查后发”替代。无法实现时，不开放自动执行，人工路径也明确披露竞态。

验证同时记录三项：本次发送目标、平台当前值、本地当前值。

| 情况 | 操作验证 | 当前业务结论 |
| --- | --- | --- |
| 本地版本未变，平台等于发送目标且等于本地 stock | VERIFIED | 观测时库存一致 |
| 平台等于发送目标，但本地已经变化 | 可记录目标曾被观测满足；任务标记 SUPERSEDED | 不能宣称当前已修复，重新只读诊断 |
| 平台不等于目标，本地版本未变 | MISMATCH | 按平台可见性契约有限验证，再人工处理；不循环重写 |
| 平台查询失败或返回 SKU 歧义 | INCONCLUSIVE | 无法验证，不推断平台值 |
| 平台等于最新本地值但与本次目标不同 | 本次操作效果不确定 | 仅报告当前一致，不归因于本次修复 |

美团写后可见性与轮询窗口没有在基线中明确，属于待确认项；不得把 POS 的“立即可查”假设套用到库存接口。库存日志只记录真实发送与观测，不补造历史事件。

## 6 从 Stage 0 开始的验证与评估

以下是待执行验收，不是已经通过的测试结果。

| 测试类别 | 必备场景 | 通过依据 |
| --- | --- | --- |
| 工具契约 | FOUND、空结果、分页不全、超时、权限拒绝、限流 | 保留正确 status/outcome，不把失败当不存在 |
| Runtime | 非法 JSON、未知工具、重复无进展、反证、预算耗尽、进程重启 | 有界终止、证据持久化、旧 Worker 更新被拒绝 |
| 权限与注入 | 跨门店查询、伪造身份、日志包含“忽略规则”、越权证据引用 | 网关拒绝，模型输出无法提高权限 |
| 订单 | 本地有/POS 无、本地无/平台有、POS 未知、五天边界、接口成功但 POS 无 | 写建议与最终验证符合业务规则 |
| 审批 | 参数替换、过期、撤销、权限变化、重复审批 | 无效审批不能进入发送阶段 |
| 写恢复 | 发送前崩溃、发送后未落结果、重复投递、下游超时后成功、恢复自带推送 | UNKNOWN 对账，不出现预期外重发 |
| 并发 | 同对象跨任务、旧系统同步同时执行、Worker 租约过期 | Agent 内互斥有效；跨系统缺少保障时阻止开放能力 |
| 库存 | 多 SKU、空格歧义、stock=0、上架状态变化、审批后库存改变、验证时漂移 | 不改编码/映射；只用 stock；漂移不误判修复成功 |

安全门槛为所有强制负向案例通过，测试中违规写操作和非预期重复写均为 0；这不等于证明生产风险绝对为 0。诊断质量先建立基线，再与业务方确定门槛，不预填虚构准确率。

评估记录 caseVersion、输入、允许工具、必要证据、禁止动作、标准答案、modelVersion、promptVersion、schemaVersion、随机配置与执行轨迹。Workflow 与 Agent 使用同一工具和约束；将训练/调试案例与保留案例隔离。模型随机性用重复运行和分布呈现，不承诺逐字复现。

根因准确率同时报告覆盖率、转人工率和有标准答案的总样本数，避免只挑已完成案例。证据支撑率核验内容与时效，不只统计引用数。根因诊断、当前状态恢复、成本和 P95 耗时分别计量；未知案例应允许“无法确认”为正确行为。

## 7 运行与发布门禁

每阶段依次经历 Stub 验证、测试环境真实联调、生产只读观察，再开放相应受控写能力。生产写默认 Approval-required，Allowlisted-auto 需要独立评审和真实回归数据。

指标至少包括任务积压、各类终态、模型与工具耗时/成本、UNKNOWN 操作数量及持续时间、审批等待、权限拒绝、验证不一致与漂移。单次日志和工具输出有大小上限，敏感字段在进入模型前脱敏。

模型服务故障时停止新规划并输出已知证据；已发送写请求的对账由 Java 继续。业务库不可用时保留未知，平台限流按 Retry-After 或契约退避并计入预算。审计与操作意图不能持久化时禁止新写。

提供全局与分工具写禁用开关。回滚应用版本前排空或接管未决操作，关闭新写后继续对账；关停不等于撤回已发送请求。数据迁移采用兼容旧读取的扩展方式，备份恢复演练覆盖任务、证据、审批和未决操作。容量、备份目标和保留期限在部署资料齐备后单独定稿。

## 8 初版下一批工作（历史记录）

以下为 2026-11-11 的原始待办，不再表示当前下一步；最新进度与 Stage 3C 订单任务见 [主路线第 2、10 节](04-agent-development-roadmap-v0.3.md)。保留原始设计过程供追溯：

1. ADR-001：核验并锁定 JDK、Boot、Maven、元数据库与模型适配方案。
2. 输出 ToolResult、AgentDecision、Task 与 Evidence 的正式 Java DTO/JSON Schema 和契约夹具。
3. 取得订单/POS 查询契约后，落地最小迁移、Task API、只读网关及适配器；没有资料的部分继续使用明确标识的 Stub。
4. 实现可暂停恢复的串行 Runtime、证据报告和版本化预算；首先运行固定模型 Stub，再联调真实模型。
5. 以同一批订单场景运行 Java Workflow 与 Agent 对照，关闭 Stage 1 问题后进入 Stage 2。

后续阶段仍需结合真实接口补齐 SQL 映射、请求字段、事务细节、DDL 与部署参数；本文已经确定其边界、关键流程和验收标准，不把未知信息猜成实施事实。
