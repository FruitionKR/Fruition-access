package fruition.access.mcp.repository;

import fruition.access.mcp.domain.McpClientPolicy;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface McpClientPolicyRepository extends JpaRepository<McpClientPolicy, String> {
    @Modifying
    @Query(value = """
            INSERT INTO mcp_client_policies(client_id, enabled, updated_at)
            VALUES (:clientId, false, now())
            ON CONFLICT (client_id) DO NOTHING
            """, nativeQuery = true)
    int insertDefault(@Param("clientId") String clientId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM McpClientPolicy c WHERE c.clientId = :clientId")
    Optional<McpClientPolicy> findForUpdate(@Param("clientId") String clientId);
}
