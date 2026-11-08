package fruition.access.user.mail;

import fruition.access.user.exception.EmailVerificationSendException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Locale;
import java.util.Set;

/**
 * 운영용 SMTP 발송. 어떤 provider든 SMTP 계정만 있으면 동작한다(SES/SendGrid/Gmail SMTP 등).
 * 인증번호와 수신 이메일 원문은 로그에 남기지 않는다. 제목·본문은 메일 템플릿 번들({@code i18n/mail})에서 고른다.
 */
public class SmtpEmailVerificationSender implements EmailVerificationSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailVerificationSender.class);

    /** 제목이 따로 있는 purpose. 그 밖의 값은 기본 제목과 회원가입 문구를 쓴다. */
    private static final Set<String> KNOWN_PURPOSES = Set.of("signup", "email_change", "password_reset", "password_change");

    private final JavaMailSender mailSender;
    private final MessageSource messageSource;
    private final String from;

    public SmtpEmailVerificationSender(JavaMailSender mailSender, MessageSource messageSource, String from) {
        if (from == null || from.isBlank()) {
            throw new IllegalStateException(
                    "SMTP 발송에는 발신 주소(app.auth.email-verification.from / MAIL_FROM)가 필요합니다.");
        }
        this.mailSender = mailSender;
        this.messageSource = messageSource;
        this.from = from;
    }

    @Override
    public void send(String email, String purpose, String code, Locale locale) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject(subjectFor(purpose, locale));
        message.setText(bodyFor(purpose, code, locale));
        try {
            mailSender.send(message);
            log.info("[인증번호 발송 완료] purpose={}", purpose);
        } catch (MailException e) {
            log.warn("[인증번호 발송 실패] operation=email_verification stage=mail_send purpose={} error={}", purpose, e.getMessage());
            throw new EmailVerificationSendException("인증번호 메일 발송에 실패했습니다.", e);
        }
    }

    private String subjectFor(String purpose, Locale locale) {
        String key = KNOWN_PURPOSES.contains(purpose) ? purpose : "default";
        return messageSource.getMessage("mail.verification.subject." + key, null, locale);
    }

    private String bodyFor(String purpose, String code, Locale locale) {
        String key = KNOWN_PURPOSES.contains(purpose) ? purpose : "signup";
        String action = messageSource.getMessage("mail.verification.action." + key, null, locale);
        return messageSource.getMessage("mail.verification.body", new Object[]{action, code}, locale);
    }
}
