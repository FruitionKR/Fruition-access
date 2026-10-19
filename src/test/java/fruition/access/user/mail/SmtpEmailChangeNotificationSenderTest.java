package fruition.access.user.mail;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SmtpEmailChangeNotificationSenderTest {

    @Test
    void sendsToPreviousEmailWithMaskedNewEmailTimeAndGuidance() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        var sender = new SmtpEmailChangeNotificationSender(mailSender, "no-reply@fruition.app");

        sender.send("old@example.com", "ne*****@example.com", Instant.parse("2026-10-19T13:05:00Z"));

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage message = captor.getValue();
        assertThat(message.getTo()).containsExactly("old@example.com");
        assertThat(message.getSubject()).contains("이메일이 변경되었습니다");
        assertThat(message.getText())
                .contains("ne*****@example.com", "2026-10-19 22:05 (KST)", "본인이 변경하지 않았다면")
                .doesNotContain("newuser");
    }
}
