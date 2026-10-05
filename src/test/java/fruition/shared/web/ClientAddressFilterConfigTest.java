package fruition.shared.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.ServerProperties;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientAddressFilterConfigTest {

    /**
     * 0 이하면 헤더를 버리고 TCP peer(ALB 주소)를 써서 전원이 한 예산을 나눠 쓰고, 너무 크면
     * 체인이 늘 짧다고 보고 같은 결과가 난다. 둘 다 조용히 전원 잠금으로 이어지므로 기동에서 막는다.
     */
    @Test
    void rejectsOutOfRangeTrustedProxyCount() {
        for (int value : new int[] {-1, 0, 6, 100}) {
            assertThatThrownBy(() -> new ClientAddressFilterConfig().clientAddressFilter(new ServerProperties(), value))
                    .as("trusted-proxy-count=%d", value)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** ALB 하나(1), CloudFront+ALB(2) 같은 실제 구성은 받는다. */
    @Test
    void acceptsRealisticTrustedProxyCount() {
        for (int value : new int[] {1, 2, 5}) {
            assertThatCode(() -> new ClientAddressFilterConfig().clientAddressFilter(new ServerProperties(), value))
                    .doesNotThrowAnyException();
        }
    }
}
