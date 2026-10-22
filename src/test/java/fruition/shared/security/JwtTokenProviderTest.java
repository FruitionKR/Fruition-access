package fruition.shared.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;

class JwtTokenProviderTest {
    private static final String LEGACY = "jwt-secret-INSECURE-NOT-FOR-PRODUCTION-test";
    private static KeyPair pair() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
    private static String pem(String type, byte[] bytes) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder().encodeToString(bytes)
                + "\n-----END " + type + "-----";
    }
    private JwtTokenProvider verifier(KeyPair pair, KeyPair previous, String legacy, String until) {
        return new JwtTokenProvider(legacy, 900, "fruition-access", "fruition-api",
                pem("PUBLIC KEY", pair.getPublic().getEncoded()), "current",
                previous == null ? "" : pem("PUBLIC KEY", previous.getPublic().getEncoded()),
                previous == null ? "" : "previous", "production", until, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
    }
    private String token(KeyPair pair, String kid, String issuer, String audience, Instant expiry) {
        return Jwts.builder().subject("audit-test-user").issuer(issuer).audience().add(audience).and()
                .expiration(Date.from(expiry)).header().keyId(kid).and()
                .signWith(pair.getPrivate(), Jwts.SIG.RS256).compact();
    }
    @Test void verifiesOnlyTrustedKeysAlgorithmIssuerAudienceAndExpiry() throws Exception {
        var pair = pair();
        var verifier = verifier(pair, null, "", "");
        String valid = token(pair, "current", "fruition-access", "fruition-api", Instant.now().plusSeconds(60));
        assertTrue(verifier.isValid(valid));
        assertEquals("audit-test-user", verifier.extractUserId(valid));
        assertFalse(verifier.isValid(token(pair(), "current", "fruition-access", "fruition-api", Instant.now().plusSeconds(60))));
        assertFalse(verifier.isValid(token(pair, "unknown", "fruition-access", "fruition-api", Instant.now().plusSeconds(60))));
        assertFalse(verifier.isValid(token(pair, "current", "wrong", "fruition-api", Instant.now().plusSeconds(60))));
        assertFalse(verifier.isValid(token(pair, "current", "fruition-access", "wrong", Instant.now().plusSeconds(60))));
        assertFalse(verifier.isValid(token(pair, "current", "fruition-access", "fruition-api", Instant.now().minusSeconds(60))));
        assertFalse(verifier.isValid("not-a-jwt"));
        String confused = Jwts.builder().subject("audit-test-user").issuer("fruition-access")
                .audience().add("fruition-api").and().header().keyId("current").and()
                .signWith(Keys.hmacShaKeyFor(pair.getPublic().getEncoded()), Jwts.SIG.HS256).compact();
        assertFalse(verifier.isValid(confused));
    }
    @Test void permitsExplicitPreviousPublicKeyForRotation() throws Exception {
        var old = pair();
        var verifier = verifier(pair(), old, "", "");
        assertTrue(verifier.isValid(token(old, "previous", "fruition-access", "fruition-api", Instant.now().plusSeconds(60))));
    }
    @Test void legacyTransitionExpiresAndCanBeRemoved() throws Exception {
        var pair = pair();
        var old = new JwtTokenProvider(LEGACY, 900, "fruition-access", "fruition-api");
        String token = old.generateAccessToken("audit-test-user", "test@example.invalid");
        assertTrue(verifier(pair, null, LEGACY, Instant.now().plusSeconds(60).toString()).isValid(token));
        assertFalse(verifier(pair, null, LEGACY, Instant.now().minusSeconds(1).toString()).isValid(token));
        assertFalse(verifier(pair, null, "", "").isValid(token));
        assertThrows(IllegalArgumentException.class, () -> verifier(pair, null, LEGACY, ""));
        assertThrows(IllegalArgumentException.class, () -> verifier(pair, null, LEGACY, Instant.now().plusSeconds(172800).toString()));
    }
    @Test void accessSignsWithRsa() throws Exception {
        var provider = verifier(pair(), null, "", "");
        assertTrue(provider.isValid(provider.generateAccessToken("user", "test@example.invalid")));
    }
    @Test void productionRejectsHmacOnlyAndMismatchedSigningKey() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new JwtTokenProvider(
                LEGACY, 900, "fruition-access", "fruition-api", "", "", "", "", "production", "", ""));
        var current = pair();
        var unrelated = pair();
        assertThrows(IllegalArgumentException.class, () -> new JwtTokenProvider(
                "", 900, "fruition-access", "fruition-api",
                pem("PUBLIC KEY", current.getPublic().getEncoded()), "current", "", "", "production", "",
                pem("PRIVATE KEY", unrelated.getPrivate().getEncoded())));
        assertThrows(IllegalArgumentException.class, () -> new JwtTokenProvider(
                "", 900, "fruition-access", "fruition-api",
                pem("PUBLIC KEY", current.getPublic().getEncoded()), "current", "", "", "production", "", ""));
    }
    @Test void rejectsWeakRsaAndUnexpectedRsaAlgorithm() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        assertThrows(IllegalArgumentException.class, () -> verifier(generator.generateKeyPair(), null, "", ""));
        var current = pair();
        String wrongAlgorithm = Jwts.builder().subject("user").issuer("fruition-access")
                .audience().add("fruition-api").and().header().keyId("current").and()
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(current.getPrivate(), Jwts.SIG.RS384).compact();
        assertFalse(verifier(current, null, "", "").isValid(wrongAlgorithm));
    }
    private JwtTokenProvider signingMode(KeyPair pair, String secret, String deadline, String mode) {
        return new JwtTokenProvider(secret, 900, "fruition-access", "fruition-api",
                pem("PUBLIC KEY", pair.getPublic().getEncoded()), "current", "", "", "production",
                deadline, pem("PRIVATE KEY", pair.getPrivate().getEncoded()), mode);
    }
    @Test void legacySigningPhaseStillVerifiesRsaAndTransitionsAtDeadline() throws Exception {
        var current = pair();
        var deadline = Instant.now().plusSeconds(2);
        var dual = signingMode(current, LEGACY, deadline.toString(), "legacy");
        var old = new JwtTokenProvider(LEGACY, 900, "fruition-access", "fruition-api");
        var rsa = verifier(current, null, "", "");
        String legacyToken = dual.generateAccessToken("user", "test@example.invalid");
        assertTrue(old.isValid(legacyToken));
        assertFalse(rsa.isValid(legacyToken));
        assertTrue(dual.isValid(rsa.generateAccessToken("user", "test@example.invalid")));
        Thread.sleep(Math.max(0, java.time.Duration.between(Instant.now(), deadline).toMillis()) + 100);
        String rsaToken = dual.generateAccessToken("user", "test@example.invalid");
        assertTrue(rsa.isValid(rsaToken));
        assertFalse(old.isValid(rsaToken));
        assertFalse(dual.isValid(legacyToken));
    }
    @Test void legacySigningRequiresExplicitBoundedFutureDeadlineAndSecret() throws Exception {
        var current = pair();
        String future = Instant.now().plusSeconds(60).toString();
        assertThrows(IllegalArgumentException.class, () -> signingMode(current, "", future, "legacy"));
        assertThrows(IllegalArgumentException.class, () -> signingMode(current, LEGACY, "", "legacy"));
        assertThrows(IllegalArgumentException.class, () -> signingMode(current, LEGACY, Instant.now().minusSeconds(1).toString(), "legacy"));
        assertThrows(IllegalArgumentException.class, () -> signingMode(current, LEGACY, Instant.now().plusSeconds(172800).toString(), "legacy"));
        assertThrows(IllegalArgumentException.class, () -> signingMode(current, "", "", "typo"));
    }
    @Test void requiresExpiryAndNonblankSubjectForRsaAndLegacy() throws Exception {
        var current = pair();
        var provider = verifier(current, null, LEGACY, Instant.now().plusSeconds(60).toString());
        for (boolean rsa : new boolean[] {true, false}) {
            for (String subject : new String[] {null, "", "   ", "user"}) {
                var builder = Jwts.builder().issuer("fruition-access").audience().add("fruition-api").and();
                if (subject != null) builder.subject(subject);
                if (!"user".equals(subject)) builder.expiration(Date.from(Instant.now().plusSeconds(60)));
                String invalid = rsa ? builder.header().keyId("current").and().signWith(current.getPrivate(), Jwts.SIG.RS256).compact()
                        : builder.signWith(Keys.hmacShaKeyFor(LEGACY.getBytes(java.nio.charset.StandardCharsets.UTF_8))).compact();
                assertFalse(provider.isValid(invalid));
            }
        }
    }
}
