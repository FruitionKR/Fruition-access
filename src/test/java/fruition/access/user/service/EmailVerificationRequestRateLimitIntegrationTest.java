package fruition.access.user.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.access.user.domain.User;
import fruition.access.user.mail.EmailVerificationSender;
import fruition.access.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 인증번호 요청의 IP별·이메일별 제한을 실제 Redis·DB로 확인한다.
 * 거절(409)은 발급 기록을 남기지 않아 쿨다운·일일 한도에 걸리지 않으므로, 요청 자체를 세는 이 제한이 반복 조회를 막는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class EmailVerificationRequestRateLimitIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository users;
    @MockitoBean EmailVerificationSender sender;

    @Test
    void repeatedRejectedSignupRequests_areLimitedPerEmail() throws Exception {
        String email = existingUserEmail();
        String ip = randomIp();

        for (int i = 0; i < 5; i++) {
            requestVerification(email, "signup", ip).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("DUPLICATE_EMAIL"));
        }
        requestVerification(email, "signup", ip).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.error.code").value("VERIFICATION_RATE_LIMITED"));
    }

    @Test
    void repeatedRejectedRequestsForDifferentEmails_areLimitedPerIp() throws Exception {
        String ip = randomIp();

        for (int i = 0; i < 30; i++) {
            requestVerification(existingUserEmail(), "email_change", ip).andExpect(status().isConflict());
        }
        requestVerification(existingUserEmail(), "email_change", ip).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("VERIFICATION_RATE_LIMITED"));
    }

    /** 가입 화면은 중복 확인을 여러 번 한 뒤 인증번호를 요청한다. 중복 확인이 인증번호 요청 한도를 쓰지 않는다. */
    @Test
    void availabilityChecks_doNotSpendVerificationRequestLimit() throws Exception {
        String email = "new_" + UUID.randomUUID().toString().replace("-", "") + "@example.com";
        String ip = randomIp();

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/email-availability").contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(java.util.Map.of("email", email)))
                            .with(remoteAddr(ip)))
                    .andExpect(status().isOk());
        }
        requestVerification(email, "signup", ip).andExpect(status().isAccepted());
        // 쿨다운 안의 재요청은 기존처럼 발급 제한이 막는다.
        requestVerification(email, "signup", ip).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("VERIFICATION_RATE_LIMITED"));
    }

    private ResultActions requestVerification(String email, String purpose, String ip) throws Exception {
        return mockMvc.perform(post("/api/auth/email-verifications").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of("email", email, "purpose", purpose)))
                .with(remoteAddr(ip)));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor remoteAddr(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private static String randomIp() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(1, 255);
    }

    private String existingUserEmail() {
        String id = "u_" + UUID.randomUUID().toString().replace("-", "");
        return users.saveAndFlush(new User(id, id + "@example.com", User.PROVIDER_LOCAL, "사용자", "hash")).getEmail();
    }
}
