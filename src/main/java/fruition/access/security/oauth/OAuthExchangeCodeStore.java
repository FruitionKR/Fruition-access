package fruition.access.security.oauth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * OAuth 로그인 성공 후 프런트로 전달하는 1회용 교환 코드 저장소.
 * 다중 인스턴스에서 어느 인스턴스로 교환 요청이 와도 소비할 수 있도록 Redis에 저장한다.
 * consume은 GETDEL로 원자적 1회 사용을 보장하고, 만료는 Redis TTL이 처리한다.
 *
 * <p>데스크톱 로그인이 넘긴 PKCE code_challenge가 있으면 교환 코드와 가입 대기 토큰에 함께 저장하고,
 * 소비할 때 code_verifier가 맞지 않으면 비어 있는 결과를 준다. 코드는 그래도 소비된다.
 */
@Component
public class OAuthExchangeCodeStore {

    private static final Duration TTL = Duration.ofSeconds(60);
    private static final String KEY_PREFIX = "oauth:exchange:";
    private static final String LINK_KEY_PREFIX = "oauth:link:";
    private static final String LINK_CODE_KEY_PREFIX = "oauth:link-code:";
    private static final String SIGNUP_KEY_PREFIX = "oauth:signup:";
    /** 소셜 신규 가입자가 약관을 읽고 동의할 시간. */
    private static final Duration SIGNUP_TTL = Duration.ofMinutes(10);

    /** 소셜 인증을 마쳤지만 약관 동의 전이라 아직 계정을 만들지 않은 신규 가입. */
    public record PendingSignup(String provider, String providerUserId, String email, String name,
                                String codeChallenge) {}

    /** 소셜 인증을 마쳤지만 아직 확정하지 않은 연동. */
    public record PendingLink(String userId, String provider, String providerUserId) {}

    private final StringRedisTemplate redisTemplate;

    public OAuthExchangeCodeStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** codeChallenge는 데스크톱 로그인일 때만 있다. 웹 로그인은 null. */
    public String issue(String userId, String codeChallenge) {
        String code = generateCode();
        redisTemplate.opsForValue().set(KEY_PREFIX + code,
                codeChallenge == null ? userId : userId + ":" + codeChallenge, TTL);
        return code;
    }

    /** 코드를 소비하고 사용자 ID를 돌려준다. challenge가 묶인 코드인데 verifier가 맞지 않으면 비어 있다. */
    public Optional<String> consume(String code, String codeVerifier) {
        return Optional.ofNullable(redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + code))
                .map(value -> value.split(":", 2))
                .filter(parts -> verifierMatches(parts.length == 2 ? parts[1] : null, codeVerifier))
                .map(parts -> parts[0]);
    }

    /** 로그인한 사용자가 소셜 계정 연동을 시작할 때 쓰는 1회용 토큰. provider에 묶인다. */
    public String issueLinkToken(String userId, String provider) {
        String token = generateCode();
        redisTemplate.opsForValue().set(LINK_KEY_PREFIX + token, provider + ":" + userId, TTL);
        return token;
    }

    /** 토큰을 소비하고 연동 대상 사용자 ID를 돌려준다. 다른 provider용 토큰이면 비어 있다. */
    public Optional<String> consumeLinkToken(String token, String provider) {
        String value = redisTemplate.opsForValue().getAndDelete(LINK_KEY_PREFIX + token);
        String prefix = provider + ":";
        if (value == null || !value.startsWith(prefix)) {
            return Optional.empty();
        }
        return Optional.of(value.substring(prefix.length()));
    }

    /** 연동 콜백이 프론트에 넘기는 1회용 code. 프론트가 로그인한 채로 확정 API에 제출한다. */
    public String issueLinkCode(PendingLink link) {
        String code = generateCode();
        redisTemplate.opsForValue().set(LINK_CODE_KEY_PREFIX + code,
                link.userId() + ":" + link.provider() + ":" + link.providerUserId(), TTL);
        return code;
    }

    public Optional<PendingLink> consumeLinkCode(String code) {
        return Optional.ofNullable(redisTemplate.opsForValue().getAndDelete(LINK_CODE_KEY_PREFIX + code))
                .map(value -> value.split(":", 3))
                .map(parts -> new PendingLink(parts[0], parts[1], parts[2]));
    }

    public String issueSignupToken(PendingSignup signup) {
        String token = generateCode();
        String value = String.join("\n", signup.provider(), signup.providerUserId(), signup.email(),
                signup.codeChallenge() == null ? "" : signup.codeChallenge(),
                signup.name() == null ? "" : signup.name());
        redisTemplate.opsForValue().set(SIGNUP_KEY_PREFIX + token, value, SIGNUP_TTL);
        return token;
    }

    /** 토큰을 소비한다. 데스크톱 가입이면 {@link #consume}처럼 code_verifier가 맞아야 한다. */
    public Optional<PendingSignup> consumeSignupToken(String token, String codeVerifier) {
        return Optional.ofNullable(redisTemplate.opsForValue().getAndDelete(SIGNUP_KEY_PREFIX + token))
                .map(value -> value.split("\n", 5))
                .map(parts -> new PendingSignup(parts[0], parts[1], parts[2], blankToNull(parts[4]), blankToNull(parts[3])))
                .filter(signup -> verifierMatches(signup.codeChallenge(), codeVerifier));
    }

    /** challenge가 없으면(웹 로그인) 통과. 있으면 BASE64URL(SHA256(verifier))이 challenge와 같아야 한다. */
    private static boolean verifierMatches(String codeChallenge, String codeVerifier) {
        if (codeChallenge == null) {
            return true;
        }
        if (codeVerifier == null) {
            return false;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    codeChallenge.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String blankToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    private String generateCode() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
