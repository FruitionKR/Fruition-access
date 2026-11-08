package fruition.access.user.repository;

import fruition.access.user.domain.UserRefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRefreshTokenRepository extends JpaRepository<UserRefreshToken, Long> {

    /** 같은 refresh token의 동시 회전이 한 번만 성공하도록 행을 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<UserRefreshToken> findByTokenHash(String tokenHash);

    /** {@link #lockUserSessions}의 키를 정하려고 잠그지 않고 소유자만 읽는다. 토큰의 소유자는 바뀌지 않는다. */
    @Query("select t.userId from UserRefreshToken t where t.tokenHash = :tokenHash")
    Optional<String> findUserIdByTokenHash(@Param("tokenHash") String tokenHash);

    /**
     * 한 사용자의 refresh 회전과 세션 폐기를 트랜잭션 끝까지 직렬화한다. 행 잠금만으로는 폐기가 시작된 뒤
     * 커밋된 후속 토큰이 폐기 대상 조회에 보이지 않는다. 이 잠금을 먼저 잡고 읽으면 진행 중인 회전이 끝난
     * 뒤의 상태를 보고, 이후 회전은 폐기가 커밋될 때까지 기다린다. 행 잠금보다 항상 먼저 잡아 교착을 피한다.
     */
    @Query(value = "SELECT count(*) FROM (SELECT pg_advisory_xact_lock(hashtextextended('refresh:' || :userId, 0)))"
            + " AS user_refresh_tokens_lock", nativeQuery = true)
    long lockUserSessions(@Param("userId") String userId);

    List<UserRefreshToken> findAllByFamilyIdAndRevokedAtIsNull(String familyId);

    List<UserRefreshToken> findAllByUserIdAndRevokedAtIsNull(String userId);

    List<UserRefreshToken> findAllByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(String userId);
}
