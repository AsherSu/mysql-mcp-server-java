package cn.ashersu.mcp.mysql;

import cn.ashersu.mcp.mysql.exception.MysqlMcpException;
import cn.ashersu.mcp.mysql.service.MysqlToolService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assertions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Arrays;

@Testcontainers
class MysqlToolServiceTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("demo_db")
            .withUsername("testuser")
            .withPassword("testpass");

    // 工具实例：每个测试用新的，保证白名单/开关互不影响
    private MysqlToolService tool;

    @BeforeEach
    void ensureRunning() {
        Assertions.assertTrue(mysql.isRunning());
        // 使用新的构造函数，启用写操作并设置白名单
        tool = new MysqlToolService(true, Arrays.asList("insert", "update", "delete", "create", "drop", "alter", "truncate", "replace"));
    }

    @Test
    @DisplayName("创建连接并列出")
    void testCreateConnectionAndList() {
        Map<String, String> info = tool.createConnection(mysql.getHost(), mysql.getFirstMappedPort(), mysql.getDatabaseName(), mysql.getUsername(), mysql.getPassword(), null);
        Assertions.assertNotNull(info.get("connectionId"));
        Assertions.assertTrue(info.get("url").contains(mysql.getDatabaseName()));
        List<Map<String, String>> list = tool.listConnections();
        Assertions.assertFalse(list.isEmpty());
    }

    @Test
    @DisplayName("查询数据与获取表结构")
    void testQueryAndSchema() throws Exception {
        Map<String, String> info = tool.createConnection(mysql.getHost(), mysql.getFirstMappedPort(), mysql.getDatabaseName(), mysql.getUsername(), mysql.getPassword(), null);
        String cid = info.get("connectionId");
        String url = info.get("url");
        try (Connection c = DriverManager.getConnection(url, mysql.getUsername(), mysql.getPassword());
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS person (id INT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(64))");
            st.executeUpdate("INSERT INTO person(name) VALUES('Alice'),('Bob')");
        }
        List<Map<String, Object>> rows = tool.queryWithConnection(cid, "select * from person order by id");
        Assertions.assertEquals(2, rows.size());
        List<Map<String, Object>> schema = tool.getTableSchema(cid, "person");
        Assertions.assertTrue(schema.stream().anyMatch(m -> "id".equalsIgnoreCase(String.valueOf(m.get("COLUMN_NAME")))));
        String tables = tool.listAllTablesName(cid);
        Assertions.assertTrue(tables.contains("person"));
    }

    @Test
    @DisplayName("关闭连接后不可再使用")
    void testCloseConnection() {
        Map<String, String> info = tool.createConnection(mysql.getHost(), mysql.getFirstMappedPort(), mysql.getDatabaseName(), mysql.getUsername(), mysql.getPassword(), null);
        String cid = info.get("connectionId");
        Assertions.assertTrue(tool.closeConnection(cid));
        Assertions.assertThrows(MysqlMcpException.class, () -> tool.queryWithConnection(cid, "select 1"));
    }

    @Test
    @DisplayName("未知 connectionId 抛异常")
    void testUnknownConnection() {
        Assertions.assertThrows(MysqlMcpException.class, () -> tool.queryWithConnection("no-such", "select 1"));
    }

    @Test
    @DisplayName("executeUpdateWithConnection 支持 DDL 与 DML")
    void testExecuteUpdateAllowed() {
        tool.enableWriteOperations();
        Map<String, String> info = tool.createConnection(mysql.getHost(), mysql.getFirstMappedPort(), mysql.getDatabaseName(), mysql.getUsername(), mysql.getPassword(), null);
        String cid = info.get("connectionId");
        // DDL
        int ddl = tool.executeUpdateWithConnection(cid, "CREATE TABLE IF NOT EXISTS t_whitelist(id INT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(32))");
        // MySQL 返回 0 对于 CREATE TABLE IF NOT EXISTS，验证不抛异常即可
        Assertions.assertTrue(ddl >= 0);
        // DML
        int insert = tool.executeUpdateWithConnection(cid, "INSERT INTO t_whitelist(name) VALUES('A'),('B')");
        Assertions.assertEquals(2, insert);
        var rows = tool.queryWithConnection(cid, "SELECT * FROM t_whitelist ORDER BY id");
        Assertions.assertEquals(2, rows.size());
    }

    @Test
    @DisplayName("白名单增删生效")
    void testWhitelistManagement() {
        tool.enableWriteOperations();
        Map<String, String> info = tool.createConnection(mysql.getHost(), mysql.getFirstMappedPort(), mysql.getDatabaseName(), mysql.getUsername(), mysql.getPassword(), null);
        String cid = info.get("connectionId");
        // 添加自定义关键字（示例: REPLACE 已存在，换一个自定义如 MERGE 不被 MySQL 支持但用于测试列表）
        boolean added = tool.addAllowedWriteCommand("merge");
        Assertions.assertTrue(added);
        Assertions.assertTrue(tool.listWriteWhitelist().contains("merge"));
        // 移除 insert
        boolean removed = tool.removeAllowedWriteCommand("insert");
        Assertions.assertTrue(removed);
        // 再执行 insert 应失败
        Assertions.assertThrows(MysqlMcpException.class, () -> tool.executeUpdateWithConnection(cid, "INSERT INTO not_exist(name) VALUES('x')"));
    }

    @Test
    @DisplayName("高级连接创建支持自定义超时")
    void testCreateConnectionAdvanced() {
        Map<String,String> info = tool.createConnectionAdvanced(mysql.getHost(), mysql.getFirstMappedPort(), mysql.getDatabaseName(), mysql.getUsername(), mysql.getPassword(), null,
                2000L, 10000L, 60000L);
        Assertions.assertNotNull(info.get("connectionId"));
    }

    @Test
    @DisplayName("重复添加已存在白名单关键字返回 false")
    void testAddDuplicateWhitelistKeyword() {
        boolean firstAdd = tool.addAllowedWriteCommand("customcmd");
        Assertions.assertTrue(firstAdd);
        boolean duplicateAdd = tool.addAllowedWriteCommand("customcmd");
        Assertions.assertFalse(duplicateAdd);
    }

    @Test
    @DisplayName("移除不存在的白名单关键字返回 false")
    void testRemoveNotExistingWhitelistKeyword() {
        Assertions.assertFalse(tool.removeAllowedWriteCommand("not_in_whitelist_abc"));
    }

    @Test
    @DisplayName("移除 update 后更新语句被拒绝")
    void testRemoveUpdateThenBlockUpdate() {
        tool.enableWriteOperations();
        // 默认包含 update
        Assertions.assertTrue(tool.removeAllowedWriteCommand("update"));
        Map<String, String> info = tool.createConnection(mysql.getHost(), mysql.getFirstMappedPort(), mysql.getDatabaseName(), mysql.getUsername(), mysql.getPassword(), null);
        String cid = info.get("connectionId");
        // 构造表
        tool.executeUpdateWithConnection(cid, "CREATE TABLE IF NOT EXISTS t_block (id INT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(16))");
        // update 应因不在白名单抛异常
        Assertions.assertThrows(MysqlMcpException.class, () -> tool.executeUpdateWithConnection(cid, "UPDATE t_block SET name='X' WHERE id=1"));
    }

    @Test
    @DisplayName("关闭连接两次第二次返回 false")
    void testCloseConnectionTwice() {
        Map<String, String> info = tool.createConnection(mysql.getHost(), mysql.getFirstMappedPort(), mysql.getDatabaseName(), mysql.getUsername(), mysql.getPassword(), null);
        String cid = info.get("connectionId");
        Assertions.assertTrue(tool.closeConnection(cid));
        Assertions.assertFalse(tool.closeConnection(cid));
    }

    @Test
    @DisplayName("允许前置空格的 SELECT")
    void testSelectWithLeadingSpaces() {
        tool.enableWriteOperations();
        Map<String, String> info = tool.createConnection(mysql.getHost(), mysql.getFirstMappedPort(), mysql.getDatabaseName(), mysql.getUsername(), mysql.getPassword(), null);
        String cid = info.get("connectionId");
        tool.executeUpdateWithConnection(cid, "CREATE TABLE IF NOT EXISTS t_space (id INT PRIMARY KEY AUTO_INCREMENT, v INT)");
        tool.executeUpdateWithConnection(cid, "INSERT INTO t_space(v) VALUES(1)");
        var rows = tool.queryWithConnection(cid, "   SELECT * FROM t_space");
        Assertions.assertEquals(1, rows.size());
    }

    @Test
    @DisplayName("全局关闭写操作后写入被拒绝, 重新开启后成功")
    void testDisableThenEnableWriteOperations() {
        tool.disableWriteOperations();
        // 初始应为关闭状态（配置默认 false）
        Assertions.assertFalse(tool.isWriteEnabled());
        // 第一次关闭返回 false（状态未变化）
        Assertions.assertFalse(tool.disableWriteOperations());
        Map<String, String> info = tool.createConnection(mysql.getHost(), mysql.getFirstMappedPort(), mysql.getDatabaseName(), mysql.getUsername(), mysql.getPassword(), null);
        String cid = info.get("connectionId");
        // 关闭状态下写操作被拒绝
        Assertions.assertThrows(MysqlMcpException.class, () -> tool.executeUpdateWithConnection(cid, "CREATE TABLE t_disabled(id INT)"));
        // 开启写操作
        Assertions.assertTrue(tool.enableWriteOperations());
        Assertions.assertTrue(tool.isWriteEnabled());
        // 再次 enable 返回 false
        Assertions.assertFalse(tool.enableWriteOperations());
        int ddl = tool.executeUpdateWithConnection(cid, "CREATE TABLE IF NOT EXISTS t_disabled(id INT)");
        Assertions.assertTrue(ddl >= 0);
    }

    @Test
    @DisplayName("写操作审计日志记录与清理")
    void testWriteAuditLoggingAndClear() {
        tool.enableWriteOperations();
        Map<String,String> info = tool.createConnection(mysql.getHost(), mysql.getFirstMappedPort(), mysql.getDatabaseName(), mysql.getUsername(), mysql.getPassword(), null);
        String cid = info.get("connectionId");
        tool.executeUpdateWithConnection(cid, "CREATE TABLE IF NOT EXISTS t_audit(id INT PRIMARY KEY AUTO_INCREMENT, v INT)");
        tool.executeUpdateWithConnection(cid, "INSERT INTO t_audit(v) VALUES(1),(2),(3)");
        var auditList = tool.listWriteAudit(10);
        Assertions.assertFalse(auditList.isEmpty(), "Audit list should not be empty");
        // 至少包含一个 INSERT 或 CREATE
        Assertions.assertTrue(auditList.stream().anyMatch(e -> "insert".equals(e.get("verb")) || "create".equals(e.get("verb"))), "Should contain create/insert verbs");
        // affectedRows 字段存在
        Assertions.assertTrue(auditList.stream().anyMatch(e -> e.containsKey("affectedRows")));
        // 限制1时只返回1条
        Assertions.assertEquals(1, tool.listWriteAudit(1).size());
        int cleared = tool.clearWriteAudit();
        Assertions.assertTrue(cleared >= auditList.size()-1); // 允许有新并发
        Assertions.assertTrue(tool.listWriteAudit(5).isEmpty());
    }

    @Test
    @DisplayName("结果集行数与字段长度截断")
    void testResultLimitAndTruncation() {
        tool.enableWriteOperations();
        Map<String,String> info = tool.createConnection(mysql.getHost(), mysql.getFirstMappedPort(), mysql.getDatabaseName(), mysql.getUsername(), mysql.getPassword(), null);
        String cid = info.get("connectionId");
        tool.executeUpdateWithConnection(cid, "CREATE TABLE IF NOT EXISTS t_limit(id INT PRIMARY KEY AUTO_INCREMENT, txt VARCHAR(512))");
        String longText = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdefghijklmnopqrstuvwxyz".repeat(5); // 长字符串
        tool.executeUpdateWithConnection(cid, "INSERT INTO t_limit(txt) VALUES('" + longText + "'),('" + longText + "')");
        int prevRows = tool.setMaxQueryRows(1);
        int prevFieldLen = tool.setMaxFieldLength(20);
        try {
            var cfg = tool.getResultLimitConfig();
            Assertions.assertEquals(1, cfg.get("maxQueryRows"));
            Assertions.assertEquals(20, cfg.get("maxFieldLength"));
            var rows = tool.queryWithConnection(cid, "SELECT id, txt FROM t_limit ORDER BY id");
            Assertions.assertEquals(1, rows.size(), "Should be truncated to 1 row");
            Object val = rows.get(0).get("txt");
            Assertions.assertTrue(val instanceof String);
            String s = (String) val;
            Assertions.assertTrue(s.length() <= 20 + 25, "Truncated string should not exceed limit + suffix");
            Assertions.assertTrue(java.util.regex.Pattern.compile(".*\\(truncated,len=\\d+\\)$").matcher(s).matches(), "Should have truncated suffix");
        } finally {
            tool.setMaxQueryRows(prevRows);
            tool.setMaxFieldLength(prevFieldLen);
        }
    }
}