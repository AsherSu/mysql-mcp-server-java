package com.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for MySQL tools (dynamic connections, whitelist, audit).
 */
@ConfigurationProperties(prefix = "mcp.mysql.tools")
public class MysqlToolsProperties {

    private final NonQuery nonQuery = new NonQuery();
    private final Audit audit = new Audit();

    public NonQuery getNonQuery() { return nonQuery; }
    public Audit getAudit() { return audit; }

    public static class NonQuery {
        /** Enable whitelist enforcement for non-query commands */
        private boolean enabled = false;
        /** Allowed non-query command names (uppercase) */
        private List<String> whitelist = new ArrayList<>(List.of(
            "INSERT", "UPDATE", "DELETE", "CREATE", "DROP", "ALTER", "TRUNCATE", "REPLACE"
        ));

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getWhitelist() { return whitelist; }
        public void setWhitelist(List<String> whitelist) { this.whitelist = whitelist; }
    }

    public static class Audit {
        private boolean enabled = true;
        private int maxEntries = 1000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxEntries() { return maxEntries; }
        public void setMaxEntries(int maxEntries) { this.maxEntries = maxEntries; }
    }
}