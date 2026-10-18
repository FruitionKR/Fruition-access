package fruition.access.workspace;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** V27이 허용 목록 밖 ingest_lint 값을 계약대로 옮기는지 실제 Flyway 실행으로 확인한다. */
@Testcontainers
class RestrictIngestLintModelsMigrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void movesValuesOutsideAllowedListToCheapModels() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        flyway(dataSource, "26").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        insert(jdbc, "ws_openai_keep", "openai", "gpt-6-luna");
        insert(jdbc, "ws_openai_old", "openai", "gpt-6.1-sol");
        insert(jdbc, "ws_gemini_keep", "gemini", "gemini-3.5-flash-lite");
        insert(jdbc, "ws_gemini_old", "gemini", "gemini-3.8-flash");
        insert(jdbc, "ws_claude_haiku", "claude", "claude-haiku-4-5-20251001");
        insert(jdbc, "ws_claude_sonnet", "claude", "claude-sonnet-5");

        flyway(dataSource, "27").migrate();

        assertThat(selection(jdbc, "ws_openai_keep")).isEqualTo("openai/gpt-6-luna");
        assertThat(selection(jdbc, "ws_openai_old")).isEqualTo("openai/gpt-6-luna");
        assertThat(selection(jdbc, "ws_gemini_keep")).isEqualTo("gemini/gemini-3.5-flash-lite");
        assertThat(selection(jdbc, "ws_gemini_old")).isEqualTo("gemini/gemini-3.5-flash-lite");
        assertThat(selection(jdbc, "ws_claude_haiku")).isEqualTo("gemini/gemini-3.5-flash-lite");
        assertThat(selection(jdbc, "ws_claude_sonnet")).isEqualTo("gemini/gemini-3.5-flash-lite");
    }

    private static Flyway flyway(DriverManagerDataSource dataSource, String target) {
        return Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration").target(target).load();
    }

    private static void insert(JdbcTemplate jdbc, String id, String provider, String model) {
        jdbc.update("INSERT INTO workspaces (id, name, created_at, updated_at, ingest_lint_provider, ingest_lint_model) "
                + "VALUES (?, ?, now(), now(), ?, ?)", id, id, provider, model);
    }

    private static String selection(JdbcTemplate jdbc, String id) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT ingest_lint_provider, ingest_lint_model FROM workspaces WHERE id = ?", id);
        return row.get("ingest_lint_provider") + "/" + row.get("ingest_lint_model");
    }
}
