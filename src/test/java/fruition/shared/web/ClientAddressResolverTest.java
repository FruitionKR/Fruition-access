package fruition.shared.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * X-Forwarded-For의 맨 왼쪽 값은 클라이언트가 그냥 적어 보낸 값이다. ALB는 XFF를 덮어쓰지 않고
 * 뒤에 덧붙이므로(append), 공격자가 넣은 값이 왼쪽에 남고 ALB가 본 실제 peer가 오른쪽에 붙는다.
 *
 * <p>Spring의 ForwardedHeaderFilter는 맨 왼쪽 값을 remote address로 복원하고 신뢰 프록시 검사를
 * 하지 않는다. 그 값을 rate limit 키로 쓰면 두 가지가 동시에 뚫린다. 공격자는 헤더만 바꿔
 * 자기 예산을 무한히 늘릴 수 있고, 반대로 피해자 IP를 적어 보내 피해자의 예산을 태워버릴 수 있다.
 */
class ClientAddressResolverTest {

    /** ALB가 덧붙인 오른쪽 값만 신뢰한다 — 공격자가 적어 보낸 왼쪽 값은 버린다. */
    @Test
    void ignoresClientSuppliedLeftmostEntry() {
        String resolved = ClientAddressResolver.resolve("1.2.3.4, 203.0.113.9", "10.0.0.1", 1);

        assertThat(resolved).isEqualTo("203.0.113.9");
    }

    /** 공격자가 여러 개를 넣어도 ALB가 덧붙인 값의 위치는 변하지 않는다. */
    @Test
    void ignoresAnyNumberOfSpoofedEntries() {
        String resolved = ClientAddressResolver.resolve(
                "9.9.9.9, 8.8.8.8, 7.7.7.7, 203.0.113.9", "10.0.0.1", 1);

        assertThat(resolved).isEqualTo("203.0.113.9");
    }

    /** 헤더를 넣지 않은 정상 클라이언트는 자기 주소로 해석된다. */
    @Test
    void honestClientResolvesToItsOwnAddress() {
        assertThat(ClientAddressResolver.resolve("203.0.113.9", "10.0.0.1", 1))
                .isEqualTo("203.0.113.9");
    }

    /** 프록시가 없는 로컬 실행에서는 TCP peer를 쓴다. */
    @Test
    void missingHeaderFallsBackToTcpPeer() {
        assertThat(ClientAddressResolver.resolve(null, "127.0.0.1", 1)).isEqualTo("127.0.0.1");
        assertThat(ClientAddressResolver.resolve("  ", "127.0.0.1", 1)).isEqualTo("127.0.0.1");
    }

    /**
     * 기대한 hop 수보다 체인이 짧으면 프록시를 거치지 않은 요청이다 — 헤더 전체가
     * 공격자 입력이므로 버리고 TCP peer를 쓴다. 이 경로가 없으면 프록시를 우회해 직접
     * 붙은 공격자가 헤더만으로 주소를 고를 수 있다.
     */
    @Test
    void shorterChainThanExpectedFallsBackToTcpPeer() {
        assertThat(ClientAddressResolver.resolve("1.2.3.4", "198.51.100.7", 2))
                .isEqualTo("198.51.100.7");
    }

    /** CloudFront+ALB처럼 덧붙이는 프록시가 둘이면 오른쪽에서 두 번째가 실제 클라이언트다. */
    @Test
    void twoTrustedHopsSkipTheProxyEntry() {
        assertThat(ClientAddressResolver.resolve("1.2.3.4, 203.0.113.9, 70.1.1.1", "10.0.0.1", 2))
                .isEqualTo("203.0.113.9");
    }

    /** 프록시가 없다고 선언하면 헤더를 아예 보지 않는다. */
    @Test
    void zeroTrustedProxiesIgnoresHeaderEntirely() {
        assertThat(ClientAddressResolver.resolve("1.2.3.4", "198.51.100.7", 0))
                .isEqualTo("198.51.100.7");
    }

    /** 필터가 심어둔 주소를 요청에서 꺼내 쓴다. */
    @Test
    void ofReadsAddressStoredByFilter() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.setAttribute(ClientAddressResolver.ATTRIBUTE, "203.0.113.9");

        assertThat(ClientAddressResolver.of(request)).isEqualTo("203.0.113.9");
    }

    /** 필터를 거치지 않은 요청(테스트 슬라이스 등)은 TCP peer로 떨어진다. */
    @Test
    void ofFallsBackToRemoteAddrWithoutFilter() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");

        assertThat(ClientAddressResolver.of(request)).isEqualTo("10.0.0.1");
    }
}
