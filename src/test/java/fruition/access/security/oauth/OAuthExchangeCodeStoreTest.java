package fruition.access.security.oauth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuthExchangeCodeStoreTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;

    private OAuthExchangeCodeStore store;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        store = new OAuthExchangeCodeStore(redisTemplate);
    }

    @Test
    void issue_storesUserIdInRedisWithTtl_andReturnsRandomCode() {
        String code = store.issue("user_1f9a74af", null);

        assertThat(code).isNotBlank();
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(keyCaptor.capture(), eq("user_1f9a74af"), eq(Duration.ofSeconds(60)));
        assertThat(keyCaptor.getValue()).isEqualTo("oauth:exchange:" + code);
    }

    @Test
    void issue_generatesDifferentCodesEachTime() {
        assertThat(store.issue("user_1f9a74af", null)).isNotEqualTo(store.issue("user_1f9a74af", null));
    }

    @Test
    void consume_deletesAtomicallyViaGetAndDelete_andReturnsUserId() {
        when(valueOperations.getAndDelete("oauth:exchange:code-1")).thenReturn("user_1f9a74af");

        assertThat(store.consume("code-1", null)).contains("user_1f9a74af");
    }

    @Test
    void consume_unknownOrAlreadyConsumedCode_returnsEmpty() {
        when(valueOperations.getAndDelete("oauth:exchange:unknown-code")).thenReturn(null);

        assertThat(store.consume("unknown-code", null)).isEmpty();
    }

    @Test
    void linkCode_roundTripsPendingLink_andLinkTokenIsBoundToProvider() {
        var pending = new OAuthExchangeCodeStore.PendingLink("user_local", "naver", "id:with:colons");
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        String code = store.issueLinkCode(pending);
        verify(valueOperations).set(eq("oauth:link-code:" + code), value.capture(), eq(Duration.ofSeconds(60)));
        when(valueOperations.getAndDelete("oauth:link-code:" + code)).thenReturn(value.getValue());
        when(valueOperations.getAndDelete("oauth:link:t")).thenReturn("google:user_local");

        assertThat(store.consumeLinkCode(code)).contains(pending);
        assertThat(store.consumeLinkToken("t", "kakao")).isEmpty();
    }

    /** RFC 7636 부록 B의 예시 값. */
    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    /** issue로 저장한 값을 그대로 돌려주는 1회 GETDEL. */
    private void storedValueIsReturnedOnce(String key, String value) {
        when(valueOperations.getAndDelete(key)).thenReturn(value).thenReturn(null);
    }

    @Test
    void consume_desktopCode_requiresMatchingVerifier() {
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        String code = store.issue("user_1f9a74af", CHALLENGE);
        verify(valueOperations).set(eq("oauth:exchange:" + code), value.capture(), eq(Duration.ofSeconds(60)));

        storedValueIsReturnedOnce("oauth:exchange:" + code, value.getValue());
        assertThat(store.consume(code, VERIFIER)).contains("user_1f9a74af");

        storedValueIsReturnedOnce("oauth:exchange:" + code, value.getValue());
        assertThat(store.consume(code, null)).isEmpty();

        storedValueIsReturnedOnce("oauth:exchange:" + code, value.getValue());
        assertThat(store.consume(code, VERIFIER + "x")).isEmpty();
    }

    /** challenge가 verifier의 해시와 맞아도 verifier가 RFC 7636 형식(43~128자, [A-Za-z0-9-._~])이 아니면 거절한다. */
    @ParameterizedTest
    @ValueSource(strings = {"short-verifier", "a+b/c=dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFO", "129"})
    void consume_malformedVerifier_isRejectedEvenIfHashMatches(String verifier) throws Exception {
        if (verifier.equals("129")) verifier = "a".repeat(129);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        storedValueIsReturnedOnce("oauth:exchange:c", "user_1f9a74af:" + challenge);
        assertThat(store.consume("c", verifier)).isEmpty();
    }

    @Test
    void consumeSignupToken_roundTripsPendingSignup_andChecksVerifier() {
        var web = new OAuthExchangeCodeStore.PendingSignup("google", "sub", "a@example.com", "이름\n둘째 줄", null);
        var desktop = new OAuthExchangeCodeStore.PendingSignup("google", "sub", "a@example.com", null, CHALLENGE);
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        String webToken = store.issueSignupToken(web);
        String desktopToken = store.issueSignupToken(desktop);
        verify(valueOperations).set(eq("oauth:signup:" + webToken), value.capture(), eq(Duration.ofMinutes(10)));
        verify(valueOperations).set(eq("oauth:signup:" + desktopToken), value.capture(), eq(Duration.ofMinutes(10)));

        storedValueIsReturnedOnce("oauth:signup:w", value.getAllValues().get(0));
        assertThat(store.consumeSignupToken("w", null)).contains(web);

        storedValueIsReturnedOnce("oauth:signup:d", value.getAllValues().get(1));
        assertThat(store.consumeSignupToken("d", "wrong-verifier")).isEmpty();

        storedValueIsReturnedOnce("oauth:signup:d", value.getAllValues().get(1));
        assertThat(store.consumeSignupToken("d", VERIFIER)).contains(desktop);
    }
}
