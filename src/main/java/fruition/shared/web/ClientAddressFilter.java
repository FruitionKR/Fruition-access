package fruition.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.io.IOException;
import java.util.Collections;

/**
 * {@link ForwardedHeaderFilter}를 상속해, scheme·host 복원은 그대로 두고 rate limit용
 * 클라이언트 주소만 따로 해석해 요청 속성에 심는다.
 *
 * <p>왜 필터여야 하는가: ForwardedHeaderFilter는 요청을 감싸면서 {@code X-Forwarded-*} 헤더를
 * <b>가린다</b>(ForwardedHeaderRemovingRequest/ExtractingRequest의 getHeader가 null을 준다).
 * 그래서 이 필터 뒤에서는 원본 XFF를 더 읽을 수 없고, 주소 해석은 super를 호출하기 전에
 * 끝내야 한다. 또 Spring Boot는 이 필터를 {@code Ordered.HIGHEST_PRECEDENCE}로 등록하므로
 * 앞에 다른 필터를 끼울 수도 없다 — 상속이 유일하게 깔끔한 자리다.
 *
 * <p>scheme·host 복원 동작은 부모 클래스 그대로라 OAuth redirect-uri의 {@code {baseUrl}}
 * 계산은 바뀌지 않는다.
 */
public class ClientAddressFilter extends ForwardedHeaderFilter {

    private final int trustedProxyCount;

    public ClientAddressFilter(int trustedProxyCount) {
        this.trustedProxyCount = trustedProxyCount;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // super가 헤더를 가리기 전에 원본 XFF로 주소를 확정한다.
        // 여러 줄로 온 XFF는 쉼표로 이은 한 줄과 같다 — getHeader는 첫 줄만 줘서 뒤 줄에 붙은 ALB 값을 놓친다.
        String forwardedFor = String.join(",", Collections.list(request.getHeaders("X-Forwarded-For")));
        request.setAttribute(ClientAddressResolver.ATTRIBUTE, ClientAddressResolver.resolve(
                forwardedFor, request.getRemoteAddr(), trustedProxyCount));
        super.doFilterInternal(request, response, filterChain);
    }
}
