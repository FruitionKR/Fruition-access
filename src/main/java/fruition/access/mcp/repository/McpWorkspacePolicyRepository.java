package fruition.access.mcp.repository;

import fruition.access.mcp.domain.McpWorkspacePolicy;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface McpWorkspacePolicyRepository extends JpaRepository<McpWorkspacePolicy, String> {
    @Modifying
    @Query(value = """
            INSERT INTO mcp_workspace_policies(workspace_id, enabled, generation, updated_at)
            VALUES (:workspaceId, false, 1, now())
            ON CONFLICT (workspace_id) DO NOTHING
            """, nativeQuery = true)
    int insertDefault(@Param("workspaceId") String workspaceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM McpWorkspacePolicy p WHERE p.workspaceId = :workspaceId")
    Optional<McpWorkspacePolicy> findForUpdate(@Param("workspaceId") String workspaceId);
}
