package fruition.access.user.service;

import fruition.access.user.mail.EmailChangeNotificationSender;
import fruition.access.user.mail.EmailMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 이메일 변경이 커밋된 뒤 기존 이메일로 알림을 보낸다. 변경이 롤백되면 보내지 않고,
 * 발송이 실패해도 이미 커밋된 변경은 되돌리지 않는다(실패는 로그로만 남긴다).
 */
@Component
public class EmailChangeNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(EmailChangeNotificationListener.class);

    private final EmailChangeNotificationSender sender;

    public EmailChangeNotificationListener(EmailChangeNotificationSender sender) {
        this.sender = sender;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEmailChanged(EmailChangedEvent event) {
        try {
            sender.send(event.previousEmail(), EmailMasker.mask(event.newEmail()), event.changedAt());
        } catch (RuntimeException e) {
            log.warn("[이메일 변경 알림 발송 실패] userId={} error={}", event.userId(), e.getMessage());
        }
    }
}
