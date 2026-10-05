package fruition.shared.web;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * {@link ClientAddressFilter}를 Spring Boot가 등록하던 자리에 그대로 끼운다.
 *
 * <p>{@code server.forward-headers-strategy=none}으로 Boot의 자동 등록을 끄고 여기서 직접
 * 등록한다. {@code @ConditionalOnMissingFilterBean}에 기대 자동 설정이 물러나길 기다리는
 * 것보다 어느 필터가 도는지가 분명하다. dispatcher type과 order는
 * ServletWebServerFactoryAutoConfiguration이 쓰던 값과 같게 맞춘다.
 */
@Configuration
public class ClientAddressFilterConfig {

    /** XFF에 덧붙이는 프록시 수의 상한. CloudFront+ALB가 2라 이보다 크면 설정 실수로 본다. */
    private static final int MAX_TRUSTED_PROXY_COUNT = 5;

    @Bean
    public FilterRegistrationBean<ForwardedHeaderFilter> clientAddressFilter(
            ServerProperties serverProperties,
            @Value("${app.auth.client-address.trusted-proxy-count:1}") int trustedProxyCount) {
        // 범위를 벗어나면 해석이 늘 TCP peer(ALB 주소)로 떨어져 전원이 한 rate limit 예산을 나눠 쓴다.
        if (trustedProxyCount < 1 || trustedProxyCount > MAX_TRUSTED_PROXY_COUNT) {
            throw new IllegalArgumentException("app.auth.client-address.trusted-proxy-count는 1~"
                    + MAX_TRUSTED_PROXY_COUNT + " 사이여야 합니다: " + trustedProxyCount);
        }
        ClientAddressFilter filter = new ClientAddressFilter(trustedProxyCount);
        filter.setRelativeRedirects(serverProperties.getTomcat().isUseRelativeRedirects());
        FilterRegistrationBean<ForwardedHeaderFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
