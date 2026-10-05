package fruition.shared.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ClientAddressFilterTest {

    /**
     * XFF가 여러 줄로 오면 RFC 9110상 한 줄을 쉼표로 이은 것과 같다. 첫 줄만 읽으면
     * 클라이언트가 보낸 줄이 체인 전체로 보여, ALB가 다음 줄에 덧붙인 값 대신 공격자 값이 키가 된다.
     */
    @Test
    void joinsMultipleForwardedForHeaderLinesBeforeParsing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "6.6.6.6");
        request.addHeader("X-Forwarded-For", "203.0.113.9");

        new ClientAddressFilter(1).doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(request.getAttribute(ClientAddressResolver.ATTRIBUTE)).isEqualTo("203.0.113.9");
    }
}
