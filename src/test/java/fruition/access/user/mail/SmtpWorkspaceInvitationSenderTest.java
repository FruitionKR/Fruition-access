package fruition.access.user.mail;

import fruition.access.workspace.exception.InvitationSendException;
import fruition.shared.i18n.TestMessageSources;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SmtpWorkspaceInvitationSenderTest {

    private static final String URL = "http://localhost:3000/invitations/tok";

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final SmtpWorkspaceInvitationSender sender =
            new SmtpWorkspaceInvitationSender(mailSender, TestMessageSources.create(), "no-reply@fruition.app");

    @Test
    void korean_keepsExistingTextAndQuotesWorkspaceName() {
        sender.send("invitee@example.com", "팀 {공간}", "홍길동", URL, Locale.KOREAN);

        SimpleMailMessage message = sent();
        assertThat(message.getTo()).containsExactly("invitee@example.com");
        assertThat(message.getSubject()).isEqualTo("[Fruition] 팀 {공간} 워크스페이스에 초대되었습니다");
        assertThat(message.getText()).isEqualTo("홍길동님이 회원님을 '팀 {공간}' 워크스페이스에 초대했습니다.\n\n"
                + "아래 링크에서 초대를 수락하세요.\n"
                + URL + "\n\n"
                + "이 링크는 이 메일을 받은 주소의 계정으로 로그인했을 때만 수락됩니다.\n"
                + "초대를 요청하지 않으셨다면 이 메일을 무시하셔도 됩니다.");
    }

    @Test
    void english_usesEnglishTemplate() {
        sender.send("invitee@example.com", "Team's space", "Alex", URL, Locale.ENGLISH);

        SimpleMailMessage message = sent();
        assertThat(message.getSubject()).isEqualTo("[Fruition] You have been invited to the Team's space workspace");
        assertThat(message.getText())
                .startsWith("Alex has invited you to the 'Team's space' workspace.")
                .contains(URL)
                .doesNotContain("님");
    }

    @Test
    void mailFailure_isWrapped() {
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> sender.send("invitee@example.com", "팀", "홍길동", URL, Locale.KOREAN))
                .isInstanceOf(InvitationSendException.class);
    }

    private SimpleMailMessage sent() {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }
}
