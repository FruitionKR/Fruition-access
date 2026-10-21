package fruition.access.user.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "email_verifications")
public class EmailVerification {

    @Id
    private String id;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String purpose;

    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    @Column(name = "code_expires_at", nullable = false)
    private Instant codeExpiresAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "token_hash")
    private String tokenHash;

    @Column(name = "token_expires_at")
    private Instant tokenExpiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected EmailVerification() {}

    public EmailVerification(String id, String email, String purpose, String codeHash, Instant codeExpiresAt) {
        this.id = id;
        this.email = email;
        this.purpose = purpose;
        this.codeHash = codeHash;
        this.codeExpiresAt = codeExpiresAt;
        this.attemptCount = 0;
        this.createdAt = Instant.now();
    }

    /**
     * 새 코드 발급 시 기존 미소비 레코드를 즉시 무효화한다.
     * 코드뿐 아니라 이미 확인해 받은 토큰도 만료시켜, 재발급 이전 토큰을 더는 쓸 수 없게 한다.
     */
    public void invalidate() {
        Instant now = Instant.now();
        this.codeExpiresAt = now;
        if (this.tokenHash != null) {
            this.tokenExpiresAt = now;
        }
    }

    public void increaseAttempt() {
        this.attemptCount++;
    }

    /** 코드 검증 성공 시 confirmed 상태로 전환하고 1회용 토큰을 부여한다. */
    public void confirm(String tokenHash, Instant tokenExpiresAt) {
        this.confirmedAt = Instant.now();
        this.tokenHash = tokenHash;
        this.tokenExpiresAt = tokenExpiresAt;
    }

    /** 최종 회원가입/비밀번호 재설정에서 토큰을 1회 소비 처리한다. */
    public void consume() {
        this.consumedAt = Instant.now();
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }

    public boolean isCodeExpired() {
        return codeExpiresAt.isBefore(Instant.now());
    }

    public boolean isTokenValid() {
        return tokenHash != null
                && consumedAt == null
                && tokenExpiresAt != null
                && tokenExpiresAt.isAfter(Instant.now());
    }

    public String getId() { return id; }
    public String getEmail() { return email; }
    public String getPurpose() { return purpose; }
    public String getCodeHash() { return codeHash; }
    public int getAttemptCount() { return attemptCount; }
    public String getTokenHash() { return tokenHash; }
    public Instant getCreatedAt() { return createdAt; }
}
