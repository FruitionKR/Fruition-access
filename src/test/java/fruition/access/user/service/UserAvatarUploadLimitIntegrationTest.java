package fruition.access.user.service;

import fruition.TestcontainersConfiguration;
import fruition.access.AccessApplication;
import fruition.access.user.domain.User;
import fruition.access.user.repository.UserRepository;
import fruition.shared.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring multipart 한도(max-file-size 2MB)에 먼저 걸리는 업로드도 1MB 초과와 같은 413이어야 한다.
 * MockMvc는 multipart resolver를 거치지 않아 이 한도가 적용되지 않으므로 실제 포트를 열어 확인한다.
 * 실제 소켓으로 보내야 Tomcat이 남은 본문을 다 읽지 않고 연결을 끊어 클라이언트가 413을 못 받는 경우
 * (max-swallow-size)까지 드러난다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(classes = AccessApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class UserAvatarUploadLimitIntegrationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired UserRepository users;
    @Autowired JwtTokenProvider jwtTokenProvider;

    @Test
    void uploadBeyondMultipartLimitIsPayloadTooLarge() {
        String me = "user_" + UUID.randomUUID();
        users.saveAndFlush(new User(me, me + "@example.com", User.PROVIDER_LOCAL, "사용자", null));

        byte[] threeMegabytes = new byte[3 * 1024 * 1024];
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(threeMegabytes) {
            @Override public String getFilename() { return "me.png"; }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(jwtTokenProvider.generateAccessToken(me, me + "@example.com"));

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/auth/me/avatar", HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).contains("USER_AVATAR_TOO_LARGE");
    }
}
