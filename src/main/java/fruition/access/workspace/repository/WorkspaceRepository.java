package fruition.access.workspace.repository;

import fruition.access.workspace.domain.Workspace;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface WorkspaceRepository extends JpaRepository<Workspace, String> {
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select w from Workspace w where w.id = :id and w.deletedAt is null")
    Optional<Workspace> findActiveByIdForShare(String id);
}
