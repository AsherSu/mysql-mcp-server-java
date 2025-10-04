# MCP MySQL Server

MCP MySQL Server 是一个基于 Spring Boot 的 Model Context Protocol (MCP) 服务器，用于安全地（可受控写操作）执行 MySQL 查询并向 AI 暴露受管数据库能力。

## 功能特性

- 多数据库动态连接：通过 createConnection / createConnectionAdvanced 动态创建连接，返回 connectionId
- Hikari 连接池高级参数：支持 connectionTimeout / idleTimeout / maxLifetime 自定义
- 连接管理：列出、关闭已建立的动态连接
- 受控写操作：对非 SELECT 的语句采用首关键字白名单（INSERT/UPDATE/DELETE/DDL 等），可动态增删
- 元数据查询：列出表、获取表结构
- 只读查询：严格限制 queryWithConnection 仅允许 SELECT
- MCP WebMVC：SSE 端点 (/sse, /mcp/message) 兼容 Spring AI MCP Client
- 服务注册：通过 Nacos (@EnableDiscoveryClient)
- 测试：使用 Testcontainers 自动拉起 MySQL 进行单元测试

## 系统要求

- JDK 17
- Maven 3.6+
- Docker (运行测试需要 Testcontainers)

## 构建
```bash
mvn clean package
```

## 运行
```bash
java -jar target/mcp-mysql-server-1.0.0.jar
```
默认端口: 18080

## 配置（环境变量）

| 变量 | 说明 | 默认 |
|------|------|------|
| SPRING_DATASOURCE_URL | 初始主数据源 URL | jdbc:mysql://localhost:3306/your_db?... |
| SPRING_DATASOURCE_USERNAME | 初始用户名 | root |
| SPRING_DATASOURCE_PASSWORD | 初始密码 | root |
| NACOS_SERVER_ADDR | 可覆盖 application.yml 中 server-addr | 无 |

(动态连接不依赖上述主数据源，可在工具调用里单独指定 host/port/database/账号)

## MCP 工具一览

| 工具方法 | 说明 |
|----------|------|
| createConnection(host, port, database, username, password, params) | 创建连接，返回 {connectionId,url} |
| createConnectionAdvanced(host, port, db, user, pwd, params, connectionTimeoutMs, idleTimeoutMs, maxLifetimeMs) | 指定超时策略创建连接 |
| listConnections() | 列出所有活动连接 |
| closeConnection(connectionId) | 关闭并移除连接 |
| queryWithConnection(connectionId, sql) | 执行仅限 SELECT 的查询 |
| executeUpdateWithConnection(connectionId, sql) | 执行白名单内首关键字的写/DDL，返回影响行数 |
| listWriteWhitelist() | 查看当前白名单关键字 |
| addAllowedWriteCommand(keyword) | 动态添加白名单关键字 |
| removeAllowedWriteCommand(keyword) | 移除白名单关键字 |
| listAllTablesName(connectionId) | 当前库所有表名（逗号分隔） |
| getTableSchema(connectionId, tableName) | 返回列名/类型/可空/默认值 |

原始只读 SqlTool（保留）：
- query(sql)  (默认数据源 SELECT)
- listAllTablesName()
- getTableSchema(tableName)

## 使用示例（伪 JSON 调用）
1. 创建连接
```json
{
  "tool": "createConnection",
  "args": {"host":"127.0.0.1","port":3306,"database":"demo_db","username":"u","password":"p","params":"useSSL=false"}
}
```
响应：`{"connectionId":"<uuid>","url":"jdbc:mysql://..."}`

2. 执行查询
```json
{"tool":"queryWithConnection","args":{"connectionId":"<uuid>","sql":"SELECT * FROM person"}}
```

3. 写操作（已在白名单）
```json
{"tool":"executeUpdateWithConnection","args":{"connectionId":"<uuid>","sql":"INSERT INTO person(name) VALUES('Alice')"}}
```

4. 管理白名单
```json
{"tool":"addAllowedWriteCommand","args":{"keyword":"rename"}}
```

## 安全注意

- queryWithConnection 严格限制首关键字为 SELECT
- executeUpdateWithConnection 仅允许白名单关键字（大小写不敏感）
- 动态白名单修改需在调用链中谨慎使用，避免扩大风险面
- 不对 SQL 做语义解析，请在上游模型提示中明确限制输出

## 连接池策略 (Hikari)
默认：
- maxPoolSize=5, minIdle=1
- connectionTimeout=10s (可调)
- idleTimeout=5m (可调)
- maxLifetime=30m (可调)
- validationTimeout=5s

## 服务发现与端点
- Nacos 注册名: mcp-mysql-server
- SSE 消息端点: /mcp/message
- SSE 事件流: /sse
- 健康检查: /actuator/health

## 测试
需已安装 Docker：
```bash
mvn test
```
Testcontainers 自动启动临时 mysql:8.0；测试涵盖：
- 动态连接创建/列表/关闭
- SELECT 查询与 schema 获取
- 写操作白名单校验与 DDL/DML 执行
- 白名单动态增删
- 高级连接超时参数

## 常见问题
1. 连接失败：检查 host/port 及防火墙；确认账号权限。
2. 写操作被拒：首关键字不在白名单，使用 addAllowedWriteCommand 添加。
3. 长时间空闲断开：调整 idleTimeout / maxLifetime 参数或客户端保持心跳。

## 许可证
MIT (示例)
