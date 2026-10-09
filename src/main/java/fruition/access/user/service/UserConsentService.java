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
 */
@Service
public class UserConsentService {

    private final JdbcTemplate jdbc;
    private final String termsVersion;
    private final String privacyVersion;

    public UserConsentService(JdbcTemplate jdbc,
                              @Value("${app.legal.terms-version}") String termsVersion,
                              @Value("${app.legal.privacy-version}") String privacyVersion) {
        this.jdbc = jdbc;
        this.termsVersion = termsVersion;
        this.privacyVersion = privacyVersion;
    }

    /** 만 18세 이상 확인과 현재 버전 이용약관 동의가 모두 있어야 한다. 화면이 옛 약관을 보여 줬으면 거절한다. */
    public void validate(Boolean ageConfirmed, String agreedTermsVersion) {
        if (!Boolean.TRUE.equals(ageConfirmed) || !termsVersion.equals(agreedTermsVersion)) {
            throw new InvalidConsentException();
        }
    }

    public void record(String userId, boolean marketingOptIn) {
        jdbc.update("""
                INSERT INTO user_consents(user_id, terms_version, privacy_version, age_confirmed, marketing_opt_in)
                VALUES (?, ?, ?, true, ?)
                """, userId, termsVersion, privacyVersion, marketingOptIn);
    }

    /** 가장 최근 동의가 현재 이용약관 버전이 아니면(동의 기록이 없는 기존 회원 포함) 다시 동의받아야 한다. */
    public boolean consentRequired(String userId) {
        List<String> latest = jdbc.queryForList(
                "SELECT terms_version FROM user_consents WHERE user_id = ? ORDER BY consented_at DESC, id DESC LIMIT 1",
                String.class, userId);
        return latest.isEmpty() || !termsVersion.equals(latest.get(0));
    }
}
