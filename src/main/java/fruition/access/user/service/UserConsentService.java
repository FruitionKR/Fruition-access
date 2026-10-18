package fruition.access.user.service;

import fruition.access.user.exception.InvalidConsentException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 가입·재동의 때 받은 만 18세 이상 확인과 이용약관 동의를 버전과 함께 쌓는다.
 *
 * <p>만 18세 미만은 가입할 수 없다(이용약관 제5조, Gemini API 약관). 개인정보 수집·이용은 계약 이행 근거라
 * 따로 동의받지 않고 처리방침은 열람한 버전만 남긴다. 마케팅 수신만 선택 항목이다.
 *
 * <p>약관 문안이 확정되기 전에는 {@code app.legal.consent-required=false}로 동의 단계를 끈다. 이때는 동의를
 * 요구하지도, 기록하지도 않는다. 확정되지 않은 문안에 대한 동의(또는 받지 않은 만 18세 확인)를 남기지 않기 위해서다.
 * 다시 켜면 동의 기록이 없는 회원은 모두 {@link #consentRequired}가 true가 되어 확정된 버전으로 다시 동의한다.
 */
@Service
public class UserConsentService {

    private final JdbcTemplate jdbc;
    private final String termsVersion;
    private final String privacyVersion;
    private final boolean consentEnabled;

    public UserConsentService(JdbcTemplate jdbc,
                              @Value("${app.legal.terms-version}") String termsVersion,
                              @Value("${app.legal.privacy-version}") String privacyVersion,
                              @Value("${app.legal.consent-required}") boolean consentEnabled) {
        this.jdbc = jdbc;
        this.termsVersion = termsVersion;
        this.privacyVersion = privacyVersion;
        this.consentEnabled = consentEnabled;
    }

    /**
     * 만 18세 이상 확인과 현재 버전 이용약관 동의가 모두 있어야 한다. 화면이 옛 약관을 보여 줬으면 거절한다.
     * 동의 단계를 껐으면 어떤 값이든(빠져도) 통과한다.
     */
    public void validate(Boolean ageConfirmed, String agreedTermsVersion) {
        if (!consentEnabled) {
            return;
        }
        if (!Boolean.TRUE.equals(ageConfirmed) || !termsVersion.equals(agreedTermsVersion)) {
            throw new InvalidConsentException();
        }
    }

    /** 동의 단계를 껐으면 요청에 동의 항목이 있어도 아무것도 남기지 않는다(age_confirmed=true 행을 만들지 않는다). */
    public void record(String userId, boolean marketingOptIn) {
        if (!consentEnabled) {
            return;
        }
        jdbc.update("""
                INSERT INTO user_consents(user_id, terms_version, privacy_version, age_confirmed, marketing_opt_in)
                VALUES (?, ?, ?, true, ?)
                """, userId, termsVersion, privacyVersion, marketingOptIn);
    }

    /**
     * 가장 최근 동의가 현재 이용약관 버전이 아니면(동의 기록이 없는 기존 회원 포함) 다시 동의받아야 한다.
     * 동의 단계를 껐으면 항상 false다.
     */
    public boolean consentRequired(String userId) {
        if (!consentEnabled) {
            return false;
        }
        List<String> latest = jdbc.queryForList(
                "SELECT terms_version FROM user_consents WHERE user_id = ? ORDER BY consented_at DESC, id DESC LIMIT 1",
                String.class, userId);
        return latest.isEmpty() || !termsVersion.equals(latest.get(0));
    }
}
