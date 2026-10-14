package fruition.access.cleanup;

import fruition.access.workspace.service.AiInternalClient;
import fruition.access.workspace.service.DocumentInternalClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 회원 탈퇴가 남긴 데이터 파기 요청을 document에 보낸 뒤, 성공하면 이어서 ai-svc에 보낸다. 둘 다 성공하면 요청을 지우고, 실패하면 시도 횟수에 따라
 * 간격을 늘려 가며 순서 전체(document → ai)를 다시 시도한다. 두 파기 API 모두 같은 요청을 다시 받아도 결과가 같다.
 * document가 미발행 AI 명령을 먼저 지우므로 ai 파기 뒤에 남는 명령이 없다. 요청 행을 {@code FOR UPDATE SKIP LOCKED}로 잡아 한 replica만 처리한다.
 */
@Component
public class DataPurgeRequestJob {

    private static final Logger log = LoggerFactory.getLogger(DataPurgeRequestJob.class);
    private static final int BATCH_SIZE = 20;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final DocumentInternalClient documentClient;
    private final AiInternalClient aiClient;

    public DataPurgeRequestJob(JdbcTemplate jdbc, TransactionTemplate transactionTemplate,
                               DocumentInternalClient documentClient, AiInternalClient aiClient) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
        this.documentClient = documentClient;
        this.aiClient = aiClient;
    }

    @Scheduled(fixedDelayString = "${app.purge.retry-delay-ms:60000}")
    public void runScheduled() {
        run(Instant.now());
    }

    public void run(Instant now) {
        List<Request> due = jdbc.query(
                "SELECT kind, target_id FROM data_purge_requests WHERE next_attempt_at <= ? ORDER BY next_attempt_at LIMIT ?",
                (rs, n) -> new Request(rs.getString("kind"), rs.getString("target_id")),
                Timestamp.from(now), BATCH_SIZE);
        for (Request request : due) {
            try {
                transactionTemplate.executeWithoutResult(status -> process(request, now));
            } catch (RuntimeException e) {
                log.warn("[데이터 파기 요청 실패, 다시 시도] kind={} targetId={}", request.kind(), request.targetId(), e);
                transactionTemplate.executeWithoutResult(status -> recordFailure(request, now, e));
            }
        }
    }

    private void process(Request request, Instant now) {
        List<Integer> locked = jdbc.queryForList("""
                SELECT 1 FROM data_purge_requests WHERE kind = ? AND target_id = ? AND next_attempt_at <= ?
                FOR UPDATE SKIP LOCKED
                """, Integer.class, request.kind(), request.targetId(), Timestamp.from(now));
        if (locked.isEmpty()) {
            return;
        }
        if ("user".equals(request.kind())) {
            documentClient.purgeUser(request.targetId());
            aiClient.purgeUser(request.targetId());
        } else {
            documentClient.purgeWorkspace(request.targetId());
            aiClient.purgeWorkspace(request.targetId());
            jdbc.update("DELETE FROM workspaces WHERE id = ?", request.targetId());
        }
        jdbc.update("DELETE FROM data_purge_requests WHERE kind = ? AND target_id = ?",
                request.kind(), request.targetId());
        log.info("[데이터 파기 완료] kind={} targetId={}", request.kind(), request.targetId());
    }

    /** 다음 시도는 1분에서 시작해 실패할 때마다 두 배로 늘리고 6시간에서 멈춘다. */
    private void recordFailure(Request request, Instant now, RuntimeException error) {
        String message = String.valueOf(error.getMessage());
        jdbc.update("""
                UPDATE data_purge_requests
                SET attempts = attempts + 1,
                    next_attempt_at = CAST(? AS timestamptz) + LEAST(interval '1 minute' * power(2, LEAST(attempts, 9)), interval '6 hours'),
                    last_error = ?
                WHERE kind = ? AND target_id = ?
                """, Timestamp.from(now), message.length() > 1000 ? message.substring(0, 1000) : message,
                request.kind(), request.targetId());
    }

    private record Request(String kind, String targetId) {}
}
