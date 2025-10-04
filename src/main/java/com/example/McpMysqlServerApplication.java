package com.example;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Bean;

@EnableDiscoveryClient
@SpringBootApplication
public class McpMysqlServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(McpMysqlServerApplication.class, args);
	}

	/**
	 * 注册所有 @Tool 方法到 MCP Server (基于反射扫描 SqlTool)。
	 */
	@Bean
	public ToolCallbackProvider toolCallbackProvider(MultiConnectionSqlTool multiConnectionSqlTool) {
		return MethodToolCallbackProvider.builder()
				.toolObjects(multiConnectionSqlTool) // 可追加多个 service
				.build();
	}
}