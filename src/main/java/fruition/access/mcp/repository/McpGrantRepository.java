package fruition.access.mcp.repository;

import fruition.access.mcp.domain.McpGrant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface McpGrantRepository extends JpaRepository<McpGrant, UUID> {
    Slice<McpGrant> findByUserId(String userId, Pageable pageable);

    Slice<McpGrant> findByWorkspaceId(String workspaceId, Pageable pageable);

    @Query("""
            SELECT g.userId AS userId, g.workspaceId AS workspaceId, g.clientId AS clientId
            FROM McpGrant g WHERE g.grantId = :grantId
            """)
    Optional<GrantContext> findContextById(@Param("grantId") UUID grantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM McpGrant g WHERE g.grantId = :grantId")
    Optional<McpGrant> findForUpdate(@Param("grantId") UUID grantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM McpGrant g WHERE g.workspaceId = :workspaceId AND g.grantId = :grantId")
    Optional<McpGrant> findInWorkspaceForUpdate(@Param("workspaceId") String workspaceId,
                                                @Param("grantId") UUID grantId);

    @Modifying
    @Query(value = """
            UPDATE mcp_grants
            SET status = 'ADMIN_REVOKED', revocation_reason = 'WORKSPACE_POLICY_DISABLED', revoked_at = :now
            WHERE workspace_id = :workspaceId AND status IN ('ACTIVE', 'USER_DISCONNECTED')
            """, nativeQuery = true)
    int revokeForDisabledWorkspace(@Param("workspaceId") String workspaceId, @Param("now") Instant now);

    @Modifying
    @Query(value = """
            UPDATE mcp_grants
            SET status = 'ADMIN_REVOKED', revocation_reason = 'ADMIN_GRANT_REVOKE', revoked_at = :now
            WHERE workspace_id = :workspaceId AND status IN ('ACTIVE', 'USER_DISCONNECTED')
            """, nativeQuery = true)
    int revokeForChangedWorkspace(@Param("workspaceId") String workspaceId, @Param("now") Instant now);

    @Modifying
    @Query(value = """
            UPDATE mcp_grants
            SET status = 'SECURITY_REVOKED', revocation_reason = 'CLIENT_DISABLED', revoked_at = :now
            WHERE client_id = :clientId AND status IN ('ACTIVE', 'USER_DISCONNECTED', 'ADMIN_REVOKED')
            """, nativeQuery = true)
    int revokeForDisabledClient(@Param("clientId") String clientId, @Param("now") Instant now);

    @Modifying
    @Query(value = """
            UPDATE mcp_grants
            SET status = 'USER_DISCONNECTED', revocation_reason = 'USER_DISCONNECT', revoked_at = :now
            WHERE workspace_id = :workspaceId AND user_id = :userId AND status = 'ACTIVE'
            """, nativeQuery = true)
    int disconnectForRemovedMember(@Param("workspaceId") String workspaceId,
                                   @Param("userId") String userId,
                                   @Param("now") Instant now);

    @Modifying
    @Query(value = """
            UPDATE mcp_grants
            SET status = 'ADMIN_REVOKED', revocation_reason = 'ADMIN_GRANT_REVOKE', revoked_at = :now
            WHERE workspace_id = :workspaceId AND user_id = :userId
              AND status IN ('ACTIVE', 'USER_DISCONNECTED')
            """, nativeQuery = true)
    int revokeForRemovedMember(@Param("workspaceId") String workspaceId,
                               @Param("userId") String userId,
                               @Param("now") Instant now);

    interface GrantContext {
        String getUserId();
        String getWorkspaceId();
        String getClientId();
    }
}
