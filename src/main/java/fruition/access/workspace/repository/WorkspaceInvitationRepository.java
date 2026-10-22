package fruition.access.workspace.repository;

import fruition.access.workspace.domain.WorkspaceInvitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface WorkspaceInvitationRepository extends JpaRepository<WorkspaceInvitation, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from WorkspaceInvitation i where i.tokenHash = :tokenHash")
    Optional<WorkspaceInvitation> findForAcceptance(@Param("tokenHash") String tokenHash);

    Optional<WorkspaceInvitation> findByTokenHash(String tokenHash);

    Optional<WorkspaceInvitation> findByWorkspaceIdAndEmailAndAcceptedAtIsNullAndRevokedAtIsNull(
            String workspaceId, String email);

    List<WorkspaceInvitation> findByWorkspaceIdAndAcceptedAtIsNullAndRevokedAtIsNullOrderByCreatedAt(
            String workspaceId);
}
