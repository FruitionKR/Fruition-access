package fruition.shared.i18n;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * 화면·오류 응답·메일에 쓰는 언어. 사용자가 저장하는 값({@code users.locale})과 API 값이 {@link #code()}다.
 * 지원하지 않는 언어는 모두 한국어로 맞춘다 — 언어를 다루기 전과 같은 동작이 기본값이 되게 하기 위해서다.
 */
public enum SupportedLocale {
    KO("ko", Locale.KOREAN),
    EN("en", Locale.ENGLISH);

    public static final SupportedLocale DEFAULT = KO;

    private final String code;
    private final Locale locale;

    SupportedLocale(String code, Locale locale) {
        this.code = code;
        this.locale = locale;
    }

    public String code() {
        return code;
    }

    public Locale toLocale() {
        return locale;
    }

    /** 저장된 값이나 요청 값. 지원하지 않거나 null이면 비어 있다. */
    public static Optional<SupportedLocale> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(value -> value.code.equals(code)).findFirst();
    }

    /** 언어 부분만 본다(en-US → EN). 지원하지 않거나 null이면 {@link #DEFAULT}. */
    public static SupportedLocale from(Locale locale) {
        if (locale == null) {
            return DEFAULT;
        }
        return fromCode(locale.getLanguage()).orElse(DEFAULT);
    }
}
