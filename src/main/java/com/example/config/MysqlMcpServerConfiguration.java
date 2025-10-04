package com.example.config;

import com.example.audit.MysqlAuditService;
import com.example.manager.MysqlConnectionManager;
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