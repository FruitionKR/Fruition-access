package fruition.shared.i18n;

import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.nio.charset.StandardCharsets;

/** 단위 테스트용 메시지 번들. application.properties의 spring.messages 설정과 같게 맞춘다. */
public final class TestMessageSources {

    private TestMessageSources() {}

    public static MessageSource create() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasenames("i18n/messages", "i18n/mail");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);
        return source;
    }
}
