package fruition.access.user.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/** 개발용 stub. SMTP(spring.mail.host)가 없을 때 {@link EmailSenderConfig}가 등록하며, 메일 대신 로그를 남긴다. */
public class LoggingEmailChangeNotificationSender implements EmailChangeNotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailChangeNotificationSender.class);

    @Override
    public void send(String previousEmail, String maskedNewEmail, Instant changedAt) {
        log.info("[이메일 변경 알림(dev stub)] to={} newEmail={} changedAt={}", previousEmail, maskedNewEmail, changedAt);
    }
}
