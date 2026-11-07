package fruition.access;

import java.util.Arrays;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import fruition.shared.util.OpenApiConfig;

/**
 * 로그인·OAuth·세션·워크스페이스를 담당하는 access 앱.
 * 공유 모듈에서는 JWT(발급·검증)와 Idempotency, 요청 로깅, 클라이언트 주소 필터, 요청 언어 선택만 스캔한다
 * (fruition.shared.util 전체를 스캔하면 document 전용 MinioConfig까지 끌려온다).
 * JPA 배선(@EntityScan·@EnableJpaRepositories)은 slice 테스트에 끌려가지 않도록 {@link JpaConfig}에 둔다.
 */
@SpringBootApplication(scanBasePackages = {
        "fruition.access",
        "fruition.shared.ai",
        "fruition.shared.logging",
        "fruition.shared.security",
        "fruition.shared.idempotency",
        "fruition.shared.web",
        "fruition.shared.i18n"
})
@Import(OpenApiConfig.class)
@EnableScheduling
public class AccessApplication {

    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--migrate-only")) {
            migrationFlyway()
                    .dataSource("jdbc:postgresql://" + requiredEnv("POSTGRES_HOST") + ":"
                                    + requiredEnv("POSTGRES_PORT") + "/" + requiredEnv("ACCESS_DB_NAME")
                                    + "?sslmode=" + System.getenv().getOrDefault("PGSSLMODE", "prefer")
                                    + "&sslrootcert=" + System.getenv().getOrDefault("PGSSLROOTCERT", ""),
                            requiredEnv("ACCESS_DB_MIGRATION_USER"), requiredEnv("ACCESS_DB_MIGRATION_PASSWORD"))
                    .load().migrate();
            return;
        }
        SpringApplication.run(AccessApplication.class, args);
    }

    /**
     * migration Job과 migration 테스트가 함께 쓰는 Flyway 설정. advisory lock을 트랜잭션 대신 세션에 잡는다.
     * 트랜잭션 lock은 migration 내내 열린 트랜잭션을 남겨, 그보다 늦게 시작한 CREATE INDEX CONCURRENTLY가
     * 그 트랜잭션이 끝나기를 영원히 기다린다(V31).
     */
    public static FluentConfiguration migrationFlyway() {
        return Flyway.configure()
                .locations("classpath:db/migration")
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"));
    }

    private static String requiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("필수 migration 설정 누락: " + key);
        }
        return value;
    }

}
