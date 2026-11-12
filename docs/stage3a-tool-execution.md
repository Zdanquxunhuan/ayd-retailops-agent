# Stage 3A：只读工具注册与执行边界

本阶段拆分既有 Stub 网关，为后续真实适配器提供统一接入点，不代表真实订单、POS、美团或日志接入已经完成。

## 调用链

模型从注册表生成的 availableTools 选择工具 → InvestigationPolicy 校验提议 → ReadOnlyGateway 校验门店、订单和工具名称 → ToolExecutor 执行注册的 Handler → 结果归一化 → 产生 Evidence 并回到模型。

- ToolRegistry：服务端定义名称、版本、用途、超时、结果字符上限、模拟标记和 Handler。重复名称拒绝注册。
- ToolExecutor：2 个工作线程、16 个排队名额，单次调用有界等待。异常不透传底层凭据或连接信息。没有自动重试。
- FixtureToolAdapter：承接原有模拟响应序列和订单占位符替换；测试场景行为保持不变。
- ReadOnlyGateway：保留授权和目标复核，调用注册表中的工具，证据 simulated 标记由服务端配置。

当前工具参数仍为 storeId/orderId，结构化入参沿用 Diagnosis.Arguments；没有开放任意 SQL、路径或时间参数。精细工具参数和真实只读适配器属于 Stage 3B，库存场景属于 Stage 3C。当前注册表只用于只读能力，不能注册业务写操作来绕过 Stage 5。

## 故障语义

| 情况 | status | errorCode |
| --- | --- | --- |
| 超时 | TIMEOUT | TOOL_TIMEOUT |
| 执行异常 | ERROR | TOOL_EXECUTION_FAILED |
| 缺少必要结果字段 | ERROR | TOOL_INVALID_RESULT |
| 结果超过字符限制 | ERROR | TOOL_RESULT_TOO_LARGE |
| 执行容量不足或执行器已关闭 | ERROR | TOOL_CAPACITY_EXCEEDED |
| 调用方被中断 | ERROR | TOOL_INTERRUPTED |

所有上述结果 complete=false、outcome=INDETERMINATE，不能作为“对象不存在”的事实。工具白名单和对象越权在调度前拒绝。

默认 Stub 超时为 2 秒，结果上限为 60000 个 toString 字符。这个限制是已读取结果的内存表示检查，不是网络流量上限或 JSON 字节上限；真实 HTTP/数据库适配器还必须在读取时限制行数和字节数。

超时会请求取消 Future，但无法强制终止忽略中断的驱动；真实适配器必须自行配置连接/读取/查询超时。线程池和队列有界，避免无限增长。Gateway 作为 AutoCloseable 由 Spring 关闭执行器。

## 验证

新增 ToolExecutionTests 覆盖超时、异常脱敏、非法/过大结果、调用前授权、模拟标记和重复注册。既有状态诊断和调查测试继续作为兼容性回归。

本阶段不需要真实模型或业务凭据。完整结果以本分支的 GitHub Actions 为准，未执行真实模型调查质量评估。

## 下一步接入资料

Stage 3B 需要每个只读接口的脱敏请求/响应样例、认证方式、门店/订单键、分页/完整性语义、错误码和超时约束。日志还需确认逻辑来源、保留期和允许的查询范围。凭据通过本地环境或部署配置注入，不写入源码或提交记录。
