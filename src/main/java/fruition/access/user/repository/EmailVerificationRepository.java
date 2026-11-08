package fruition.access.user.repository;

import fruition.access.user.domain.EmailVerification;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, String> {

    Optional<EmailVerification> findTopByEmailAndPurposeOrderByCreatedAtDesc(String email, String purpose);

    List<EmailVerification> findByEmailAndPurposeAndConsumedAtIsNull(String email, String purpose);

    long countByEmailAndPurposeAndCreatedAtAfter(String email, String purpose, Instant since);

    Optional<EmailVerification> findFirstByEmailAndPurposeAndCreatedAtAfterOrderByCreatedAtAsc(
            String email, String purpose, Instant since);

    /**
     * 현재 트랜잭션이 끝날 때까지 같은 key의 발급을 직렬화하는 PostgreSQL advisory lock을 잡는다.
     * 첫 발급처럼 잠글 행이 아직 없어도 동작한다. 반드시 트랜잭션 안에서 불러야 한다.
     */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:key, 0))", nativeQuery = true)
    int lockIssuance(@Param("key") String key);

    // 소비 트랜잭션이 끝날 때까지 같은 토큰의 검증·소비를 직렬화한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailVerification> findByTokenHash(String tokenHash);

    /** 인증번호 확인을 행 단위로 직렬화한다. 잠그지 않으면 동시 오답이 시도 횟수를 서로 덮어써 한도보다 많이 비교된다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from EmailVerification v where v.id = :id")
    Optional<EmailVerification> findByIdForUpdate(@Param("id") String id);

}
