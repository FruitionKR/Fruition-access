package fruition.access.user.mail;

import fruition.access.user.domain.User;
import fruition.access.user.repository.UserRepository;
import fruition.shared.i18n.RequestLocale;
import fruition.shared.i18n.SupportedLocale;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;

/**
 * 메일 언어를 고른다. 수신자의 저장된 언어 → 지금 요청의 {@code Accept-Language} → 한국어 순이다.
 * 가입 전 인증 메일처럼 수신자 계정이 없으면 요청 언어를 쓴다.
 */
@Component
public class MailLocaleResolver {

    private final UserRepository userRepository;

    public MailLocaleResolver(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** 이 주소를 쓰는 계정의 저장 언어. 같은 주소의 계정이 여럿이면(provider별 분리, ADR 0017) 이메일 가입 계정을 먼저 본다. */
    public Locale forEmail(String email) {
        return userRepository.findAllByEmail(email).stream()
                .sorted(Comparator.comparing((User user) -> !User.PROVIDER_LOCAL.equals(user.getProvider())))
                .map(user -> SupportedLocale.fromCode(user.getLocale()))
                .flatMap(Optional::stream)
                .findFirst()
                .orElseGet(RequestLocale::current)
                .toLocale();
    }

    /** 이 사용자의 저장 언어. 없으면 요청 언어다. */
    public Locale forUser(User user) {
        return SupportedLocale.fromCode(user.getLocale())
                .orElseGet(RequestLocale::current)
                .toLocale();
    }
}
