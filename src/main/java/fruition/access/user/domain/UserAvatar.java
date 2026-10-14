package fruition.access.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 프로필 사진 바이너리. 사용자와 1:1이며 사용자 삭제 시 FK cascade로 함께 지워진다.
 *
 * <p>{@link User}와 분리한 이유: 멤버 목록·인가 조회가 사용자마다 최대 1MB를 함께 읽지 않게 한다.
 */
@Entity
@Table(name = "user_avatars")
public class UserAvatar {

    @Id
    @Column(name = "user_id")
    private String userId;

    @Column(nullable = false)
    private byte[] image;

    protected UserAvatar() {}

    public UserAvatar(String userId, byte[] image) {
        this.userId = userId;
        this.image = image;
    }

    public void replace(byte[] image) {
        this.image = image;
    }

    public String getUserId() { return userId; }
    public byte[] getImage() { return image; }
}
