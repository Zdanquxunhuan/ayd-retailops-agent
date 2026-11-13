# Stage 3C：美团订单模拟适配与模型调查

日期：2026-11-13。仅只读调查，不执行恢复、补推或库存同步。真实模型使用已有百炼 qwen provider；平台业务接口继续模拟，无 SDK/AppKey 请求。

## 本轮实现

- MeituanOrderClient 定义内部请求/响应，MockMeituanOrderClient 返回原始模拟响应；MeituanOrderValidator 检查查询范围、订单与门店、候选数量、时间及最小载荷字段。MeituanOrderAdapter 接入现有注册表与执行器。
- 平台响应不再经过 FixtureToolAdapter 的订单号替换。错误订单/门店返回 DENIED，其他对象的值不进入证据。旧 order-cases-v1 的平台结果迁入 meituan-order-responses-v1，其他工具保持原行为。
- complete 表示查询完整性；payloadValidated 表示 meituan-simulation-v1 结构检查通过。必需字段为订单/门店、创建时间、非空状态、非空商品列表及 SKU/正数量/非负价格；这不是美团官方完整订单 Schema，也不是业务可恢复资格。productionPayloadValidated 始终 false，完整原始订单不传给模型或 API 报告。
- FOUND 但缺少载荷字段时仍报告订单存在，missingFields 说明缺口，禁止形成 RESTORE_RECOMMENDED。真实 POS/本地与模拟美团不能拼成真实恢复提议。
- 原始异常信息不输出，区分平台超时、限流、拒绝、范围错误、数据冲突和部分结果。未知或失败不变成缺单。
- 所有窗口案例使用固定业务时钟 2026-11-13T04:00:00Z，模拟区间为此前 5×24 小时至该时刻，两端包含；运行时证据有效期、预算仍使用真实 Clock。没有时间依据不推断窗口外。真实搜索/详情接口和五天精确契约需后续核验。
- 模拟响应序列位置由已持久化的平台 Evidence 数量推导，不使用共享可变游标。重启后沿用观察进度；未保存的只读调用可能再次执行，不承诺恰好一次。

## 大模型在主链路中的位置

创建任务 → Java 查询 POS、本地 → ContextBuilder 组装目标、证据、假设、用户补充与预算 → 百炼选择下一动作 → Java 校验 → 执行工具/补问暂停 → 保存 Evidence、State、Checkpoint → 再次调用模型。

DIAGNOSE_CAUSE 不再由 Java 自动追加美团查询：模型决定先查美团、日志、推送记录，或者 ASK_USER。根据反证更新假设的历史与引用，最终提交带依据和限定语的解释。Java 不按异常类名返回固定根因；缺证据可以 NEEDS_HUMAN。当前报告中的 rootCauseStatus 仍为 UNCONFIRMED，结构校验通过不等于自然语言因果已证实。

CHECK_STATUS 保留确定性状态查询，必要时自动查美团，零模型调用。恢复建议只表示后续调查/审批方向；元数据、模拟结构检查均不能直接授权真实写操作。

Prompt 升级 order-investigation-v11；保留增量假设、旧假设逐项复核、引用检查、补问和反注入约束。模型只看脱敏摘要、missingFields、窗口依据和工具列表，看不到 fixtureId、预期答案或原始商品载荷。没有配置调查模型时停在明确的 MODEL_NOT_CONFIGURED 语义，不使用规则替身冒充模型。

## Windows 运行：复用百炼配置

已有 DASHSCOPE_API_KEY、DASHSCOPE_ENDPOINT 继续使用，无需再提供密钥。新增可选 DASHSCOPE_MODEL 覆盖，默认仍为 qwen-flash。默认启动配置保持 stub，避免无意产生费用；显式 -Provider qwen 启用百炼，不回退 DeepSeek。

```powershell
.\scripts\stage1.ps1 test
.\scripts\stage1.ps1 build
# 以下会调用百炼。MaxRequests 是本进程上限，按本次已确认预算设置。
.\scripts\stage1.ps1 run -Provider qwen -MaxRequests 6
```

脚本仅在当前进程使用 F:\ayd\jdk-17。纯演示请保持 JDBC/logs 的 fixture 默认配置，使用 A001/O100。新场景固定该模拟对象；更换订单号会触发平台范围校验，不会自动把模拟响应伪装成其他订单。

另开 PowerShell：

```powershell
$body = @{
    userGoal = '调查这笔订单没有进入 POS 的原因，说明证据、可能原因和还缺什么'
    storeId = 'A001'
    orderId = 'O100'
    fixtureId = 'meituan-db-failure'
    strategy = 'investigation'
    intent = 'DIAGNOSE_CAUSE'
} | ConvertTo-Json
$task = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8080/api/tasks' -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
$id = $task.id
Invoke-RestMethod "http://127.0.0.1:8080/api/tasks/$id"
Invoke-RestMethod "http://127.0.0.1:8080/api/tasks/$id/investigation"
Invoke-RestMethod "http://127.0.0.1:8080/api/tasks/$id/report"
```

任务异步执行，按需再次 GET。报告未完成时返回 204。若 WAITING_USER，按 [Stage 2](stage2-stateful-investigation.md) 获取最新 version 并调用 resume；问题与时机由真实模型决定，不保证每次轨迹一样。6 次额度是单进程示例；多案例不能假设每例都有独立 6 次，进程重启也不等于获得新的用户授权预算。

只核对适配器与最终状态时，使用 stub 启动，将 intent 改为 CHECK_STATUS。需要导出单例模型轨迹时，也可使用现有命令（与上面的服务模式二选一，不重复占用额度）：

```powershell
.\scripts\stage1.ps1 investigate -Provider qwen -Fixture meituan-db-failure -MaxRequests 6
```

输出在 evaluation-output/investigation/<时间>/，包括 report.md、trace.json 和包含全部案例资源摘要的 manifest.json。若模型 ASK_USER，一次性命令会保存等待状态后退出；需要交互演示请使用上述 API 服务模式。

## 案例与验收观察

通过 GET /api/fixtures 查看列表。以下是开发案例，不能当作保留测试集。

| fixtureId | 应观察的行为 |
| --- | --- |
| meituan-complete / meituan-boundary | 匹配且模拟结构有效，可评估恢复建议；刚好五天按模拟约定可查 |
| meituan-incomplete | FOUND 与查询 complete 不等于载荷可用；明确缺少 items |
| meituan-wrong-order / meituan-wrong-store | 拒绝对象不符，不输出对方对象信息 |
| meituan-multiple / meituan-partial | 不任取候选或把部分结果当完整事实 |
| meituan-empty / meituan-unknown-time | 未命中不等于平台故障；无时间不推断超期 |
| meituan-outside-window | 本地及 POS 均缺失时提示门店人工补推 |
| meituan-timeout / meituan-rate-limit / meituan-rejected | 查询失败保留未知，不解释为缺单或超期 |
| meituan-db-failure | 模型可从关联日志提出保存异常假设，并说明证据与不确定性 |
| meituan-late-receipt | 日志出现迟到回执线索，模型应复核 POS 并修正当前缺单假设 |
| meituan-timeout-recovery | 第一次平台调用超时、后续调用成功；是否再次查/补问由模型选择，不自动重试 |

验收关注工具选择的依据、反证修正、补问质量和证据支持，不能只看最终 outcome。保存失败案例与 UNKNOWN，不为通过案例降低权限或引用门槛。后续 Stage 1F 再开展系统性的模型质量评估与强规则对照。

## 验证与限制

新增适配器契约、边界与来源隔离测试，文件快照后的响应序列测试，以及真实 HTTP 创建任务/查询报告/补问恢复测试。HTTP 调查使用明确标识的测试规划器，验证两种查询顺序和反证处理；它不证明百炼的推理质量。已有取消、预算、注入与恢复回归继续保留。

本地已完成核心领域与校验器隔离编译；完整 Maven 因依赖站点网络不可达，交由 JDK17 CI 执行。本轮执行环境没有 DASHSCOPE_API_KEY，未做新增百炼调用；不代表用户本机配置丢失。真实模型验收与真实美团/数据库联调仍单列待办。

下一阶段按 [主路线](design/04-agent-development-roadmap-v0.3.md) 进入订单 RAG 与长期 Memory；真实模型效果记录仍需在可用环境与明确预算内补齐。审批和受控恢复/补推属于 Stage 5。
