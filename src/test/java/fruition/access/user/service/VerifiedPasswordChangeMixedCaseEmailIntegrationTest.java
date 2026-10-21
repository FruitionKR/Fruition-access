package fruition.access.user.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.access.user.domain.User;
import fruition.access.user.dto.VerificationConfirmRequest;
import fruition.access.user.mail.EmailVerificationSender;
import fruition.access.user.repository.UserRepository;
import fruition.shared.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 대문자가 섞인 예전 계정 이메일도 인증번호로 비밀번호를 바꿀 수 있는지 확인한다.
 * 발급은 정규화한 주소로 저장되므로, 소비할 때도 계정 이메일을 같은 방식으로 정규화해야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class VerifiedPasswordChangeMixedCaseEmailIntegrationTest {

    static final String NEW_PASSWORD = "new-password-5678";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired EmailVerificationService verificationService;
    @MockitoBean EmailVerificationSender sender;

    @Test
    void mixedCaseAccountEmail_changesPasswordWithVerificationToken() throws Exception {
        String id = "u_" + UUID.randomUUID().toString().replace("-", "");
        User user = users.saveAndFlush(new User(id, "Legacy_" + id + "@Example.COM", User.PROVIDER_LOCAL, "사용자",
                passwordEncoder.encode("old-password-1234")));
        String bearer = "Bearer " + jwtTokenProvider.generateAccessToken(id, user.getEmail(), Instant.now());

        String body = mockMvc.perform(post("/api/auth/me/password/verification").header("Authorization", bearer))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String verificationId = objectMapper.readTree(body).get("verification_id").asText();
        String normalizedEmail = user.getEmail().toLowerCase(Locale.ROOT);
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq(normalizedEmail), eq("password_change"), code.capture());
        String token = verificationService.confirm(verificationId, new VerificationConfirmRequest(code.getValue()))
                .verificationToken();

        var payload = objectMapper.createObjectNode()
                .put("new_password", NEW_PASSWORD)
                .put("verification_token", token);
        mockMvc.perform(put("/api/auth/me/password/verified").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isNoContent());

        assertThat(passwordEncoder.matches(NEW_PASSWORD, users.findById(id).orElseThrow().getPasswordHash())).isTrue();
    }
}
