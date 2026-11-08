package fruition.shared.i18n;

import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;

/** 지금 처리 중인 요청의 언어. 요청 밖(예약 작업 등)이거나 정해지지 않았으면 한국어다. */
public final class RequestLocale {

    private RequestLocale() {}

    public static SupportedLocale current() {
        // LocaleContextHolder.getLocale()은 요청 밖에서 JVM 기본 언어를 돌려주므로 컨텍스트가 있을 때만 쓴다.
        LocaleContext context = LocaleContextHolder.getLocaleContext();
        return context == null ? SupportedLocale.DEFAULT : SupportedLocale.from(context.getLocale());
    }
}
