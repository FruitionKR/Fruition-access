package fruition.access.workspace.service;

import fruition.TestcontainersConfiguration;
import fruition.access.user.domain.User;
import fruition.access.user.repository.UserRepository;
import fruition.access.workspace.domain.Workspace;
import fruition.access.workspace.domain.WorkspaceMember;
import fruition.access.workspace.domain.WorkspacePermission;
import fruition.access.workspace.domain.WorkspaceRole;
import fruition.access.workspace.dto.WorkspaceMemberRoleUpdateRequest;
import fruition.access.workspace.repository.WorkspaceMemberRepository;
import fruition.access.workspace.repository.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 권한 항목의 실제 저장 형식("wiki.ingest")과 멤버 제거 후 재가입 시 초기화를 DB로 확인한다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class WorkspaceMemberPermissionsIntegrationTest {

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

    private List<String> stored(String workspaceId, String userId) {
        return jdbc.queryForList("SELECT permission FROM workspace_member_permissions WHERE workspace_id = ? AND user_id = ?",
                String.class, workspaceId, userId);
    }

    @Test
    void permissionsAreStoredAsDottedValueAndResetOnReinvite() {
        String owner = user();
        String member = user();
        Workspace workspace = workspaces.saveAndFlush(new Workspace(UUID.randomUUID().toString(), "권한"));
        join(workspace, owner, WorkspaceRole.OWNER);
        join(workspace, member, WorkspaceRole.MEMBER);
        assertThat(stored(workspace.getId(), member)).isEmpty();

        var response = memberService.changeRole(owner, workspace.getId(), member,
                new WorkspaceMemberRoleUpdateRequest(null, List.of(WorkspacePermission.WIKI_INGEST)));

        assertThat(response.permissions()).containsExactly("wiki.ingest");
        assertThat(stored(workspace.getId(), member)).containsExactly("wiki.ingest");
        assertThat(members.findActiveMember(workspace.getId(), member).orElseThrow().effectivePermissions())
                .containsExactly(WorkspacePermission.WIKI_INGEST);

        memberService.remove(owner, workspace.getId(), member);
        assertThat(stored(workspace.getId(), member)).isEmpty();
        join(workspace, member, WorkspaceRole.MEMBER);
        assertThat(members.findActiveMember(workspace.getId(), member).orElseThrow().effectivePermissions()).isEmpty();
    }

    @Test
    void promoteClearsAndDemoteGrantsAllInDb() {
        String owner = user();
        String other = user();
        Workspace workspace = workspaces.saveAndFlush(new Workspace(UUID.randomUUID().toString(), "승강"));
        join(workspace, owner, WorkspaceRole.OWNER);
        join(workspace, other, WorkspaceRole.MEMBER);
        memberService.changeRole(owner, workspace.getId(), other,
                new WorkspaceMemberRoleUpdateRequest(null, List.of(WorkspacePermission.WIKI_INGEST)));

        memberService.changeRole(owner, workspace.getId(), other, new WorkspaceMemberRoleUpdateRequest(WorkspaceRole.OWNER, null));
        assertThat(stored(workspace.getId(), other)).isEmpty();

        memberService.changeRole(owner, workspace.getId(), other, new WorkspaceMemberRoleUpdateRequest(WorkspaceRole.MEMBER, null));
        assertThat(stored(workspace.getId(), other)).containsExactly("wiki.ingest");
    }
}
