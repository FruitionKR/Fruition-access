package fruition.access.user.mail;

import fruition.shared.i18n.TestMessageSources;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Instant;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SmtpEmailChangeNotificationSenderTest {

    @Test
    void sendsToPreviousEmailWithMaskedNewEmailTimeAndGuidance() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        var sender = new SmtpEmailChangeNotificationSender(mailSender, TestMessageSources.create(), "no-reply@fruition.app");

        sender.send("old@example.com", "ne*****@example.com", Instant.parse("2026-10-19T13:05:00Z"), Locale.KOREAN);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage message = captor.getValue();
        assertThat(message.getTo()).containsExactly("old@example.com");
        assertThat(message.getSubject()).contains("이메일이 변경되었습니다");
        assertThat(message.getText())
                .contains("ne*****@example.com", "2026-10-19 22:05 (KST)", "본인이 변경하지 않았다면")
                .doesNotContain("newuser");
    }

    @Test
    void english_usesEnglishTemplateWithSameTimeFormat() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        var sender = new SmtpEmailChangeNotificationSender(mailSender, TestMessageSources.create(), "no-reply@fruition.app");

        sender.send("old@example.com", "ne*****@example.com", Instant.parse("2026-10-19T13:05:00Z"), Locale.ENGLISH);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getSubject()).isEqualTo("[Fruition] Your account email has been changed");
        assertThat(captor.getValue().getText())
                .contains("New email: ne*****@example.com", "Changed at: 2026-10-19 22:05 (KST)",
                        "If you did not make this change")
                .doesNotContain("본인");
    }
}
