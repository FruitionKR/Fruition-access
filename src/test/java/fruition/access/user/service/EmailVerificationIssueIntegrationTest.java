package fruition.access.user.service;

import fruition.TestcontainersConfiguration;
import fruition.access.user.dto.EmailVerificationRequest;
import fruition.access.user.dto.VerificationConfirmRequest;
import fruition.access.user.exception.InvalidVerificationTokenException;
import fruition.access.user.mail.EmailVerificationSender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/** 인증번호 발급(재발급·동시 요청)이 실제 DB에서 이전 토큰과 rate limit을 올바르게 다루는지 확인한다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class EmailVerificationIssueIntegrationTest {

    static final String PURPOSE = "password_reset";

    @Autowired EmailVerificationService verificationService;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean EmailVerificationSender sender;

    @Test
    void reissue_invalidatesTokenConfirmedFromPreviousCode() {
        String email = uniqueEmail();
        String firstId = verificationService.request(new EmailVerificationRequest(email, PURPOSE)).verificationId();
        String firstToken = verificationService.confirm(firstId, new VerificationConfirmRequest(lastCode(email)))
                .verificationToken();
        passCooldown(email);

        String secondId = verificationService.request(new EmailVerificationRequest(email, PURPOSE)).verificationId();

        assertThatThrownBy(() -> verificationService.consumeForPasswordReset(email, firstToken))
                .isInstanceOf(InvalidVerificationTokenException.class);
        String secondToken = verificationService.confirm(secondId, new VerificationConfirmRequest(lastCode(email)))
                .verificationToken();
        assertThatCode(() -> verificationService.consumeForPasswordReset(email, secondToken)).doesNotThrowAnyException();
    }

    private String lastCode(String email) {
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(sender, atLeastOnce()).send(eq(email), eq(PURPOSE), code.capture());
        List<String> codes = code.getAllValues();
        return codes.get(codes.size() - 1);
    }

    /** 재발급 쿨다운을 지난 것으로 만든다. */
    private void passCooldown(String email) {
        jdbc.update("UPDATE email_verifications SET created_at = created_at - interval '10 minutes' WHERE email = ?",
                email);
    }

    private static String uniqueEmail() {
        return "issue_" + UUID.randomUUID().toString().replace("-", "") + "@example.com";
    }
}
