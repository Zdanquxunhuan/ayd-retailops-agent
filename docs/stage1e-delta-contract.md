# Stage 1E：简化模型输出与用户可读报告

模型仅在建立新假设时给出完整定义。后续每个已有假设只提交状态、增量支持/反证、尚缺证据及简短理由。InvestigationDelta 将其展开为既有内部契约，保留服务端已存的命题、时段、反驳条件及旧引用。

服务端不会补造漏掉的假设判断；缺少任一已有假设的 update 会拒绝。复核记录中的新证据集合和“维持/修改”类型由程序机械生成，不应把这些字段齐全当作模型正确理解了所有证据的证明。模型仍负责具体语义、状态建议及下一步动作。Java 的证据和权限门槛不放松。

当前 Prompt 为 order-investigation-v8；内部任务契约仍为 investigation-v2，模型线路使用增量格式。旧 Prompt 和轨迹保留。本地构建测试共 68 项通过，包含新增的定义/历史引用保留、漏复核拒绝和未知假设拒绝测试。

运行入口保持不变，例如在已注入 DASHSCOPE_API_KEY 且有授权额度的终端执行：

```powershell
.\scripts\stage1.ps1 investigate -Provider qwen -Fixture investigation-reversal -MaxRequests 6
```

report.md 新增普通用户可读摘要，当前状态仍由 Java 证据规则生成；假设及模型解释仍需审阅。常见问答见 [使用例子](stage1e-user-examples.md)。

排查格式问题时可显式设置 JVM 属性 -Domniops.capture-model-output=true，仅在解析失败时将模型公开输出正文写入 evaluation-output/model-format-diagnostics。默认关闭，不记录密钥、请求头或私有推理。本轮只在模拟数据测试中使用；这是诊断材料，不是可信事实。解析失败请求的 Token 暂未计入任务统计，不能将汇总值当完整账单。
