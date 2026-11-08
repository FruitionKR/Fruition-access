package fruition.access.user.mail;

import fruition.access.workspace.exception.InvitationSendException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Locale;

/** 운영용 SMTP 발송. 수락 링크와 수신 이메일 원문은 로그에 남기지 않는다. 제목·본문은 {@code i18n/mail}에서 고른다. */
public class SmtpWorkspaceInvitationSender implements WorkspaceInvitationSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpWorkspaceInvitationSender.class);

    private final JavaMailSender mailSender;
    private final MessageSource messageSource;
    private final String from;

    public SmtpWorkspaceInvitationSender(JavaMailSender mailSender, MessageSource messageSource, String from) {
        if (from == null || from.isBlank()) {
            throw new IllegalStateException(
                    "SMTP 발송에는 발신 주소(app.auth.email-verification.from / MAIL_FROM)가 필요합니다.");
        }
        this.mailSender = mailSender;
        this.messageSource = messageSource;
        this.from = from;
    }

    @Override
    public void send(String email, String workspaceName, String inviterName, String acceptUrl, Locale locale) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        Object[] args = {workspaceName, inviterName, acceptUrl};
        message.setSubject(messageSource.getMessage("mail.invitation.subject", args, locale));
        message.setText(messageSource.getMessage("mail.invitation.body", args, locale));
        try {
            mailSender.send(message);
            log.info("[초대 메일 발송 완료] workspaceName={}", workspaceName);
        } catch (MailException e) {
            log.warn("[초대 메일 발송 실패] operation=workspace_invitation stage=mail_send workspaceName={} error={}", workspaceName, e.getMessage());
            throw new InvitationSendException("초대 메일 발송에 실패했습니다. 잠시 후 다시 시도해 주세요.", e);
        }
    }
}
