package fruition.access.workspace.service;

import fruition.TestcontainersConfiguration;
import fruition.access.user.domain.User;
import fruition.access.user.repository.UserRepository;
import fruition.access.workspace.domain.Workspace;
import fruition.access.workspace.domain.WorkspaceMember;
import fruition.access.workspace.domain.WorkspaceRole;
import fruition.access.workspace.repository.WorkspaceMemberRepository;
import fruition.access.workspace.repository.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * document의 AI 사용량 정산은 기간 중 멤버였던 사용자(탈퇴·제거 포함)를 이 이력으로 찾는다.
 * workspace_members는 제거 시 행을 지우므로, 이력이 빠지면 그 사용자의 사용량이 정산에서 사라진다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class WorkspaceMembershipPeriodIntegrationTest {

    @Autowired WorkspaceMemberService memberService;
    @Autowired UserRepository users;
    @Autowired WorkspaceRepository workspaces;
    @Autowired WorkspaceMemberRepository members;
    @Autowired JdbcTemplate jdbc;

    private String user() {
        String id = UUID.randomUUID().toString();
        users.saveAndFlush(new User(id, id + "@example.com", "local", "사용자", null));
        return id;
    }

    private void join(Workspace workspace, String userId, WorkspaceRole role) {
        members.saveAndFlush(new WorkspaceMember(workspace, users.getReferenceById(userId), role));
    }

    @Test
    void removedSelfLeftAndDeletedUsersStayInHistory() {
        String owner = user();
        String removed = user();
        String left = user();
        String deleted = user();
        Workspace workspace = workspaces.saveAndFlush(new Workspace(UUID.randomUUID().toString(), "정산"));
        join(workspace, owner, WorkspaceRole.OWNER);
        join(workspace, removed, WorkspaceRole.MEMBER);
        join(workspace, left, WorkspaceRole.MEMBER);
        join(workspace, deleted, WorkspaceRole.MEMBER);
        Instant from = Instant.now().minusSeconds(60);

        memberService.remove(owner, workspace.getId(), removed);
        memberService.remove(left, workspace.getId(), left);
        jdbc.update("DELETE FROM users WHERE id = ?", deleted);

        assertThat(members.findUserIdsMemberDuring(workspace.getId(), from, Instant.now().plusSeconds(60)))
                .containsExactlyInAnyOrder(owner, removed, left, deleted);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_membership_periods "
                + "WHERE workspace_id = ? AND left_at IS NOT NULL", Integer.class, workspace.getId())).isEqualTo(3);
    }

    @Test
    void periodBoundsAreStartInclusiveEndExclusive() {
        String before = user();
        String during = user();
        String after = user();
        String rejoined = user();
        String id = workspaces.saveAndFlush(new Workspace(UUID.randomUUID().toString(), "경계")).getId();
        insertPeriod(id, before, "2026-08-01T00:00:00Z", "2026-09-01T00:00:00Z");
        insertPeriod(id, during, "2026-08-01T00:00:00Z", "2026-09-01T00:00:01Z");
        insertPeriod(id, after, "2026-10-01T00:00:00Z", null);
        insertPeriod(id, rejoined, "2026-09-02T00:00:00Z", "2026-09-03T00:00:00Z");
        insertPeriod(id, rejoined, "2026-09-10T00:00:00Z", null);

        assertThat(members.findUserIdsMemberDuring(id,
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z")))
                .containsExactlyInAnyOrder(during, rejoined);
    }

    private void insertPeriod(String workspaceId, String userId, String joinedAt, String leftAt) {
        jdbc.update("INSERT INTO workspace_membership_periods (workspace_id, user_id, joined_at, left_at) "
                + "VALUES (?, ?, ?::timestamptz, ?::timestamptz)", workspaceId, userId, joinedAt, leftAt);
    }
}
