package fruition.access.workspace.service;

import fruition.TestcontainersConfiguration;
import fruition.access.user.domain.User;
import fruition.access.user.repository.UserRepository;
import fruition.access.workspace.domain.Workspace;
import fruition.access.workspace.domain.WorkspaceMember;
import fruition.access.workspace.domain.WorkspaceRole;
import fruition.access.workspace.dto.WorkspaceMemberRoleUpdateRequest;
import fruition.access.workspace.exception.LastOwnerException;
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

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OWNER 두 명이 동시에 빠지면 워크스페이스가 OWNER 0명으로 남는다. 이름 변경·아이콘·삭제·
 * 초대·역할 변경이 모두 OWNER를 요구하므로, 그 워크스페이스는 영구히 관리 불능이 되고
 * 삭제조차 못 한다 — DB 직접 접근 말고는 복구 수단이 없다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class WorkspaceOwnerConcurrencyIntegrationTest {

    @Autowired WorkspaceMemberService memberService;
    @Autowired UserRepository users;
    @Autowired WorkspaceRepository workspaces;
    @Autowired WorkspaceMemberRepository members;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;

    private String user() {
        String id = UUID.randomUUID().toString();
        users.saveAndFlush(new User(id, id + "@example.com", "local", "사용자", null));
        return id;
    }

    private Workspace workspaceWithTwoOwners(String firstOwnerId, String secondOwnerId) {
        Workspace workspace = workspaces.saveAndFlush(new Workspace(UUID.randomUUID().toString(), "테스트"));
        members.saveAndFlush(new WorkspaceMember(workspace, users.getReferenceById(firstOwnerId), WorkspaceRole.OWNER));
        members.saveAndFlush(new WorkspaceMember(workspace, users.getReferenceById(secondOwnerId), WorkspaceRole.OWNER));
        return workspace;
    }

    @Test
    void concurrentSelfRemoval_leavesAtLeastOneOwner() throws Exception {
        String first = user();
        String second = user();
        String workspaceId = workspaceWithTwoOwners(first, second).getId();

        Future<?> result = runConcurrently(
                () -> memberService.remove(first, workspaceId, first),
                () -> memberService.remove(second, workspaceId, second));

        assertThatThrownBy(() -> result.get(10, TimeUnit.SECONDS))
                .hasCauseInstanceOf(LastOwnerException.class);
        assertThat(members.countActiveByRole(workspaceId, WorkspaceRole.OWNER)).isEqualTo(1);
    }

    @Test
    void concurrentOwnerDemotion_leavesAtLeastOneOwner() throws Exception {
        String first = user();
        String second = user();
        String workspaceId = workspaceWithTwoOwners(first, second).getId();
        var toMember = new WorkspaceMemberRoleUpdateRequest(WorkspaceRole.MEMBER);

        Future<?> result = runConcurrently(
                () -> memberService.changeRole(first, workspaceId, first, toMember),
                () -> memberService.changeRole(second, workspaceId, second, toMember));

        assertThatThrownBy(() -> result.get(10, TimeUnit.SECONDS))
                .hasCauseInstanceOf(LastOwnerException.class);
        assertThat(members.countActiveByRole(workspaceId, WorkspaceRole.OWNER)).isEqualTo(1);
    }

    /**
     * 첫 요청을 커밋하지 않은 상태로 두 번째 요청을 다른 스레드에서 보낸다. OWNER 집합에
     * 잠금이 없으면 두 번째 요청은 아직 커밋되지 않은 OWNER 2명을 세고 그대로 통과한다.
     */
    private Future<?> runConcurrently(Runnable firstRequest, Runnable secondRequest) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            return new TransactionTemplate(transactions).execute(status -> {
                firstRequest.run();
                Future<?> second = executor.submit(secondRequest);
                awaitLockOrCompletion(second);
                return second;
            });
        } finally {
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** 두 번째 요청이 잠금 대기에 들어가거나(수정 후) 끝나버릴 때까지(수정 전) 기다린다. */
    private void awaitLockOrCompletion(Future<?> second) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (second.isDone()) return;
            jdbc.execute("SELECT pg_stat_clear_snapshot()");
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                    WHERE datname = current_database() AND pid <> pg_backend_pid()
                    AND wait_event_type = 'Lock' AND query LIKE '%workspace_members%')
                    """, Boolean.class))) return;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("두 번째 요청이 완료도 잠금 대기도 하지 않았습니다.");
    }
}
