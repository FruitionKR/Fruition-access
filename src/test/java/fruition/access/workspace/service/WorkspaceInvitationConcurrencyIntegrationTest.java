package fruition.access.workspace.service;

import fruition.TestcontainersConfiguration;
import fruition.access.security.OpaqueTokens;
import fruition.access.user.domain.User;
import fruition.access.user.repository.UserRepository;
import fruition.access.workspace.domain.Workspace;
import fruition.access.workspace.domain.WorkspaceInvitation;
import fruition.access.workspace.domain.WorkspaceMember;
import fruition.access.workspace.domain.WorkspaceRole;
import fruition.access.workspace.exception.InvitationNotFoundException;
import fruition.access.workspace.repository.WorkspaceInvitationRepository;
import fruition.access.workspace.repository.WorkspaceMemberRepository;
import fruition.access.workspace.repository.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 같은 초대의 수락과 취소가 겹치면 먼저 커밋한 쪽만 성공한다.
 * 잠그지 않던 이전 구현은 둘 다 성공을 돌려주고 나중 커밋이 먼저 커밋한 상태를 덮어썼다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class WorkspaceInvitationConcurrencyIntegrationTest {
    @Autowired WorkspaceInvitationService invitations;
    @Autowired WorkspaceInvitationRepository invitationRows;
    @Autowired WorkspaceRepository workspaces;
    @Autowired WorkspaceMemberRepository members;
    @Autowired UserRepository users;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;

    @Test
    void revokeCommittedFirst_waitingAcceptIsRejected() throws Exception {
        Fixture f = fixture();

        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> accept = new TransactionTemplate(transactions).execute(status -> {
                invitations.revoke(f.owner, f.workspaceId, f.invitationId);
                Future<?> result = executor.submit(() -> invitations.accept(f.invitee, f.token));
                awaitLock();
                return result;
            });
            assertThatThrownBy(() -> accept.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(InvitationNotFoundException.class);

            assertThat(members.findActiveRole(f.workspaceId, f.invitee)).isEmpty();
            WorkspaceInvitation row = invitationRows.findById(f.invitationId).orElseThrow();
            assertThat(row.isRevoked()).isTrue();
            assertThat(row.isAccepted()).isFalse();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void acceptCommittedFirst_waitingRevokeIsRejected() throws Exception {
        Fixture f = fixture();

        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> revoke = new TransactionTemplate(transactions).execute(status -> {
                invitations.accept(f.invitee, f.token);
                Future<?> result = executor.submit(() -> invitations.revoke(f.owner, f.workspaceId, f.invitationId));
                awaitLock();
                return result;
            });
            assertThatThrownBy(() -> revoke.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(InvitationNotFoundException.class);

            assertThat(members.findActiveRole(f.workspaceId, f.invitee)).contains(WorkspaceRole.MEMBER);
            WorkspaceInvitation row = invitationRows.findById(f.invitationId).orElseThrow();
            assertThat(row.isAccepted()).isTrue();
            assertThat(row.isRevoked()).isFalse();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Fixture fixture() {
        String owner = user();
        String invitee = user();
        Workspace workspace = workspaces.saveAndFlush(new Workspace(UUID.randomUUID().toString(), "공간"));
        members.saveAndFlush(new WorkspaceMember(
                workspaces.getReferenceById(workspace.getId()), users.getReferenceById(owner), WorkspaceRole.OWNER));
        String token = UUID.randomUUID().toString();
        String invitationId = "inv_" + UUID.randomUUID().toString().replace("-", "");
        invitationRows.saveAndFlush(new WorkspaceInvitation(invitationId, workspace.getId(), invitee + "@example.com",
                WorkspaceRole.MEMBER, OpaqueTokens.sha256(token), Instant.now().plusSeconds(3600), owner));
        return new Fixture(owner, invitee, workspace.getId(), invitationId, token);
    }

    private String user() {
        String id = UUID.randomUUID().toString();
        users.saveAndFlush(new User(id, id + "@example.com", "local", "사용자", null));
        return id;
    }

    private void awaitLock() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            jdbc.execute("SELECT pg_stat_clear_snapshot()");
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                    WHERE datname = current_database() AND pid <> pg_backend_pid()
                    AND wait_event_type = 'Lock' AND query LIKE '%workspace_invitations%')
                    """, Boolean.class))) return;
            try { Thread.sleep(20); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        }
        throw new AssertionError("두 번째 요청이 초대 행 잠금 대기에 도달하지 않았습니다.");
    }

    private record Fixture(String owner, String invitee, String workspaceId, String invitationId, String token) {}
}
