package fruition.access.user.mail;

import java.util.Locale;

/**
 * 인증번호 발송 추상화. MVP는 로그 stub({@link LoggingEmailVerificationSender})만 제공하며,
 * 운영 배포 전 실제 메일(SMTP) 발송 구현으로 교체한다.
 */
public interface EmailVerificationSender {
    /** {@code locale}은 메일 언어다({@link MailLocaleResolver}가 고른다). */
    void send(String email, String purpose, String code, Locale locale);
}
