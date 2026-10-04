package fruition.shared.web;

import fruition.TestcontainersConfiguration;
import fruition.access.AccessApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 클라이언트 주소 필터가 실제 앱 context에 등록돼 rate limit 키를 정하는지 고정한다.
 *
 * <p>단위 테스트는 해석 알고리즘만 본다. ClientAddressFilterConfig가 지워지거나
 * {@code server.forward-headers-strategy}가 framework로 돌아가면 요청 속성이 비고
 * {@code getRemoteAddr()} = ALB 주소가 키가 된다 — 모든 사용자가 한 예산을 나눠 써서
 * 50번 시도로 전원이 잠긴다. 그 회귀는 context 수준에서만 드러난다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(classes = AccessApplication.class, properties = "app.auth.login.ip-limit=" + ClientAddressFilterIntegrationTest.IP_LIMIT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClientAddressFilterIntegrationTest {

    static final int IP_LIMIT = 3;
    private static final String ALB_ADDRESS = "10.0.0.1";

    @Autowired ApplicationContext context;
    @Autowired MockMvc mockMvc;

    private MockHttpServletRequestBuilder login(String forwardedFor) {
        return post("/api/auth/login")
                .with(r -> {
                    r.setRemoteAddr(ALB_ADDRESS);
                    return r;
                })
                .header("X-Forwarded-For", forwardedFor)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + UUID.randomUUID() + "@example.com\",\"password\":\"wrong-password\"}");
    }

    private static String clientIp() {
        return "203.0.113." + (int) (Math.random() * 250 + 1);
    }

    @Test
    void clientAddressFilterIsRegistered() {
        assertThat(context.getBeansOfType(FilterRegistrationBean.class).values())
                .anyMatch(registration -> registration.getFilter() instanceof ClientAddressFilter);
    }

    /** 공격자가 적어 보낸 왼쪽 값도, ALB의 TCP 주소도 아닌 ALB가 덧붙인 오른쪽 끝 값이 키가 된다. */
    @Test
    void resolvesRightmostForwardedEntryNotSpoofedOrAlbAddress() throws Exception {
        String client = clientIp();

        mockMvc.perform(login("6.6.6.6, 7.7.7.7, " + client))
                .andExpect(request().attribute(ClientAddressResolver.ATTRIBUTE, client));
    }

    /**
     * 같은 ALB를 거쳐 들어온 두 클라이언트는 예산을 따로 쓴다. 한 클라이언트가 왼쪽 값을
     * 매번 바꿔 보내도 자기 예산만 태우고, 다른 클라이언트는 막히지 않는다.
     */
    @Test
    void differentClientsBehindTheSameProxyGetSeparateBuckets() throws Exception {
        String attacker = "198.51.100.77";
        String victim = "198.51.100.88";

        for (int i = 0; i < IP_LIMIT; i++) {
            mockMvc.perform(login(UUID.randomUUID() + ", " + attacker)).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(login("spoofed-" + UUID.randomUUID() + ", " + attacker))
                .andExpect(status().isTooManyRequests());

        mockMvc.perform(login(victim)).andExpect(status().isUnauthorized());
    }
}
