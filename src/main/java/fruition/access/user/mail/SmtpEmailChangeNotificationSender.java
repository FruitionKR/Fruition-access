package fruition.access.user.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 운영용 SMTP 발송. 수신 이메일 원문은 로그에 남기지 않는다. 실패는 호출자가 로그로 처리한다.
 * 제목·본문은 {@code i18n/mail}에서 고른다. 변경 시각은 언어와 상관없이 한국 시간으로 적는다.
 */
public class SmtpEmailChangeNotificationSender implements EmailChangeNotificationSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailChangeNotificationSender.class);
    private static final DateTimeFormatter CHANGED_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm '(KST)'").withZone(ZoneId.of("Asia/Seoul"));

    private final JavaMailSender mailSender;
    private final MessageSource messageSource;
    private final String from;

    public SmtpEmailChangeNotificationSender(JavaMailSender mailSender, MessageSource messageSource, String from) {
        if (from == null || from.isBlank()) {
            throw new IllegalStateException(
                    "SMTP 발송에는 발신 주소(app.auth.email-verification.from / MAIL_FROM)가 필요합니다.");
        }
        this.mailSender = mailSender;
        this.messageSource = messageSource;
        this.from = from;
    }

    @Override
    public void send(String previousEmail, String maskedNewEmail, Instant changedAt, Locale locale) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(previousEmail);
        message.setSubject(messageSource.getMessage("mail.email_change.subject", null, locale));
        message.setText(messageSource.getMessage("mail.email_change.body",
                new Object[]{maskedNewEmail, CHANGED_AT.format(changedAt)}, locale));
        mailSender.send(message);
        log.info("[이메일 변경 알림 발송 완료]");
    }
}
