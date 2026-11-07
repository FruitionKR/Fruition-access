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

/** V30이 옛 auto_ingest_on_upload를 PDF 변환 설정으로 옮기고 Markdown 업로드는 모두 켜는지 실제 Flyway 실행으로 확인한다. */
@Testcontainers
class SplitAutoIngestSettingsMigrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void copiesLegacyValueToPdfAndEnablesMarkdownForAll() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        flyway(dataSource, "29").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        insert(jdbc, "ws_on", true);
        insert(jdbc, "ws_off", false);

        flyway(dataSource, "30").migrate();

        assertThat(settings(jdbc, "ws_on")).containsEntry("auto_ingest_markdown_on_upload", true)
                .containsEntry("auto_ingest_pdf_on_convert", true)
                .containsEntry("auto_ingest_on_upload", true);
        assertThat(settings(jdbc, "ws_off")).containsEntry("auto_ingest_markdown_on_upload", true)
                .containsEntry("auto_ingest_pdf_on_convert", false)
                .containsEntry("auto_ingest_on_upload", false);

        // 새 행의 DB 기본값도 계약과 같다(Markdown 켬, PDF 끔).
        jdbc.update("INSERT INTO workspaces (id, name, created_at, updated_at, ingest_lint_provider, ingest_lint_model) "
                + "VALUES ('ws_new', 'ws_new', now(), now(), 'gemini', 'gemini-3.5-flash-lite')");
        assertThat(settings(jdbc, "ws_new")).containsEntry("auto_ingest_markdown_on_upload", true)
                .containsEntry("auto_ingest_pdf_on_convert", false);
    }

    private static Flyway flyway(DriverManagerDataSource dataSource, String target) {
        return Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration").target(target).load();
    }

    private static void insert(JdbcTemplate jdbc, String id, boolean autoIngestOnUpload) {
        jdbc.update("INSERT INTO workspaces (id, name, created_at, updated_at, ingest_lint_provider, ingest_lint_model, "
                + "auto_ingest_on_upload) VALUES (?, ?, now(), now(), 'gemini', 'gemini-3.5-flash-lite', ?)",
                id, id, autoIngestOnUpload);
    }

    private static Map<String, Object> settings(JdbcTemplate jdbc, String id) {
        return jdbc.queryForMap("SELECT auto_ingest_on_upload, auto_ingest_markdown_on_upload, auto_ingest_pdf_on_convert "
                + "FROM workspaces WHERE id = ?", id);
    }
}
