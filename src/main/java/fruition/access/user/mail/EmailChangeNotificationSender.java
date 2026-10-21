package fruition.access.user.mail;

import java.time.Instant;

/**
 * 계정 이메일이 바뀌었음을 <b>기존</b> 이메일로 알린다. 세션을 탈취한 사람이 이메일을 바꿔도
 * 원래 주인이 알아차릴 수 있게 하기 위해서다. SMTP 설정이 없는 환경에서는
 * {@link LoggingEmailChangeNotificationSender}가 로그로 남긴다.
 */
public interface EmailChangeNotificationSender {
    /** {@code maskedNewEmail}은 {@link EmailMasker#mask}로 가린 새 이메일이다. */
    void send(String previousEmail, String maskedNewEmail, Instant changedAt);
}
