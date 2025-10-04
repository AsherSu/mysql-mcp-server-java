package cn.ashersu.mcp.mysql.config;

import cn.ashersu.mcp.mysql.audit.MysqlAuditService;
import cn.ashersu.mcp.mysql.manager.MysqlConnectionManager;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Root configuration enabling MySQL tool properties and registering manager/audit beans.
 */
@Configuration
@EnableConfigurationProperties(MysqlToolsProperties.class)
public class MysqlMcpServerConfiguration {

    @Bean
    public MysqlConnectionManager mysqlConnectionManager() {
        return new MysqlConnectionManager();
    }

    @Bean
    public MysqlAuditService mysqlWriteAudit(MysqlToolsProperties props) {
        return new MysqlAuditService(props.getAudit().isEnabled());
    }
}