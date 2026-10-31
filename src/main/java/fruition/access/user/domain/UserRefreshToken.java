package fruition.access.user.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_refresh_tokens")
public class UserRefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** 발급 요청의 User-Agent 원문. 세션 목록에서 기기를 구분하는 유일한 단서다. */
    @Column(name = "user_agent", length = 512)
    private String userAgent;

    /** 같은 로그인에서 회전으로 이어진 토큰이 공유한다. 세션 폐기는 이 계보 전체를 끊는다. */
    @Column(name = "family_id", nullable = false, updatable = false, length = 64)
    private String familyId;

    protected UserRefreshToken() {}

    public UserRefreshToken(String userId, String tokenHash, Instant expiresAt) {
        this(userId, tokenHash, expiresAt, null);
    }

    /** 로그인으로 새 세션(계보)을 시작한다. */
    public UserRefreshToken(String userId, String tokenHash, Instant expiresAt, String userAgent) {
        this(userId, tokenHash, expiresAt, userAgent, "rtf_" + UUID.randomUUID().toString().replace("-", ""));
    }

    private UserRefreshToken(String userId, String tokenHash, Instant expiresAt, String userAgent, String familyId) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.userAgent = userAgent;
        this.familyId = familyId;
        this.createdAt = Instant.now();
    }

    /** refresh 회전으로 이어 받는 토큰. 같은 계보에 속한다. */
    public UserRefreshToken successor(String tokenHash, Instant expiresAt, String userAgent) {
        return new UserRefreshToken(userId, tokenHash, expiresAt, userAgent, familyId);
    }

    public void revoke() {
        this.revokedAt = Instant.now();
    }

    public boolean isValid() {
        return revokedAt == null && expiresAt.isAfter(Instant.now());
    }

    public Long getId() { return id; }
    public String getUserId() { return userId; }
    public String getTokenHash() { return tokenHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public String getUserAgent() { return userAgent; }
    public String getFamilyId() { return familyId; }
}
