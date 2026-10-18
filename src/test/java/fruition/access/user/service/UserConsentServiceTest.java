package fruition.access.user.service;

import fruition.access.user.exception.InvalidConsentException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** app.legal.consent-required 플래그에 따라 동의를 요구·기록하는지 확인한다. */
class UserConsentServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    private UserConsentService service(boolean consentRequired) {
        return new UserConsentService(jdbc, "2026-10-01", "2026-10-01", consentRequired);
    }

    @Test
    void enabled_rejectsMissingOrStaleConsent() {
        UserConsentService service = service(true);

        assertThatThrownBy(() -> service.validate(null, null)).isInstanceOf(InvalidConsentException.class);
        assertThatThrownBy(() -> service.validate(false, "2026-10-01")).isInstanceOf(InvalidConsentException.class);
        assertThatThrownBy(() -> service.validate(true, "2026-01-01")).isInstanceOf(InvalidConsentException.class);
        assertThatCode(() -> service.validate(true, "2026-10-01")).doesNotThrowAnyException();
    }

    @Test
    void enabled_recordsConsentAndAsksMembersWithoutCurrentConsent() {
        UserConsentService service = service(true);
        when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class))).thenReturn(List.of());

        service.record("user-1", true);

        verify(jdbc).update(anyString(), eq("user-1"), eq("2026-10-01"), eq("2026-10-01"), eq(true));
        assertThat(service.consentRequired("user-1")).isTrue();
    }

    @Test
    void disabled_acceptsAnythingRecordsNothingAndNeverAsks() {
        UserConsentService service = service(false);

        assertThatCode(() -> service.validate(null, null)).doesNotThrowAnyException();
        assertThatCode(() -> service.validate(false, "old")).doesNotThrowAnyException();
        service.record("user-1", true);

        assertThat(service.consentRequired("user-1")).isFalse();
        verifyNoInteractions(jdbc);
    }
}
