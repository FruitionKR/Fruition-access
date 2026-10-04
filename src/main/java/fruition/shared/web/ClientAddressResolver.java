package fruition.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * rate limit 키로 쓸 수 있는 클라이언트 주소를 신뢰할 수 있는 위치에서 뽑는다.
 *
 * <p>{@code HttpServletRequest#getRemoteAddr()}를 그대로 쓰면 안 된다. Spring의
 * ForwardedHeaderFilter는 {@code X-Forwarded-For}의 <b>맨 왼쪽</b> 값을 remote address로
 * 복원하고(ForwardedHeaderUtils.parseForwardedFor는 토큰 배열의 [0]을 쓴다) 신뢰 프록시
 * allowlist가 없다. AWS ALB는 XFF를 덮어쓰지 않고 뒤에 덧붙이므로, 클라이언트가 적어 보낸
 * 값이 맨 왼쪽에 남는다. 즉 맨 왼쪽 값은 공격자가 고르는 문자열이다.
 *
 * <p>그래서 두 가지가 동시에 뚫린다. 공격자는 요청마다 헤더를 바꿔 자기 IP 예산을 무한히
 * 늘릴 수 있고, 반대로 피해자나 사무실 NAT 주소를 적어 보내 <b>남의 예산을 태워</b> 그쪽
 * 로그인을 막을 수 있다.
 *
 * <p>대신 오른쪽에서 센다. 덧붙이는 프록시가 N개면 오른쪽에서 N번째 값이 그 프록시가 실제로
 * 본 TCP peer이고, 그보다 왼쪽은 전부 클라이언트 입력이다. 체인이 N보다 짧으면 프록시를
 * 거치지 않은 요청이므로 헤더를 버리고 TCP peer를 쓴다. 어느 경로로 가든 결과는 항상
 * "실제 TCP peer" 아니면 "신뢰 프록시가 적어 넣은 값"이고, 공격자가 고른 값은 될 수 없다.
 *
 * <h2>전제</h2>
 * ALB가 XFF를 <b>덧붙인다</b>고 가정했다. {@code routing.http.xff_header_processing.mode}가
 * 기본값({@code append})일 때가 그렇다. 이 값을 {@code preserve}로 바꾸면 클라이언트 헤더가
 * 그대로 통과해 이 계산의 전제가 깨진다. trusted-proxy-count를 0으로 내려 헤더를 무시하는
 * 우회는 쓸 수 없다 — TCP peer가 ALB 주소라 전원이 한 예산을 나눠 쓰므로 기동에서 거부한다
 * (ClientAddressFilterConfig). append를 유지해야 한다.
 */
public final class ClientAddressResolver {

    /** 필터가 해석한 주소를 담아 두는 요청 속성. */
    public static final String ATTRIBUTE = ClientAddressResolver.class.getName() + ".CLIENT_ADDRESS";

    private static final Logger log = LoggerFactory.getLogger(ClientAddressResolver.class);

    private ClientAddressResolver() {
    }

    /**
     * 요청에서 신뢰할 수 있는 클라이언트 주소를 읽는다.
     *
     * <p>필터가 심어 둔 값이 있으면 그것을 쓴다. 없으면 TCP peer로 떨어지는데, 운영에서는
     * 필터가 모든 REQUEST dispatch에 걸려 있으므로 이 경로는 필터를 끼우지 않은 테스트
     * 슬라이스에서만 나온다. 조용히 떨어지면 나중에 알아채기 어려워 WARN으로 남긴다.
     */
    public static String of(HttpServletRequest request) {
        Object resolved = request.getAttribute(ATTRIBUTE);
        if (resolved instanceof String address && !address.isBlank()) {
            return address;
        }
        log.warn("[클라이언트 주소 미해석] ClientAddressFilter를 거치지 않은 요청이라 TCP peer를 쓴다. uri={}",
                request.getRequestURI());
        return request.getRemoteAddr();
    }

    /**
     * @param forwardedFor      원본 {@code X-Forwarded-For} 헤더 값 (없으면 null)
     * @param remoteAddr        실제 TCP peer 주소
     * @param trustedProxyCount XFF에 값을 덧붙이는 신뢰 프록시 수 (ALB 하나면 1. 설정은 1~5만 허용하며, 1 미만이면 헤더를 무시하고 TCP peer를 쓴다)
     */
    public static String resolve(String forwardedFor, String remoteAddr, int trustedProxyCount) {
        if (trustedProxyCount < 1 || forwardedFor == null || forwardedFor.isBlank()) {
            return remoteAddr;
        }
        String[] entries = forwardedFor.split(",");
        int index = entries.length - trustedProxyCount;
        if (index < 0) {
            // 기대한 hop 수보다 짧다 — 프록시를 거치지 않았으므로 헤더 전체가 공격자 입력이다.
            return remoteAddr;
        }
        String candidate = entries[index].trim();
        return candidate.isEmpty() ? remoteAddr : candidate;
    }
}
