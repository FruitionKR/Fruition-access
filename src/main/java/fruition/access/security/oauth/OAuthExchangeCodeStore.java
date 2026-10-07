package fruition.access.security.oauth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * OAuth 로그인 성공 후 프런트로 전달하는 1회용 교환 코드 저장소.
 * 다중 인스턴스에서 어느 인스턴스로 교환 요청이 와도 소비할 수 있도록 Redis에 저장한다.
 * consume은 GETDEL로 원자적 1회 사용을 보장하고, 만료는 Redis TTL이 처리한다.
 */
@Component
public class OAuthExchangeCodeStore {

    private static final Duration TTL = Duration.ofSeconds(60);
    private static final String KEY_PREFIX = "oauth:exchange:";
    private static final String LINK_KEY_PREFIX = "oauth:link:";
    private static final String LINK_CODE_KEY_PREFIX = "oauth:link-code:";

    /** 소셜 인증을 마쳤지만 아직 확정하지 않은 연동. */
    public record PendingLink(String userId, String provider, String providerUserId) {}

    private final StringRedisTemplate redisTemplate;

    public OAuthExchangeCodeStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public String issue(String userId) {
        String code = generateCode();
        redisTemplate.opsForValue().set(KEY_PREFIX + code, userId, TTL);
        return code;
    }

    public Optional<String> consume(String code) {
        return Optional.ofNullable(redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + code));
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

    private String generateCode() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
