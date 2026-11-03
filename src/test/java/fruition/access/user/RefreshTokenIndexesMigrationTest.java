package fruition.access.user;

import fruition.access.AccessApplication;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** V31이 트랜잭션 밖에서 CONCURRENTLY 인덱스를 만들고, 다시 실행해도 아무것도 바꾸지 않는지 확인한다. */
@Testcontainers
class RefreshTokenIndexesMigrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void createsValidLookupIndexesAndRerunIsNoOp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway flyway = AccessApplication.migrationFlyway().dataSource(dataSource).load();

        MigrateResult first = flyway.migrate();
        MigrateResult second = flyway.migrate();

        assertThat(first.success).isTrue();
        // 뒤 버전이 늘어도 V31이 적용됐는지만 본다.
        assertThat(Integer.parseInt(first.targetSchemaVersion)).isGreaterThanOrEqualTo(31);
        assertThat(second.migrationsExecuted).isZero();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<String> definitions = jdbc.queryForList("""
                SELECT pg_get_indexdef(i.indexrelid)
                FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
                WHERE c.relname IN ('idx_user_refresh_tokens_token_hash', 'idx_user_refresh_tokens_user_active')
                  AND i.indisvalid
                ORDER BY c.relname
                """, String.class);
        assertThat(definitions).containsExactly(
                "CREATE UNIQUE INDEX idx_user_refresh_tokens_token_hash ON public.user_refresh_tokens"
                        + " USING btree (token_hash)",
                "CREATE INDEX idx_user_refresh_tokens_user_active ON public.user_refresh_tokens"
                        + " USING btree (user_id) WHERE (revoked_at IS NULL)");
    }
}
