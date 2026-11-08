package fruition.shared.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class CommittedLogTest {
    private final Logger log = (Logger) LoggerFactory.getLogger(CommittedLogTest.class);
    private final TransactionTemplate tx = new TransactionTemplate(new LocalTransactions());

    @Test void logsAfterCommitWithOriginalRequestAndNeverAfterRollback() {
        ListAppender<ILoggingEvent> output = new ListAppender<>();
        output.start();
        log.addAppender(output);
        try {
            tx.executeWithoutResult(s -> {
                MDC.put("requestId", "original");
                CommittedLog.info(log, "[완료] operation=login stage=completed");
                assertThat(output.list).isEmpty();
                MDC.put("requestId", "later");
            });
            assertThat(output.list).hasSize(1);
            assertThat(output.list.getFirst().getMDCPropertyMap()).containsEntry("requestId", "original");
            tx.executeWithoutResult(s -> { CommittedLog.info(log, "[완료] rolled back"); s.setRollbackOnly(); });
            assertThat(output.list).hasSize(1);
        } finally {
            log.detachAppender(output);
            MDC.clear();
        }
    }

    private static final class LocalTransactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object t, TransactionDefinition d) {}
        @Override protected void doCommit(DefaultTransactionStatus s) {}
        @Override protected void doRollback(DefaultTransactionStatus s) {}
    }
}
