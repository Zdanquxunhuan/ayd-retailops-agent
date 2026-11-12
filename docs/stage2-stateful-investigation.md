# Stage 2：有状态补问与单机恢复

日期：2026-11-12。基于 Stage 1E，调查 Prompt 更新为 v9。业务工具继续为 Stub；本阶段不调用生产接口、不执行写操作。

## 已实现

- `AgentState` 保存执行位置、待执行只读查询、纠正机会、反馈、待答问题、等待期限和用户回答。
- `ASK_USER` 提议经过原有假设与证据校验后保存为 `WAITING_USER`，释放 Worker。
- `ContextBuilder` 组装事实、假设、反证、历史查询、预算和不可信用户补充。保持现有 Context 线格式；回答有明确来源标记，不生成 Evidence。
- 基于现有 `FileTaskRepository` 原子快照保存整个任务。没有更换数据库，也不引入多进程执行。
- 重启后保留 `WAITING_USER`；进行中的 investigation 变为 `PAUSED`，调用恢复接口后续跑。旧 agent/workflow 继续采用中断后转人工策略。
- 恢复保留 Trace、决策/工具累计次数、活动执行耗时、Token 和纠正额度，重新读取过期的基础业务状态。
- 未落结果的只读调用可再次执行并继续扣预算，不能保证外部调用恰好一次。未保存的模型响应可能需要重新请求。
- 版本校验、单进程任务所有权和任务目录文件锁防止重复恢复及共享目录多进程执行。
- 用户等待默认 24 小时；恢复时检查过期。等待时间不计入活动执行时间。终态和已取消任务不允许恢复。

## 使用

先构建并运行（PowerShell，JDK 仅作用当前进程）：

```powershell
.\scripts\stage1.ps1 test
.\scripts\stage1.ps1 build
# 凭据由环境安全注入；运行前自行设定本次调用预算。
.\scripts\stage1.ps1 run -Provider qwen -MaxRequests 10
```

创建调查任务：

```powershell
$body = @{
  userGoal = '调查订单此前异常的原因；如缺少我能提供的信息，请具体问我'
  storeId = 'A001'
  orderId = 'O100'
  fixtureId = 'investigation-no-history'
  strategy = 'investigation'
} | ConvertTo-Json
$task = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8080/api/tasks' -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
$id = $task.id
$task = Invoke-RestMethod "http://127.0.0.1:8080/api/tasks/$id"
$task.status
$task.agentState.question
```

模型是否补问取决于实际决策，不保证这个样例一定产生 ASK_USER；确定性补问与恢复用测试替身验证。

当状态为 WAITING_USER，可停止再启动服务（保持同一 data/tasks 目录），然后重新读取版本并回答：

```powershell
$task = Invoke-RestMethod "http://127.0.0.1:8080/api/tasks/$id"
$body = @{ expectedVersion = $task.version; answer = '大约十分钟前发现异常，后来订单出现了' } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8080/api/tasks/$id/resume" -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
```

若重启后为 PAUSED，提交相同接口，只传 expectedVersion，不传 answer。版本过期、已在执行或状态不符返回 409；回答为空或超过 2000 字符返回 400。最多保留 8 次回答。等待过期后恢复会返回已转人工的任务。刚观察到等待状态但旧 Worker 尚未退出时可能短暂返回 409，应重新获取任务后重试。取消仍使用 POST /api/tasks/{id}/cancel。

## 设计与恢复边界

状态为任务权威记录，Context 是一轮输入。用户回答不会修改 storeId/orderId，不具有授权或事实效力。每次恢复再次执行门店范围校验。当前仍限本地单用户、单进程，没有生产认证。

保存动作提议后再执行工具；工具观察与 pendingQuery 清除在同一任务快照中提交。补问的问题、期限、假设和 WAITING_USER 一次提交，避免重启后丢失问题。模型决策次数在发起前保存，失败或重启不返还。

上下文目前采用 60000 字符近似保护，超限明确停止，不静默删除反证；这是初始大小保护，不是精确 Token 计量或语义压缩。RAG、长期 Memory 和分层压缩属于 Stage 4。

当前证据仍沿用 Stage 1E 的 120 秒有效期。基础状态会刷新；旧历史证据不会自动延长有效期，旧假设的引用若不再满足政策可能导致转人工。证据关系可修正和完整历史证据时效策略将在 Stage 1F 完善，不能宣称任意长期调查均能无损完成。活动耗时在快照边界保存，崩溃到最后快照之间的未记录耗时无法精确恢复。

恢复只适用于当前兼容契约；agent-state-v1 之外版本拒绝恢复。未来模型/契约升级需增加迁移。进程级模型请求额度仍随进程重启重置，任务决策预算累计保留；这不是账户级费用限制。

## 验证记录

本轮使用 JDK 21 编译了领域模型、调查 Runtime、策略和上下文构建器，并使用替代 FixtureCatalog 的隔离探针通过：

1. 补问后回答继续，证据、假设轨迹及累计 Token/决策保留。
2. 执行前取消不调用工具。
3. 过期基础事实重新查询，旧证据不删除。
4. 恢复不能重置决策预算。

新增 StageTwoTests 覆盖文件快照重启、回答续跑、版本冲突/重复恢复、运行中任务转暂停及等待过期。完整 Maven 测试因当前环境缺少 Spring Boot 父 POM 且依赖网络不可用，未能执行。隔离探针不覆盖 Jackson 文件序列化或 HTTP 集成，不应等同于完整测试通过。GitHub Actions 配置使用 JDK 17 执行完整测试；结果以对应运行记录为准。

没有使用真实模型做新增补问实验，没有产生本轮模型调用费用。真实模型端到端验收、生产接入和分布式恢复尚未验证。
