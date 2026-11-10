package fruition.access.beta;

import fruition.TestcontainersConfiguration;
import fruition.access.workspace.service.DocumentInternalClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties = "app.beta-credit.retry-ms=3600000")
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class BetaCreditIntegrationTest {
    @Autowired BetaCreditService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ApplicationEventPublisher events;
    @MockitoBean DocumentInternalClient document;
    @MockitoBean TaskScheduler scheduler;

    @BeforeEach void resetCampaign() {
        jdbc.update("DELETE FROM beta_credit_awards");
        jdbc.update("UPDATE beta_credit_campaign SET enabled=true, starts_at=now()-interval '1 hour', allocated=0");
    }
    void signup(String id, String email) {
        tx.executeWithoutResult(s -> events.publishEvent(new UserSignedUp(id, email, Instant.now())));
    }
    int allocated() { return jdbc.queryForObject("SELECT allocated FROM beta_credit_campaign", Integer.class); }

    @Test void activationScriptCannotResetSlotsOrMoveCutover() throws Exception {
        jdbc.update("UPDATE beta_credit_campaign SET enabled=false, starts_at=null");
        String script = java.nio.file.Files.readString(java.nio.file.Path.of("scripts/sql/activate-beta-signup-credits.sql"))
                .replace("\\set ON_ERROR_STOP on", "").replace("BEGIN;", "").replace("COMMIT;", "");
        String first = script.replace(":'cutoff'", "'2026-01-01T00:00:00Z'");
        tx.executeWithoutResult(s -> jdbc.execute(first));
        signup("activation", "activation@example.com");
        tx.executeWithoutResult(s -> jdbc.execute(first));
        assertThat(allocated()).isEqualTo(1);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.execute(
                script.replace(":'cutoff'", "'2026-01-02T00:00:00Z'"))))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(allocated()).isEqualTo(1);
    }

    @Test void concurrentSignupsAwardExactlyFifty() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = IntStream.range(0, 65).<Callable<Void>>mapToObj(i -> () -> {
                signup("beta-" + i, "beta-" + i + "@example.com"); return null;
            }).toList();
            for (var result : pool.invokeAll(tasks)) result.get();
        }
        assertThat(allocated()).isEqualTo(50);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT slot) FROM beta_credit_awards", Integer.class)).isEqualTo(50);
        assertThat(jdbc.queryForObject("SELECT max(slot) FROM beta_credit_awards", Integer.class)).isEqualTo(50);
    }
    @Test void rollbackDoesNotConsumeSlotOrScheduleDelivery() {
        tx.executeWithoutResult(s -> {
            events.publishEvent(new UserSignedUp("rolled-back", "rollback@example.com", Instant.now()));
            s.setRollbackOnly();
        });
        assertThat(allocated()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM beta_credit_awards", Integer.class)).isZero();
        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
        signup("next", "next@example.com");
        assertThat(allocated()).isEqualTo(1);
    }
    @Test void duplicateEmailAndReplayedSignupDoNotConsumeSlots() {
        signup("original", " Beta@Example.com ");
        signup("original", "beta@example.com");
        signup("rejoined", "BETA@example.com");
        assertThat(allocated()).isEqualTo(1);
        assertThat(service.status("original").status()).isEqualTo("pending");
    }
    @Test void inactiveAndPreCutoverAccountsDoNotReceiveCredit() {
        tx.executeWithoutResult(s -> service.reserve(new UserSignedUp("old", "old@example.com", Instant.EPOCH)));
        jdbc.update("UPDATE beta_credit_campaign SET enabled=false");
        signup("disabled", "disabled@example.com");
        assertThat(allocated()).isZero();
    }
    @Test void failedDeliveryIsRetriedWithoutAllocatingAnotherSlot() {
        signup("retry", "retry@example.com");
        when(document.grantBetaCredit(eq("retry"), anyString(), eq(1)))
                .thenThrow(new IllegalStateException("unavailable")).thenReturn(7_000_000L);
        service.deliver("retry");
        service.deliver("retry");
        verify(document, times(1)).grantBetaCredit(eq("retry"), anyString(), eq(1));
        assertThat(service.status("retry").status()).isEqualTo("pending");
        jdbc.update("UPDATE beta_credit_awards SET next_attempt_at=now()-interval '1 second'");
        service.retryPending();
        service.retryPending();
        assertThat(service.status("retry")).isEqualTo(new BetaCreditService.Status("granted", 7_000_000L));
        verify(document, times(2)).grantBetaCredit(eq("retry"), anyString(), eq(1));
        assertThat(allocated()).isEqualTo(1);
    }
}
