package fruition.shared.logging;

import org.slf4j.Logger;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

/** 최종 상태 로그는 DB 커밋 뒤에 원래 요청의 MDC로 남긴다. 롤백되면 남지 않는다. */
public final class CommittedLog {
    private CommittedLog() {}

    public static void info(Logger logger, String template, Object... arguments) {
        afterCommit(() -> logger.info(template, arguments));
    }

    static void afterCommit(Runnable record) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        Runnable contextual = () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                if (context == null) MDC.clear(); else MDC.setContextMap(context);
                record.run();
            } finally {
                if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
            }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { contextual.run(); }
            });
        } else contextual.run();
    }
}
