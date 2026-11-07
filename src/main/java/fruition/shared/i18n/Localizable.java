package fruition.shared.i18n;

/**
 * 오류 응답 문구를 메시지 번들에서 고르는 예외. 구현하지 않은 예외는 {@code error.<오류 코드>} 키를 인자 없이 쓴다.
 * 예외 자체의 메시지(로그용)는 한국어로 그대로 둔다.
 */
public interface Localizable {

    /** 메시지 번들 키. null이면 {@code error.<오류 코드>}를 쓴다. 한 오류 코드에 문구가 여럿일 때만 바꾼다. */
    default String messageKey() {
        return null;
    }

    /** 문구에 끼울 값. 숫자도 문자열로 넘겨 언어별 자릿수 구분 기호가 끼지 않게 한다. */
    default Object[] messageArgs() {
        return null;
    }
}
