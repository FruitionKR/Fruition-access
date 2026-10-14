package fruition.access.user.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "users")
public class User {

    /** 일반 회원가입으로 만든 계정의 provider 값. */
    public static final String PROVIDER_LOCAL = "local";

    @Id
    private String id;

    @Column(nullable = false)
    private String email;

    /** 계정을 만든 수단. 일반 회원가입은 "local", OAuth는 provider 등록 ID. */
    @Column(nullable = false)
    private String provider;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 프로필 사진 metadata. 바이너리는 {@link UserAvatar}에 있다. 둘 다 null이면 사진이 없다. */
    @Column(name = "avatar_content_type")
    private String avatarContentType;

    @Column(name = "avatar_hash")
    private String avatarHash;

    protected User() {}

    public User(String id, String email, String provider, String displayName, String passwordHash) {
        this.id = id;
        this.email = email;
        this.provider = provider;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void changeDisplayName(String displayName) {
        this.displayName = displayName;
        this.updatedAt = Instant.now();
    }

    public void changeEmail(String email) {
        this.email = email;
        this.updatedAt = Instant.now();
    }

    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
        this.updatedAt = Instant.now();
    }

    /** 프로필 사진 metadata를 바꾼다. 바이너리 저장은 호출자가 한다. */
    public void changeAvatar(String contentType, String hash) {
        this.avatarContentType = contentType;
        this.avatarHash = hash;
        this.updatedAt = Instant.now();
    }

    /** 프로필 사진 metadata를 지운다. 바이너리 삭제는 호출자가 한다. */
    public void clearAvatar() {
        this.avatarContentType = null;
        this.avatarHash = null;
        this.updatedAt = Instant.now();
    }

    public boolean hasAvatar() {
        return avatarHash != null;
    }

    /**
     * 프로필 사진 조회 경로. 사진이 없으면 null이다.
     * 해시를 {@code ?v=}로 붙여 사진을 바꾸면 URL도 바뀌게 한다 — 예전 이미지가 브라우저 캐시에 남지 않는다.
     */
    public String avatarUrl() {
        return hasAvatar() ? "/api/users/" + id + "/avatar?v=" + avatarHash : null;
    }

    public String getId() { return id; }
    public String getEmail() { return email; }
    public String getProvider() { return provider; }
    public String getDisplayName() { return displayName; }
    public String getPasswordHash() { return passwordHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getAvatarContentType() { return avatarContentType; }
    public String getAvatarHash() { return avatarHash; }
}
