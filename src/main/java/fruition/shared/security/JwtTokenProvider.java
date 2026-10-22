package fruition.shared.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.UnsupportedJwtException;
import org.springframework.beans.factory.annotation.Autowired;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
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
    private final SecretKey legacySigningKey;
    private final boolean legacySigning;
    private final PrivateKey signingKey;
    private final JwtParser rsaParser;
    private final JwtParser legacyParser;
    private final Instant legacyUntil;
    private final String keyId;
    private final long accessTokenExpirationSeconds;
    private final String issuer;
    private final String audience;

    /** Local/test compatibility; production requires an RSA public key. */
    public JwtTokenProvider(String secret, long ttl, String issuer, String audience) {
        this(secret, ttl, issuer, audience, "", "", "", "", "", "", "");
    }

    public JwtTokenProvider(String secret, long ttl, String issuer, String audience,
                            String publicPem, String keyId, String previousPem, String previousKeyId,
                            String profiles, String legacyUntil, String privatePem) {
        this(secret, ttl, issuer, audience, publicPem, keyId, previousPem, previousKeyId,
                profiles, legacyUntil, privatePem, "rsa");
    }

    @Autowired
    public JwtTokenProvider(@Value("${app.jwt.secret:}") String secret,
                            @Value("${app.jwt.access-token-expiration-seconds}") long accessTokenExpirationSeconds,
                            @Value("${app.jwt.issuer}") String issuer,
                            @Value("${app.jwt.audience}") String audience,
                            @Value("${app.jwt.public-key:}") String publicPem,
                            @Value("${app.jwt.key-id:}") String keyId,
                            @Value("${app.jwt.previous-public-key:}") String previousPem,
                            @Value("${app.jwt.previous-key-id:}") String previousKeyId,
                            @Value("${spring.profiles.active:}") String profiles,
                            @Value("${app.jwt.legacy-until:}") String legacyUntil,
                            @Value("${app.jwt.private-key:}") String privatePem,
                            @Value("${app.jwt.signing-mode:rsa}") String signingMode) {
        this.accessTokenExpirationSeconds = accessTokenExpirationSeconds;
        this.issuer = issuer;
        this.audience = audience;
        this.keyId = keyId;
        if (!java.util.Set.of("rsa", "legacy").contains(signingMode)) {
            throw new IllegalArgumentException("JWT signing mode must be rsa or legacy");
        }
        this.legacySigning = "legacy".equals(signingMode);
        boolean production = Arrays.stream(profiles.split(",")).anyMatch(p -> p.trim().equals("production"));
        if (publicPem.isBlank()) {
            if (production || legacySigning) {
                throw new IllegalArgumentException("Production JWT requires RSA verification keys");
            }
            this.legacySigningKey = null;
            this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
            this.signingKey = null;
            this.rsaParser = null;
            this.legacyUntil = null;
            this.legacyParser = legacyParser(key, issuer, audience);
        } else {
            if (keyId.isBlank() || (!previousPem.isBlank() && (previousKeyId.isBlank() || keyId.equals(previousKeyId)))) {
                throw new IllegalArgumentException("JWT key IDs must be nonempty and distinct");
            }
            PublicKey current = parsePublicKey(publicPem);
            PublicKey previous = previousPem.isBlank() ? null : parsePublicKey(previousPem);
            this.signingKey = parsePrivateKey(privatePem);
            if (!((RSAKey) signingKey).getModulus().equals(((RSAKey) current).getModulus())) {
                throw new IllegalArgumentException("JWT RSA key pair does not match");
            }
            this.key = null;
            this.rsaParser = Jwts.parser().keyLocator(header -> {
                if (!"RS256".equals(header.get("alg"))) {
                    throw new UnsupportedJwtException("Only RS256 is accepted");
                }
                if (keyId.equals(header.get("kid"))) return current;
                if (previous != null && previousKeyId.equals(header.get("kid"))) return previous;
                throw new UnsupportedJwtException("Unknown JWT key ID");
            })
                    .requireIssuer(issuer).requireAudience(audience).build();
            if (!secret.isBlank()) {
                if (legacyUntil.isBlank()) {
                    throw new IllegalArgumentException("Legacy JWT verification requires an explicit expiry");
                }
                this.legacyUntil = Instant.parse(legacyUntil);
                if (this.legacyUntil.isAfter(Instant.now().plus(Duration.ofHours(24)))) {
                    throw new IllegalArgumentException("Legacy JWT transition cannot exceed 24 hours");
                }
                this.legacySigningKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
                this.legacyParser = legacyParser(legacySigningKey, issuer, audience);
            } else {
                this.legacyUntil = null;
                this.legacyParser = null;
                this.legacySigningKey = null;
            }
        }
        if (legacySigning && (legacySigningKey == null || legacyUntil.isBlank()
                || !this.legacyUntil.isAfter(Instant.now()))) {
            throw new IllegalArgumentException("Legacy signing requires a secret and a future transition deadline");
        }
    }

    private static JwtParser legacyParser(SecretKey key, String issuer, String audience) {
        return Jwts.parser().keyLocator(header -> {
            if (!java.util.Set.of("HS256", "HS384", "HS512").contains(header.get("alg"))) {
                throw new UnsupportedJwtException("Only HMAC is accepted during legacy verification");
            }
            return key;
        }).requireIssuer(issuer).requireAudience(audience).build();
    }

    private static PublicKey parsePublicKey(String pem) {
        try {
            PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decodePem(pem, "PUBLIC KEY")));
            if (((RSAKey) key).getModulus().bitLength() < 2048) throw new IllegalArgumentException("RSA key must be at least 2048 bits");
            return key;
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid JWT RSA public key");
        }
    }

    private static byte[] decodePem(String pem, String type) {
        if (!pem.startsWith("-----BEGIN " + type + "-----")) throw new IllegalArgumentException("Invalid PEM type");
        return Base64.getDecoder().decode(pem.replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "").replaceAll("\\s", ""));
    }

    private static PrivateKey parsePrivateKey(String pem) {
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decodePem(pem, "PRIVATE KEY")));
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid JWT RSA private key");
        }
    }

    public String generateAccessToken(String userId, String email) {
        return generateAccessToken(userId, email, null);
    }

    /**
     * {@code authTime}은 사용자가 비밀번호·소셜·MFA로 직접 인증한 시각이다. 로그인으로 발급할 때만 넣고
     * refresh로 다시 발급할 때는 넣지 않는다. 회원 탈퇴처럼 최근 인증이 필요한 요청이 이 값을 본다.
     */
    public String generateAccessToken(String userId, String email, Instant authTime) {
        if (rsaParser != null && signingKey == null) {
            throw new IllegalStateException("This service only verifies JWTs");
        }
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(userId)
                .issuer(issuer)
                .audience().add(audience).and()
                .claim("email", email);
        if (authTime != null) {
            builder.claim("auth_time", authTime.getEpochSecond());
        }
        builder
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(accessTokenExpirationSeconds)));
        // Every upgraded replica verifies RSA before any replica starts issuing RSA tokens.
        // Expiry automatically ends legacy issuance even if deployment configuration is left unchanged.
        if (legacySigning && now.isBefore(legacyUntil)) {
            return builder.signWith(legacySigningKey).compact();
        }
        return signingKey == null ? builder.signWith(key).compact()
                : builder.header().keyId(keyId).and().signWith(signingKey, Jwts.SIG.RS256).compact();
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
        if (rsaParser == null) return legacyParser.parseSignedClaims(token).getPayload();
        try {
            return rsaParser.parseSignedClaims(token).getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            // Time-bounded migration only; removing the old secret disables this path entirely.
            if (legacyParser != null && Instant.now().isBefore(legacyUntil)) {
                return legacyParser.parseSignedClaims(token).getPayload();
            }
            throw e;
        }
    }
}
