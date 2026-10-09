package fruition.access.user.repository;

import fruition.access.user.domain.UserRefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;

public interface UserRefreshTokenRepository extends JpaRepository<UserRefreshToken, Long> {

    /** 같은 refresh token의 동시 회전이 한 번만 성공하도록 행을 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<UserRefreshToken> findByTokenHash(String tokenHash);

    List<UserRefreshToken> findAllByUserIdAndRevokedAtIsNull(String userId);

    List<UserRefreshToken> findAllByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(String userId);
}
