package fruition.shared.i18n;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.Arrays;

/**
 * 요청 언어를 {@code Accept-Language}에서 고른다. 지원하는 언어(ko·en)만 받고, 헤더가 없거나 그 밖의 언어면 한국어다.
 * 기본값을 JVM 언어에 맡기면 서버 설정에 따라 응답 언어가 바뀌므로 한국어로 고정한다.
 *
 * <p>{@link WebMvcConfigurer}를 구현해 {@code @WebMvcTest} 슬라이스에도 함께 올라오게 한다. 빠지면 테스트 요청이
 * MockMvc 기본 언어(영어)로 처리돼 운영과 다르게 동작한다.
 */
@Configuration
public class LocaleConfig implements WebMvcConfigurer {

    /** DispatcherServlet이 이 이름의 빈을 찾는다. */
    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setSupportedLocales(Arrays.stream(SupportedLocale.values()).map(SupportedLocale::toLocale).toList());
        resolver.setDefaultLocale(SupportedLocale.DEFAULT.toLocale());
        return resolver;
    }
}
