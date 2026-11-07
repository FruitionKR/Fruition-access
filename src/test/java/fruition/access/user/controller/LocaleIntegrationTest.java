package fruition.access.user.controller;

import fruition.TestcontainersConfiguration;
import fruition.access.user.domain.User;
import fruition.access.user.mail.EmailVerificationSender;
import fruition.access.user.repository.UserRepository;
import fruition.access.workspace.service.DocumentInternalClient;
import fruition.shared.i18n.SupportedLocale;
import fruition.shared.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 화면 언어 저장, 내 정보의 has_password·locale, Accept-Language에 따른 오류 문구와 메일 언어를 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class LocaleIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository users;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean DocumentInternalClient documentClient;
    @MockitoBean EmailVerificationSender verificationSender;

    @Test
    void me_reportsPasswordAndSavedLocale() throws Exception {
        User local = saveUser(User.PROVIDER_LOCAL, "hash");
        User social = saveUser("google", null);

        mockMvc.perform(authed(get("/api/auth/me"), local))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.has_password").value(true))
                .andExpect(jsonPath("$.locale").value(org.hamcrest.Matchers.nullValue()));
        mockMvc.perform(authed(get("/api/auth/me"), social))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.has_password").value(false));
    }

    @Test
    void updateLocale_savesAndReturnsMe() throws Exception {
        User user = saveUser(User.PROVIDER_LOCAL, "hash");

        mockMvc.perform(authed(patch("/api/auth/me/locale"), user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locale\":\"en\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId()))
                .andExpect(jsonPath("$.locale").value("en"));

        assertThat(jdbc.queryForObject("SELECT locale FROM users WHERE id = ?", String.class, user.getId()))
                .isEqualTo("en");
        mockMvc.perform(authed(get("/api/auth/me"), user))
                .andExpect(jsonPath("$.locale").value("en"));
    }

    @Test
    void updateLocale_rejectsUnsupportedValueWithLocalizedReason() throws Exception {
        User user = saveUser(User.PROVIDER_LOCAL, "hash");

        mockMvc.perform(authed(patch("/api/auth/me/locale"), user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locale\":\"fr\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("요청 형식이 올바르지 않습니다."))
                .andExpect(jsonPath("$.error.details[0].field").value("locale"))
                .andExpect(jsonPath("$.error.details[0].reason").value("locale은 ko, en 중 하나여야 합니다."));

        mockMvc.perform(authed(patch("/api/auth/me/locale"), user)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("The request is not valid."))
                .andExpect(jsonPath("$.error.details[0].reason").value("locale is required."));

        assertThat(jdbc.queryForObject("SELECT locale FROM users WHERE id = ?", String.class, user.getId())).isNull();
    }

    @Test
    void errorMessage_followsAcceptLanguageAndDefaultsToKorean() throws Exception {
        String korean = "이메일 또는 비밀번호가 올바르지 않습니다.";
        String english = "The email or password is incorrect.";

        login(null).andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.error.message").value(korean));
        login("en-US,en;q=0.9").andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.error.message").value(english));
        login("ja-JP").andExpect(jsonPath("$.error.message").value(korean));
        login("ko").andExpect(jsonPath("$.error.message").value(korean));
    }

    @Test
    void errorMessageArguments_areFilledWithoutNumberFormatting() throws Exception {
        User user = saveUser(User.PROVIDER_LOCAL, "hash");

        mockMvc.perform(authed(delete("/api/auth/me/sessions/1234567"), user)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SESSION_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("Session not found: id=1234567"));
        mockMvc.perform(authed(delete("/api/auth/me/sessions/1234567"), user))
                .andExpect(jsonPath("$.error.message").value("세션을 찾을 수 없습니다: id=1234567"));
    }

    @Test
    void verificationMail_usesSavedLocaleBeforeRequestLanguage() throws Exception {
        User user = saveUser(User.PROVIDER_LOCAL, "hash");
        user.changeLocale(SupportedLocale.EN);
        users.saveAndFlush(user);

        requestVerification(user.getEmail(), "password_reset", "ko").andExpect(status().isAccepted());

        verify(verificationSender).send(eq(user.getEmail()), eq("password_reset"), anyString(), eq(Locale.ENGLISH));
    }

    @Test
    void signupMail_usesRequestLanguageAndDefaultsToKorean() throws Exception {
        String englishEmail = "en-" + UUID.randomUUID() + "@example.com";
        String defaultEmail = "ko-" + UUID.randomUUID() + "@example.com";

        requestVerification(englishEmail, "signup", "en-GB").andExpect(status().isAccepted());
        requestVerification(defaultEmail, "signup", null).andExpect(status().isAccepted());

        verify(verificationSender).send(eq(englishEmail), eq("signup"), anyString(), eq(Locale.ENGLISH));
        verify(verificationSender).send(eq(defaultEmail), eq("signup"), anyString(), eq(Locale.KOREAN));
    }

    private org.springframework.test.web.servlet.ResultActions login(String acceptLanguage) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"missing-" + UUID.randomUUID() + "@example.com\",\"password\":\"password1234\"}");
        if (acceptLanguage != null) {
            request.header(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage);
        }
        return mockMvc.perform(request).andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.ResultActions requestVerification(
            String email, String purpose, String acceptLanguage) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/auth/email-verifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"purpose\":\"%s\"}".formatted(email, purpose));
        if (acceptLanguage != null) {
            request.header(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage);
        }
        return mockMvc.perform(request);
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request, User user) {
        return request.header(HttpHeaders.AUTHORIZATION,
                "Bearer " + jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail()));
    }

    private User saveUser(String provider, String passwordHash) {
        String id = "user_" + UUID.randomUUID().toString().replace("-", "");
        return users.saveAndFlush(new User(id, id + "@example.com", provider, "언어 테스트", passwordHash));
    }
}
