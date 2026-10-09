package fruition.shared.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

@Component
public class JwtTokenProvider {

    private final SecretKey key;
    private final long accessTokenExpirationSeconds;
    private final String issuer;
    private final String audience;

    public JwtTokenProvider(@Value("${app.jwt.secret}") String secret,
                            @Value("${app.jwt.access-token-expiration-seconds}") long accessTokenExpirationSeconds,
                            @Value("${app.jwt.issuer}") String issuer,
                            @Value("${app.jwt.audience}") String audience) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpirationSeconds = accessTokenExpirationSeconds;
        this.issuer = issuer;
        this.audience = audience;
    }

    public String generateAccessToken(String userId, String email) {
        return generateAccessToken(userId, email, null);
    }

    /**
     * {@code authTime}은 사용자가 비밀번호·소셜·MFA로 직접 인증한 시각이다. 로그인으로 발급할 때만 넣고
     * refresh로 다시 발급할 때는 넣지 않는다. 회원 탈퇴처럼 최근 인증이 필요한 요청이 이 값을 본다.
     */
    public String generateAccessToken(String userId, String email, Instant authTime) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(userId)
                .issuer(issuer)
                .audience().add(audience).and()
                .claim("email", email);
        if (authTime != null) {
            builder.claim("auth_time", authTime.getEpochSecond());
        }
        return builder
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(accessTokenExpirationSeconds)))
                .signWith(key)
                .compact();
    }

    public boolean isValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public String extractUserId(String token) {
        return parseClaims(token).getSubject();
    }

    /** 로그인으로 발급한 토큰이면 인증 시각을, refresh로 발급했거나 유효하지 않으면 빈 값을 돌려준다. */
    public Optional<Instant> extractAuthTime(String token) {
        try {
            Number authTime = parseClaims(token).get("auth_time", Number.class);
            return Optional.ofNullable(authTime).map(value -> Instant.ofEpochSecond(value.longValue()));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public long getAccessTokenExpirationSeconds() {
        return accessTokenExpirationSeconds;
    }

    private Claims parseClaims(String token) {
        // 서명뿐 아니라 발급자(iss)·대상(aud)까지 일치해야 유효한 토큰으로 본다.
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(issuer)
                .requireAudience(audience)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
