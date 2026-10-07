package fruition.access.user.repository;

import fruition.access.user.domain.UserOAuthAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserOAuthAccountRepository extends JpaRepository<UserOAuthAccount, Long> {

    Optional<UserOAuthAccount> findByProviderAndProviderUserId(String provider, String providerUserId);

    List<UserOAuthAccount> findAllByUserIdOrderByProvider(String userId);
}
