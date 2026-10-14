package fruition.access.user.repository;

import fruition.access.user.domain.UserAvatar;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAvatarRepository extends JpaRepository<UserAvatar, String> {
}
