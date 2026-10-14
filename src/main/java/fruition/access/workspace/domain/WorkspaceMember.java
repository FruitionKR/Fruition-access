package fruition.access.workspace.domain;

import fruition.access.user.domain.User;
import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "workspace_members")
public class WorkspaceMember {

    @EmbeddedId
    private WorkspaceMemberId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("workspaceId")
    @JoinColumn(name = "workspace_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("userId")
    @JoinColumn(name = "user_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkspaceRole role;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    /** MEMBER에게 부여된 권한. OWNER는 저장하지 않고 {@link #effectivePermissions()}가 전부로 계산한다. */
    // 멤버 목록처럼 여러 행을 읽을 때 행마다 권한을 따로 조회하지 않도록 묶어 읽는다.
    @ElementCollection(fetch = FetchType.EAGER)
    @BatchSize(size = 100)
    @CollectionTable(name = "workspace_member_permissions", joinColumns = {
            @JoinColumn(name = "workspace_id", referencedColumnName = "workspace_id"),
            @JoinColumn(name = "user_id", referencedColumnName = "user_id")})
    @Column(name = "permission", nullable = false)
    @Convert(converter = WorkspacePermissionConverter.class)
    private Set<WorkspacePermission> permissions = new HashSet<>();

    protected WorkspaceMember() {}

    public WorkspaceMember(Workspace workspace, User user, WorkspaceRole role) {
        this.id = new WorkspaceMemberId(workspace.getId(), user.getId());
        this.workspace = workspace;
        this.user = user;
        this.role = role;
        this.joinedAt = Instant.now();
    }

    public void changeRole(WorkspaceRole role) {
        this.role = role;
    }

    public void replacePermissions(Set<WorkspacePermission> next) {
        permissions.retainAll(next);
        permissions.addAll(next);
    }

    /** OWNER는 항상 전부, MEMBER는 부여된 항목만. */
    public Set<WorkspacePermission> effectivePermissions() {
        return role == WorkspaceRole.OWNER
                ? EnumSet.allOf(WorkspacePermission.class)
                : permissions.isEmpty() ? EnumSet.noneOf(WorkspacePermission.class) : EnumSet.copyOf(permissions);
    }

    public Set<WorkspacePermission> getPermissions() { return permissions; }
    public User getUser() { return user; }
    public String getWorkspaceId() { return id.getWorkspaceId(); }
    public String getUserId() { return id.getUserId(); }
    public WorkspaceRole getRole() { return role; }
    public Instant getJoinedAt() { return joinedAt; }
}
