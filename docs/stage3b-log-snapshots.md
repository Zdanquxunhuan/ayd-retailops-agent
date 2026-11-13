# Stage 3B 第一部分：模拟美团与本地日志副本

美团继续使用 FixtureToolAdapter，不连接 SDK，不需要 AppKey。restore、outside-window、platform-timeout、platform-empty 分别覆盖平台查到、窗口外、超时、空结果。5 天窗口为模拟场景，不是经过真实 SDK 验证的边界计算。

## 启动

把日志 gzip 副本放在独立的本地目录，例如 F:/ayd/retailops-log-samples。仅支持 b-push-center-server.log.YYYY-MM-DD.N.gz（也支持上传产生的 N(1).gz）。配置日期由服务端控制，不从模型读取路径或命令。

```powershell
$env:JAVA_HOME='F:\ayd\jdk-17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--omniops.logs.mode=local --omniops.logs.directory=F:/ayd/retailops-log-samples --omniops.logs.date=2026-11-11"
```

沿用 POST /api/tasks，strategy=investigation，storeId=A001，orderId 为副本内单号，fixtureId=restore 等。模型 provider 默认 stub；真实大模型需另行配置原有 remote/DeepSeek 配置。日志执行只有在规划器调用 logs.search 时发生，启动不会自动查询日志，也不保证每个任务都调用日志。模型决定下一步工具，Java 控制范围、预算和来源。

## 第一版结果与边界

以下是 v1 的边界记录；v2 的现行行为见 [日志事件取证](stage3b-log-events.md)。

- 美团、本地订单、POS、推送历史仍模拟，Evidence.simulated=true；日志副本为 false，但不代表当前线上状态。
- 日志结果 LOG_SNAPSHOT 包含文件、行号、同一行的异常特征、扫描范围和 scanTruncated。
- 不回传原始业务报文，仅提取通用 Exception/Error 类名与少量诊断标志；未知异常类可以返回，不输出固定根因结论。这一版尚未做跨行堆栈、线程关联或完整日志语义脱敏，不能宣称完成任意根因自动诊断。
- 同一行出现异常也不自动证明因果。没有单号的后续堆栈不会自动关联，避免混入并发订单。
- complete 始终 false：副本覆盖不代表完整业务链路。未命中不能推出美团没推送。
- 限制：最多检查1000个目录项、8个文件、2000万解压字符、单行32768字符、20条命中；工具超时5秒，配合线程中断。达到上限返回 PARTIAL。只用于可信本地磁盘，不用于网络挂载或对抗性可写目录。
- 不启动 shell、不连接 SSH、不写日志。只读普通文件并拒绝符号链接。目录必须由部署者控制，副本应仅含获准查询的数据；当前仍是 localhost 演示应用，无真实多租户权限系统。
- 原始附件不入库；测试使用合成日志。目录 data/ 已被 gitignore 排除。

## 测试

LocalLogAdapterTests 覆盖精确匹配、载荷排除、未知异常、空目录、非法输入、长行、命中截断、损坏 gzip、美团四种模拟场景。执行 .\mvnw.cmd test。当前工作环境无法解析 Maven Central，完整验证以 PR 的 JDK17 CI 为准。

下一步：按日志事件边界关联异常堆栈，设计可审计的正文脱敏，并补充真实本地订单/POS 查询适配。这些均未在本次实现。
