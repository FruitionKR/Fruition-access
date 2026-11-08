package fruition.access.user.mail;

import fruition.access.user.exception.EmailVerificationSendException;
import fruition.shared.i18n.TestMessageSources;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SmtpEmailVerificationSenderTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);

    @Test
    void send_signup_setsFromToSubjectAndCode() {
        SmtpEmailVerificationSender sender = new SmtpEmailVerificationSender(mailSender, TestMessageSources.create(), "no-reply@fruition.app");

        sender.send("user@example.com", "signup", "123456", Locale.KOREAN);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage message = captor.getValue();
        assertThat(message.getFrom()).isEqualTo("no-reply@fruition.app");
        assertThat(message.getTo()).containsExactly("user@example.com");
        assertThat(message.getSubject()).contains("회원가입");
        assertThat(message.getText()).contains("123456");
    }

    @Test
    void send_passwordReset_usesResetSubject() {
        SmtpEmailVerificationSender sender = new SmtpEmailVerificationSender(mailSender, TestMessageSources.create(), "no-reply@fruition.app");

        sender.send("user@example.com", "password_reset", "654321", Locale.KOREAN);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getSubject()).contains("비밀번호 재설정");
    }

    @Test
    void constructor_rejectsBlankFrom() {
        assertThatThrownBy(() -> new SmtpEmailVerificationSender(mailSender, TestMessageSources.create(), " "))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void send_wrapsMailFailure() {
        SmtpEmailVerificationSender sender = new SmtpEmailVerificationSender(mailSender, TestMessageSources.create(), "no-reply@fruition.app");
        doThrow(new MailSendException("smtp down")).when(mailSender).send(org.mockito.ArgumentMatchers.any(SimpleMailMessage.class));

        assertThatThrownBy(() -> sender.send("user@example.com", "signup", "123456", Locale.KOREAN))
                .isInstanceOf(EmailVerificationSendException.class);
    }
    @Test
    void send_emailChange_explainsPurposeInSubjectAndBody() {
        SmtpEmailVerificationSender sender = new SmtpEmailVerificationSender(mailSender, TestMessageSources.create(), "no-reply@fruition.app");
        sender.send("new@example.com", "email_change", "123456", Locale.KOREAN);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getSubject()).contains("이메일 변경");
        assertThat(captor.getValue().getText()).contains("이메일 변경", "123456").doesNotContain("회원가입");
    }

    @Test
    void send_passwordChange_explainsPurposeInSubjectAndBody() {
        SmtpEmailVerificationSender sender = new SmtpEmailVerificationSender(mailSender, TestMessageSources.create(), "no-reply@fruition.app");
        sender.send("user@example.com", "password_change", "123456", Locale.KOREAN);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getSubject()).contains("비밀번호 변경");
        assertThat(captor.getValue().getText()).contains("비밀번호 변경", "123456").doesNotContain("회원가입");
    }

    @Test
    void send_english_usesEnglishSubjectAndBody() {
        SmtpEmailVerificationSender sender = new SmtpEmailVerificationSender(mailSender, TestMessageSources.create(), "no-reply@fruition.app");
        sender.send("user@example.com", "password_reset", "012345", Locale.ENGLISH);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getSubject()).isEqualTo("[Fruition] Password reset verification code");
        assertThat(captor.getValue().getText())
                .isEqualTo("Here is your Fruition password reset verification code.\n\n"
                        + "Verification code: 012345\n\n"
                        + "If you did not request this code, you can ignore this email.");
    }

    @Test
    void send_korean_keepsExistingText() {
        SmtpEmailVerificationSender sender = new SmtpEmailVerificationSender(mailSender, TestMessageSources.create(), "no-reply@fruition.app");
        sender.send("user@example.com", "unknown", "123456", Locale.KOREAN);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getSubject()).isEqualTo("[Fruition] 인증번호");
        assertThat(captor.getValue().getText())
                .isEqualTo("Fruition 회원가입 인증번호는 아래와 같습니다.\n\n"
                        + "인증번호: 123456\n\n"
                        + "인증번호를 요청하지 않으셨다면 이 메일을 무시하셔도 됩니다.");
    }
}
