# Stage 3B 本地订单与 POS 只读查询

本轮新增真实 JDBC 适配器，默认关闭。开启后，本地订单查询访问 MySQL，POS 查询访问 SQL Server。美团继续模拟；没有调用 SDK，也不需要 AppKey。日志继续查询本地副本。应用仅监听127.0.0.1，仍无生产身份认证。

## 从上传资料确认的业务契约

来源为填写表以及上传代码 SaveDataServiceImpl、MeituanServiceImpl、MtOrderData，未复制公司业务源码或原始数据到仓库。

- 本地表 mt_order_data，order_id 是 Long，美团订单号；app_poi_code 是门店号。查询只返回元数据，不读 data/member_id，不验证原始 JSON 完整性。
- 本地没有 push_status/retry_count/error_msg。不存在时只证明本次查询未命中，@Async 保存可能尚未完成。查询本身不自动重试。
- 上传 saveData 代码把美团 ctime/utime 按 +08:00 写入 created_date/updated_date。填写表将其称为落库时间，两者不一致；当前以代码为准，不能用这些字段推断推送时间。
- POS 根据 mail_orderid 查询 t_rm_saleflow_frommail 和 t_rm_payflow_frommail；任一表存在即 FOUND。它仅表示接口流水接收，不代表 POS 后续业务完成。两表只有一张命中会给出 partialReceipt=true，不应重复补推。
- 为避免串门店，新增 branch_no 条件。部署者必须核对 app_poi_code → 所属数据库 → siss_storeid/branch_no 的实际映射；本轮不自动读取映射表，不接受模型指定数据源。映射错误可能造成假阴性，因此必须先用已知订单验证。

## 结构与边界

JdbcOrderAdapter 使用 PreparedStatement 固定 SELECT。输入只接受正的规范十进制订单号（Long 范围）；门店在网关和适配器复核，本地查到其他门店则 DENIED，不泄露对方元数据。POS 连接库由服务端配置，SQL 使用 dbo 表名，不跨库。

每库 Hikari 最大2连接、借连接超时2秒、查询超时2秒、网络读取3秒、外层工具6秒。池默认0空闲连接，关闭应用时释放。不把 SQLException 原文放进模型或 API 证据；超时返回 TIMEOUT/DB_TIMEOUT，其他数据库异常 ERROR/DB_QUERY_FAILED，绝不转成 ABSENT。发生错误时请在本地核对连接、权限、表结构。

只读权限必须由数据库账号实际实施。readonly-account-confirmed 是部署配置声明，不是权限检测，也不授予权限；JDBC setReadOnly 不是安全边界（SQL Server 不支持该方法）。本轮只执行固定 SELECT，但本地日志和任务数据仍会写磁盘。

业务提议要求所需基础观察来源一致：真实 POS + 模拟本地不能组合成补推提议；真实本地缺单 + 模拟美团不能组合成恢复提议。启用 JDBC 后，推送历史工具返回 UNKNOWN，避免沿用演示历史；模拟美团仍可以展示，但不能补足真实证据。调查上下文标记 MIXED_READ_ONLY，并逐条保留 simulated。

## Windows 本地配置

默认不连数据库，原有演示仍可运行。先用数据库管理工具确认账号仅有必要的 SELECT 权限；不要上传密码。配置示例全部使用假值。

在项目根目录创建 data/jdbc-local.properties（data/ 已忽略），从 src/main/resources/application-jdbc-example.properties 复制配置。此文件的 readonly-account-confirmed=true 仅在确认实际账号后保留。

启动终端：

```powershell
$env:JAVA_HOME='F:\ayd\jdk-17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:RETAILOPS_STORE_ID='C100001'             # 替换为真实美团门店号
$env:RETAILOPS_POS_BRANCH_NO='SX000001'       # 替换为核对过的 POS 门店编码
$env:RETAILOPS_LOCAL_DB_URL='jdbc:mysql://localhost:3306/mtjd?connectionTimeZone=Asia/Shanghai'
$env:RETAILOPS_LOCAL_DB_USER='onlyselect'
$env:RETAILOPS_POS_DB_URL='jdbc:sqlserver://YOUR_POS_HOST:1433;databaseName=YOUR_POS_DB;encrypt=true;trustServerCertificate=false'
$env:RETAILOPS_POS_DB_USER='YOUR_SELECT_ONLY_USER'
# 密码不写进命令历史；仅注入当前 PowerShell 进程环境。
$localSecret=Read-Host 'Local DB password' -AsSecureString
$env:RETAILOPS_LOCAL_DB_PASSWORD=[System.Net.NetworkCredential]::new('', $localSecret).Password
$posSecret=Read-Host 'POS DB password' -AsSecureString
$env:RETAILOPS_POS_DB_PASSWORD=[System.Net.NetworkCredential]::new('', $posSecret).Password
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--spring.config.additional-location=file:./data/jdbc-local.properties"
```

不要在 URL 嵌入口令，不要在 URL 重写超时或开启多语句。SQL Server 需配置受信证书，示例没有关闭证书校验。本地只有 MySQL 可用时，把复制后的 omniops.jdbc.pos.mode 改成 fixture；此时 POS 仍是模拟，不能用于真实业务判定。

本地 MySQL 为5.7，驱动显式使用8.0.33兼容基线，未跟随 Boot 升级到仅支持新服务端的驱动。此旧版本仅用于既有5.7环境联调；正式部署前应复核驱动安全更新并规划数据库升级。升级后的服务器可用 Maven 属性 retailops.mysql-driver.version 覆盖。SQL Server 驱动使用 Boot 管理版本；目标旧服务器兼容性需本地验证。

## 调用与验收

另开终端，先做无需大模型的状态核对（替换假值）：

```powershell
$body=@{
  userGoal='核对这笔订单是否进入 POS'
  storeId='C100001'
  orderId='3200000000000000001'
  strategy='investigation'
  intent='CHECK_STATUS'
  fixtureId='restore'
} | ConvertTo-Json
$task=Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8080/api/tasks' -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
Invoke-RestMethod "http://127.0.0.1:8080/api/tasks/$($task.id)"
```

任务异步执行，可再次 GET 查看终态。检查 evidence：LOCAL_JDBC/POS_JDBC 应 simulated=false；POS 两表标志、门店与人工查询一致。POS 已存在可能短路，不会重复查询本地库。

先验证已知存在订单，再验证确认不存在订单，最后验证错误门店拒绝和连接失败。不要把错误连接引起的失败误判为缺单。账号的 SELECT 权限应覆盖两张 POS 表；缺任何一张表的权限返回 ERROR。

原因调查时将 intent 改为 DIAGNOSE_CAUSE，并按既有文档配置 remote/DeepSeek 模型及本地日志 mode=local。无模型凭据时系统只采集基础状态并 NEEDS_HUMAN，不能把规则替身称为真实模型诊断。fixtureId 仅选择未接入工具的模拟响应，不影响真实 JDBC 查询目标。

本轮没有接入补推、库存、恢复订单或自动修复。元数据 FOUND 不代表 JSON 可被恢复；补推前的业务校验仍属于后续阶段。

## 自动化验证与资料

H2 使用 MySQL/MSSQLServer 模式验证 SQL、门店隔离、只读权限、错误语义、最小字段及混合来源策略；另有配置测试。H2 不等于真实引擎，不证明生产索引、锁行为、TLS 和厂商驱动兼容性。完整单测由 JDK17 CI 执行；真实 MySQL/POS 和真实大模型联调需要用户本地环境，本轮没有执行。

POS 查询使用 EXISTS 避免完整计数，但无合适索引仍可能扫描大量行；请由数据库维护者核对 mail_orderid/branch_no 索引与执行计划。本程序不建索引。

官方参考：
- https://dev.mysql.com/doc/relnotes/connector-j/en/news-8-0-33.html
- https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-networking.html
- https://learn.microsoft.com/en-us/sql/connect/jdbc/understand-timeouts
- https://learn.microsoft.com/en-us/sql/connect/jdbc/reference/setreadonly-method-sqlserverconnection
