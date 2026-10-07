package fruition.access.user.repository;

import fruition.access.user.domain.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByEmailAndProvider(String email, String provider);

    List<User> findAllByEmail(String email);

    boolean existsByEmailAndProvider(String email, String provider);

    /** 로그인 수단 연동·해제를 사용자 단위로 직렬화한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(String id);
}
