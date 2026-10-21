package fruition.access.user.service;

import fruition.access.user.mail.EmailMasker;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;

/**
 * 이메일 변경 트랜잭션 안에서 기존 이메일로 보낼 알림을 outbox({@code email_change_notifications})에 넣는다.
 * 변경과 함께 커밋되고 롤백되면 같이 사라지며, 발송은 {@link EmailChangeNotificationJob}이 요청 스레드 밖에서 한다.
 * 새 이메일은 메일에 실리는 가린 값만 저장한다. 넣지 못하면 알림 없는 변경이 남지 않도록 변경도 롤백된다.
 */
@Component
public class EmailChangeNotificationListener {

    private final JdbcTemplate jdbc;

    public EmailChangeNotificationListener(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onEmailChanged(EmailChangedEvent event) {
        jdbc.update("""
                INSERT INTO email_change_notifications (user_id, recipient_email, masked_new_email, changed_at)
                VALUES (?, ?, ?, ?)
                """, event.userId(), event.previousEmail(), EmailMasker.mask(event.newEmail()),
                Timestamp.from(event.changedAt()));
    }
}
