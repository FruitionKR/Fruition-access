package fruition.access.cleanup;

import fruition.access.workspace.service.AuthzProjectionStore;
import fruition.access.workspace.service.AiInternalClient;
import fruition.access.workspace.service.DocumentInternalClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 만료된 인증·세션·초대·멱등 기록을 지우고, 휴지통 보관 기간이 지난 워크스페이스를 영구 삭제한다.
 *
 * <p>기록 정리는 조건부 DELETE라 여러 replica가 동시에 돌아도 결과가 같다. 워크스페이스는 행을
 * {@code FOR UPDATE SKIP LOCKED}로 잡은 채 document → ai 파기를 호출하므로 한 replica만 처리한다.
 * document 또는 ai 파기가 실패하면 행을 남겨 다음 실행에서 다시 시도한다.
 */
@Component
public class ExpiredRecordCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ExpiredRecordCleanupJob.class);
    private static final int WORKSPACE_BATCH_SIZE = 100;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final DocumentInternalClient documentClient;
    private final AiInternalClient aiClient;
    private final AuthzProjectionStore authzProjectionStore;
    private final Duration verificationRetention;
    private final Duration refreshTokenRetention;
    private final Duration invitationRetention;
    private final Duration workspaceTrashRetention;

    public ExpiredRecordCleanupJob(
            JdbcTemplate jdbc,
            TransactionTemplate transactionTemplate,
            DocumentInternalClient documentClient,
            AiInternalClient aiClient,
            AuthzProjectionStore authzProjectionStore,
            @Value("${app.cleanup.verification-retention:7d}") Duration verificationRetention,
            @Value("${app.cleanup.refresh-token-retention:7d}") Duration refreshTokenRetention,
            @Value("${app.cleanup.invitation-retention:30d}") Duration invitationRetention,
            @Value("${app.cleanup.workspace-trash-retention:30d}") Duration workspaceTrashRetention) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
        this.documentClient = documentClient;
        this.aiClient = aiClient;
        this.authzProjectionStore = authzProjectionStore;
        this.verificationRetention = verificationRetention;
        this.refreshTokenRetention = refreshTokenRetention;
        this.invitationRetention = invitationRetention;
        this.workspaceTrashRetention = workspaceTrashRetention;
    }

    @Scheduled(fixedDelayString = "${app.cleanup.delay-ms:86400000}")
    public void runScheduled() {
        run(Instant.now());
    }

    public void run(Instant now) {
        int verifications = jdbc.update("""
                DELETE FROM email_verifications
                WHERE GREATEST(code_expires_at, COALESCE(token_expires_at, code_expires_at)) < ?
                """, before(now, verificationRetention));
        int refreshTokens = jdbc.update(
                "DELETE FROM user_refresh_tokens WHERE expires_at < ? OR revoked_at < ?",
                before(now, refreshTokenRetention), before(now, refreshTokenRetention));
        // 초대는 수락·취소·만료 중 가장 먼저 일어난 때 끝난다.
        int invitations = jdbc.update("""
                DELETE FROM workspace_invitations
                WHERE LEAST(expires_at, COALESCE(accepted_at, expires_at), COALESCE(revoked_at, expires_at)) < ?
                """, before(now, invitationRetention));
        int idempotency = jdbc.update("DELETE FROM idempotency_records WHERE expires_at < ?", Timestamp.from(now));
        int workspaces = purgeTrashedWorkspaces(now);
        log.info("[만료 기록 정리] verifications={} refreshTokens={} invitations={} idempotency={} workspaces={}",
                verifications, refreshTokens, invitations, idempotency, workspaces);
    }

    /**
     * 대상이 없을 때까지 배치를 반복한다. (deleted_at, id) 순으로 지나온 자리 뒤만 조회하므로 실패하거나 건너뛴
     * 워크스페이스가 다음 배치의 앞을 막지 않고, 같은 실행에서 다시 잡히지도 않는다. 다음 실행에서 다시 시도한다.
     */
    private int purgeTrashedWorkspaces(Instant now) {
        Timestamp cutoff = before(now, workspaceTrashRetention);
        Candidate last = new Candidate("", new Timestamp(0));
        int purged = 0;
        while (true) {
            List<Candidate> candidates = jdbc.query("""
                    SELECT id, deleted_at FROM workspaces
                    WHERE deleted_at < ? AND (deleted_at, id) > (?, ?)
                    ORDER BY deleted_at, id LIMIT ?
                    """, (rs, n) -> new Candidate(rs.getString("id"), rs.getTimestamp("deleted_at")),
                    cutoff, last.deletedAt(), last.id(), WORKSPACE_BATCH_SIZE);
            if (candidates.isEmpty()) {
                return purged;
            }
            for (Candidate candidate : candidates) {
                try {
                    Boolean deleted = transactionTemplate.execute(status -> purgeWorkspace(candidate.id(), cutoff));
                    if (Boolean.TRUE.equals(deleted)) {
                        purged++;
                    }
                } catch (RuntimeException e) {
                    log.warn("[휴지통 워크스페이스 영구 삭제 실패, 다음 실행에서 다시 시도] workspaceId={}", candidate.id(), e);
                }
            }
            last = candidates.get(candidates.size() - 1);
        }
    }

    private record Candidate(String id, Timestamp deletedAt) {
    }

    /**
     * 그사이 복구됐거나 다른 replica가 잡은 워크스페이스는 건너뛴다. document, ai 순서로 지우고 행을 지운다.
     * 행을 지우면 멤버십·초대·아이콘은 CASCADE로 지워지고, 멤버십 기간 이력은 트리거가 left_at을 채운다.
     */
    private boolean purgeWorkspace(String workspaceId, Timestamp cutoff) {
        List<String> locked = jdbc.queryForList(
                "SELECT id FROM workspaces WHERE id = ? AND deleted_at < ? FOR UPDATE SKIP LOCKED",
                String.class, workspaceId, cutoff);
        if (locked.isEmpty()) {
            return false;
        }
        documentClient.purgeWorkspace(workspaceId);
        aiClient.purgeWorkspace(workspaceId);
        jdbc.update("DELETE FROM workspaces WHERE id = ?", workspaceId);
        authzProjectionStore.evictWorkspace(workspaceId);
        return true;
    }

    private static Timestamp before(Instant now, Duration retention) {
        return Timestamp.from(now.minus(retention));
    }
}
