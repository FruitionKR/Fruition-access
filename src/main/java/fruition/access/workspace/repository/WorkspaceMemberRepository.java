package fruition.access.workspace.repository;

import fruition.access.workspace.domain.Workspace;
import fruition.access.workspace.domain.WorkspaceMember;
import fruition.access.workspace.domain.WorkspaceMemberId;
import fruition.access.workspace.domain.WorkspaceRole;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, WorkspaceMemberId> {

    @Query("""
            SELECT CASE WHEN COUNT(m) > 0 THEN true ELSE false END
            FROM WorkspaceMember m
            WHERE m.workspace.id = :workspaceId
              AND m.user.id = :userId
              AND m.workspace.deletedAt IS NULL
            """)
    boolean existsByWorkspace_IdAndUser_Id(
            @Param("workspaceId") String workspaceId,
            @Param("userId") String userId
    );

    Optional<WorkspaceMember> findByWorkspace_IdAndUser_Id(String workspaceId, String userId);

    @Query("""
            SELECT m.role
            FROM WorkspaceMember m
            WHERE m.workspace.id = :workspaceId
              AND m.user.id = :userId
              AND m.workspace.deletedAt IS NULL
            """)
    Optional<WorkspaceRole> findActiveRole(
            @Param("workspaceId") String workspaceId,
            @Param("userId") String userId
    );

    @Query("""
            SELECT m.workspace
            FROM WorkspaceMember m
            WHERE m.user.id = :userId
              AND m.workspace.deletedAt IS NULL
            ORDER BY m.workspace.createdAt DESC
            """)
    List<Workspace> findAllWorkspacesByUserId(@Param("userId") String userId);

    @Query("""
            SELECT m.workspace
            FROM WorkspaceMember m
            WHERE m.workspace.id = :workspaceId
              AND m.user.id = :userId
              AND m.role = :role
            """)
    Optional<Workspace> findOwnedWorkspaceIncludingDeleted(
            @Param("workspaceId") String workspaceId,
            @Param("userId") String userId,
            @Param("role") WorkspaceRole role
    );

    @Query("""
            SELECT m.workspace
            FROM WorkspaceMember m
            WHERE m.user.id = :userId
              AND m.role = :role
              AND m.workspace.deletedAt IS NOT NULL
            ORDER BY m.workspace.deletedAt DESC
            """)
    List<Workspace> findDeletedOwnedWorkspaces(
            @Param("userId") String userId,
            @Param("role") WorkspaceRole role
    );

    @Query("""
            SELECT m
            FROM WorkspaceMember m
            JOIN FETCH m.user
            WHERE m.workspace.id = :workspaceId
              AND m.workspace.deletedAt IS NULL
            ORDER BY m.joinedAt
            """)
    List<WorkspaceMember> findActiveMembers(@Param("workspaceId") String workspaceId);

    @Query("""
            SELECT COUNT(m)
            FROM WorkspaceMember m
            WHERE m.workspace.id = :workspaceId
              AND m.role = :role
              AND m.workspace.deletedAt IS NULL
            """)
    long countActiveByRole(
            @Param("workspaceId") String workspaceId,
            @Param("role") WorkspaceRole role
    );

    /**
     * OWNER 행을 잠근 채로 돌려준다. 잠금 없이 세면 동시 요청이 각각 OWNER 2명을 보고
     * 둘 다 통과해 OWNER 0명이 남는다 — 그 워크스페이스는 모든 관리 동작이 OWNER를
     * 요구하므로 삭제조차 못 하는 상태가 된다. 같은 행을 두 트랜잭션이 반대 순서로
     * 잠가 교착하지 않도록 정렬 순서를 고정한다.
     *
     * <p>deletedAt 조건은 두지 않는다 — 호출 전에 requireMember가 같은 트랜잭션에서
     * 활성 워크스페이스임을 이미 확인한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT m
            FROM WorkspaceMember m
            WHERE m.workspace.id = :workspaceId
              AND m.role = :role
            ORDER BY m.user.id
            """)
    List<WorkspaceMember> findByRoleForUpdate(
            @Param("workspaceId") String workspaceId,
            @Param("role") WorkspaceRole role
    );

    @Query("""
            SELECT m.workspace
            FROM WorkspaceMember m
            WHERE m.workspace.id = :workspaceId
              AND m.user.id = :userId
              AND m.workspace.deletedAt IS NULL
            """)
    Optional<Workspace> findActiveWorkspaceForMember(
            @Param("workspaceId") String workspaceId,
            @Param("userId") String userId
    );

    /** 기간 [from, to)에 한 번이라도 멤버였던 사용자. 탈퇴·제거된 사용자도 포함한다(V21 이력). */
    @Query(value = """
            SELECT DISTINCT user_id
            FROM workspace_membership_periods
            WHERE workspace_id = :workspaceId
              AND joined_at < :to
              AND (left_at IS NULL OR left_at > :from)
            ORDER BY user_id
            """, nativeQuery = true)
    List<String> findUserIdsMemberDuring(@Param("workspaceId") String workspaceId,
                                         @Param("from") Instant from,
                                         @Param("to") Instant to);
}
