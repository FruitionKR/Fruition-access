package fruition.access.cleanup;

import fruition.TestcontainersConfiguration;
import fruition.access.user.domain.User;
import fruition.access.user.repository.UserRepository;
import fruition.access.workspace.domain.Workspace;
import fruition.access.workspace.domain.WorkspaceMember;
import fruition.access.workspace.domain.WorkspaceRole;
import fruition.access.workspace.repository.WorkspaceMemberRepository;
import fruition.access.workspace.repository.WorkspaceRepository;
import fruition.access.workspace.service.DocumentInternalClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClientException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 기한이 지난 기록과 휴지통 워크스페이스만 지워지는지 실제 Postgres FK·트리거로 확인한다. */
@SpringBootTest(properties = "app.cleanup.delay-ms=3600000")
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ExpiredRecordCleanupJobIntegrationTest {

    @Autowired ExpiredRecordCleanupJob job;
    @Autowired UserRepository users;
    @Autowired WorkspaceRepository workspaces;
    @Autowired WorkspaceMemberRepository members;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean DocumentInternalClient documentClient;

    /** 앱의 스케줄 실행은 실제 현재 시각을 쓴다. 미래 시각을 기준으로 삼아 테스트 행을 그 실행과 겹치지 않게 한다. */
    final Instant now = Instant.now().plus(Duration.ofDays(365));

    @Test
    void deletesOnlyRecordsPastRetention() {
        String user = user();
        String workspace = workspaces.saveAndFlush(new Workspace(UUID.randomUUID().toString(), "정리")).getId();

        String oldCode = verification(ago(8), null);
        String liveToken = verification(ago(8), ago(1));
        long expiredToken = refreshToken(user, ago(8), null);
        long revokedToken = refreshToken(user, later(5), ago(8));
        long recentlyRevoked = refreshToken(user, later(5), ago(1));
        long activeToken = refreshToken(user, later(5), null);
        String accepted = invitation(workspace, user, later(1), ago(31), null);
        String expired = invitation(workspace, user, ago(31), null, null);
        String pending = invitation(workspace, user, later(1), null, null);
        String recentlyRevokedInvitation = invitation(workspace, user, later(1), null, ago(10));
        UUID staleIdempotency = idempotency(user, ago(0).toInstant().minusSeconds(60));
        UUID liveIdempotency = idempotency(user, later(1).toInstant());

        job.run(now);

        assertThat(exists("email_verifications", oldCode)).isFalse();
        assertThat(exists("email_verifications", liveToken)).isTrue();
        assertThat(exists("user_refresh_tokens", expiredToken)).isFalse();
        assertThat(exists("user_refresh_tokens", revokedToken)).isFalse();
        assertThat(exists("user_refresh_tokens", recentlyRevoked)).isTrue();
        assertThat(exists("user_refresh_tokens", activeToken)).isTrue();
        assertThat(exists("workspace_invitations", accepted)).isFalse();
        assertThat(exists("workspace_invitations", expired)).isFalse();
        assertThat(exists("workspace_invitations", pending)).isTrue();
        assertThat(exists("workspace_invitations", recentlyRevokedInvitation)).isTrue();
        assertThat(exists("idempotency_records", staleIdempotency)).isFalse();
        assertThat(exists("idempotency_records", liveIdempotency)).isTrue();
    }

    @Test
    void purgesTrashedWorkspaceAfterDocumentAndKeepsFailedOnesForRetry() {
        String owner = user();
        String expired = trashedWorkspace(owner, ago(31));
        String failing = trashedWorkspace(owner, ago(31));
        String recent = trashedWorkspace(owner, ago(29));
        doThrow(new RestClientException("document 다운")).when(documentClient).purgeWorkspace(failing);

        job.run(now);

        verify(documentClient).purgeWorkspace(expired);
        verify(documentClient, never()).purgeWorkspace(recent);
        assertThat(exists("workspaces", expired)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_members WHERE workspace_id = ?",
                Integer.class, expired)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_membership_periods "
                + "WHERE workspace_id = ? AND left_at IS NOT NULL", Integer.class, expired)).isOne();
        assertThat(exists("workspaces", failing)).isTrue();
        assertThat(exists("workspaces", recent)).isTrue();
    }

    @Test
    void purgesAllBatchesWithoutRetryingFailedWorkspaceInSameRun() {
        String owner = user();
        // 가장 오래된 실패 건이 첫 배치 맨 앞에 온다. 같은 시각의 나머지는 배치 크기(100)를 넘긴다.
        String failing = trashedWorkspace(owner, ago(40));
        doThrow(new RestClientException("document 다운")).when(documentClient).purgeWorkspace(failing);
        List<String> rest = IntStream.range(0, 101).mapToObj(i -> trashedWorkspace(owner, ago(31))).toList();

        job.run(now);

        verify(documentClient, times(1)).purgeWorkspace(failing);
        assertThat(exists("workspaces", failing)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspaces WHERE id = ANY(?)", Integer.class,
                (Object) rest.toArray(String[]::new))).isZero();
    }

    private String user() {
        String id = UUID.randomUUID().toString();
        users.saveAndFlush(new User(id, id + "@example.com", "local", "사용자", null));
        return id;
    }

    private String trashedWorkspace(String owner, Timestamp deletedAt) {
        Workspace workspace = workspaces.saveAndFlush(new Workspace(UUID.randomUUID().toString(), "휴지통"));
        members.saveAndFlush(new WorkspaceMember(workspace, users.getReferenceById(owner), WorkspaceRole.OWNER));
        jdbc.update("UPDATE workspaces SET deleted_at = ?, deleted_by = ? WHERE id = ?", deletedAt, owner, workspace.getId());
        return workspace.getId();
    }

    private String verification(Timestamp codeExpiresAt, Timestamp tokenExpiresAt) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO email_verifications(id, email, purpose, code_hash, code_expires_at, token_expires_at)
                VALUES (?, ?, 'SIGNUP', 'hash', ?, ?)
                """, id, id + "@example.com", codeExpiresAt, tokenExpiresAt);
        return id;
    }

    private long refreshToken(String user, Timestamp expiresAt, Timestamp revokedAt) {
        return jdbc.queryForObject("""
                INSERT INTO user_refresh_tokens(created_at, expires_at, revoked_at, token_hash, user_id)
                VALUES (now(), ?, ?, ?, ?) RETURNING id
                """, Long.class, expiresAt, revokedAt, UUID.randomUUID().toString(), user);
    }

    private String invitation(String workspace, String user, Timestamp expiresAt, Timestamp acceptedAt, Timestamp revokedAt) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO workspace_invitations(id, workspace_id, email, role, token_hash, expires_at, invited_by,
                    accepted_at, accepted_by, revoked_at)
                VALUES (?, ?, ?, 'MEMBER', ?, ?, ?, ?, ?, ?)
                """, id, workspace, id + "@example.com", UUID.randomUUID().toString(), expiresAt, user,
                acceptedAt, acceptedAt == null ? null : user, revokedAt);
        return id;
    }

    private UUID idempotency(String user, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO idempotency_records(id, user_id, endpoint_scope, idempotency_key, request_hash,
                    response_status, created_at, expires_at, status)
                VALUES (?, ?, 'TEST', ?, 'hash', 200, now(), ?, 'COMPLETED')
                """, id, user, id.toString(), Timestamp.from(expiresAt));
        return id;
    }

    private boolean exists(String table, Object id) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id = ?", Integer.class, id) == 1;
    }

    private Timestamp ago(int days) {
        return Timestamp.from(now.minus(Duration.ofDays(days)));
    }

    private Timestamp later(int days) {
        return Timestamp.from(now.plus(Duration.ofDays(days)));
    }
}
