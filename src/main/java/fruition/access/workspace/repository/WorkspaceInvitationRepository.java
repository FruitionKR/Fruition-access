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

    /*
     * 수락·취소·재발송은 같은 초대 행을 잠근 채 상태를 다시 읽는다. 잠그지 않으면 나중 커밋의 전체 컬럼
     * UPDATE가 먼저 커밋된 취소나 수락을 지워, 취소에 성공한 초대가 수락된 채로 남는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from WorkspaceInvitation i where i.tokenHash = :tokenHash")
    Optional<WorkspaceInvitation> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from WorkspaceInvitation i where i.id = :id")
    Optional<WorkspaceInvitation> findByIdForUpdate(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<WorkspaceInvitation> findByWorkspaceIdAndEmailAndAcceptedAtIsNullAndRevokedAtIsNull(
            String workspaceId, String email);

    List<WorkspaceInvitation> findByWorkspaceIdAndAcceptedAtIsNullAndRevokedAtIsNullOrderByCreatedAt(
            String workspaceId);
}
