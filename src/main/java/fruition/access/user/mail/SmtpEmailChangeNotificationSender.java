package fruition.access.user.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** 운영용 SMTP 발송. 수신 이메일 원문은 로그에 남기지 않는다. 실패는 호출자가 로그로 처리한다. */
public class SmtpEmailChangeNotificationSender implements EmailChangeNotificationSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailChangeNotificationSender.class);
    private static final DateTimeFormatter CHANGED_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm '(KST)'").withZone(ZoneId.of("Asia/Seoul"));

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailChangeNotificationSender(JavaMailSender mailSender, String from) {
        if (from == null || from.isBlank()) {
            throw new IllegalStateException(
                    "SMTP 발송에는 발신 주소(app.auth.email-verification.from / MAIL_FROM)가 필요합니다.");
        }
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(String previousEmail, String maskedNewEmail, Instant changedAt) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(previousEmail);
        message.setSubject("[Fruition] 계정 이메일이 변경되었습니다");
        message.setText("Fruition 계정의 로그인 이메일이 변경되었습니다.\n\n"
                + "새 이메일: " + maskedNewEmail + "\n"
                + "변경 시각: " + CHANGED_AT.format(changedAt) + "\n\n"
                + "본인이 변경했다면 이 메일을 무시하셔도 됩니다.\n\n"
                + "본인이 변경하지 않았다면 계정이 도용되었을 수 있습니다. 이 주소로는 더 이상 로그인하거나"
                + " 비밀번호를 재설정할 수 없으니, 즉시 Fruition 운영팀에 알려 계정 복구를 요청해 주세요."
                + " 같은 비밀번호를 쓰는 다른 서비스의 비밀번호도 바꾸시길 권합니다.");
        mailSender.send(message);
        log.info("[이메일 변경 알림 발송 완료]");
    }
}
