package fruition.access.mcp.oauth;

import fruition.access.AccessApplication;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** V39가 출시 대상 MCP 클라이언트를 허용하되, 운영자가 먼저 만든 정책 행은 덮어쓰지 않는지 확인한다. */
@Testcontainers
class AllowLaunchMcpClientsMigrationTest {
    private static final List<String> LAUNCH_CLIENTS = List.of(
            "https://claude.ai/oauth/mcp-oauth-client-metadata",
            "https://claude.ai/oauth/claude-code-client-metadata",
            "https://chatgpt.com/oauth/codex/client.json",
            "https://vscode.dev/oauth/client-metadata.json",
            "https://github.com/copilot/cli/client-metadata.json",
            "https://zed.dev/oauth/client-metadata.json",
            "https://antigravity.google/oauth/client-metadata.json");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void freshDatabaseAllowsEveryLaunchClient() {
        DriverManagerDataSource dataSource = database("fresh_v39");
        AccessApplication.migrationFlyway().dataSource(dataSource).load().migrate();

        Map<String, Boolean> policies = policies(dataSource);
        assertThat(policies).containsOnlyKeys(LAUNCH_CLIENTS);
        assertThat(policies.values()).containsOnly(true);
    }

    @Test
    void existingOperatorDecisionIsKept() {
        DriverManagerDataSource dataSource = database("existing_v39");
        AccessApplication.migrationFlyway().dataSource(dataSource).target("38").load().migrate();
        new JdbcTemplate(dataSource).update(
                "INSERT INTO mcp_client_policies (client_id, enabled) VALUES (?, false)", LAUNCH_CLIENTS.getFirst());

        Flyway flyway = AccessApplication.migrationFlyway().dataSource(dataSource).load();
        assertThat(flyway.migrate().success).isTrue();

        assertThat(policies(dataSource)).containsEntry(LAUNCH_CLIENTS.getFirst(), false)
                .hasSize(LAUNCH_CLIENTS.size());
    }

    private static Map<String, Boolean> policies(DriverManagerDataSource dataSource) {
        Map<String, Boolean> result = new HashMap<>();
        new JdbcTemplate(dataSource).query("SELECT client_id, enabled FROM mcp_client_policies",
                rs -> { result.put(rs.getString("client_id"), rs.getBoolean("enabled")); });
        return result;
    }

    private static DriverManagerDataSource database(String name) {
        new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword())).execute("CREATE DATABASE " + name);
        String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + name);
        return new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
    }
}
