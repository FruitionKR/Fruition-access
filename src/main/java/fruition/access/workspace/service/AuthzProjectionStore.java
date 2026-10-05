package fruition.access.workspace.service;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 워크스페이스 권한 projection(access 소유).
 *
 * <p>core(문서 서비스)가 access의 DB를 직접 읽지 않도록 멤버십 역할을
 * Redis({@code authz:role:{workspaceId}:{userId}})로 조회하게 한다. 적재는
 * 문서 서비스가 cache miss 시 내부 API 폴백 결과를 캐시하는 방식이고,
 * 멤버십이 변하는 지점에서 evict를 호출하며 TTL이 최종 안전망이다.
 *
 * <p>무효화는 커밋 이후에 실행되므로 실패해도 DB 변경을 되돌릴 수 없다. Redis 장애 시에는
 * 한 번 재시도하고, 그래도 실패하면 ERROR로 남긴 뒤 HTTP 응답은 성공으로 둔다. 예외를 올리면
 * 호출자가 500을 받고 재시도하지만 멤버는 이미 제거돼 404가 되므로 복구에 쓸모가 없다.
 * 보장 수준은 "projection TTL까지 eventually consistent + 실패는 ERROR 로그로 드러남"이다.
 *
 * <p>무효화는 트랜잭션 커밋 후에만 한다. 커밋 전에 지우면 evict와 commit 사이에
 * 문서 서비스가 조회했을 때 아직 커밋되지 않은 <em>옛 역할</em>을 내부 API로 읽어
 * 다시 캐시하고, 그 판정이 TTL 만료까지 남는다.
 */
@Component
public class AuthzProjectionStore {

    private static final Logger log = LoggerFactory.getLogger(AuthzProjectionStore.class);

    private static final String KEY_PREFIX = "authz:role:";
    private static final int SCAN_COUNT = 100;

    private final StringRedisTemplate redisTemplate;

    public AuthzProjectionStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void evict(String workspaceId, String userId) {
        runAfterCommit(() -> {
            redisTemplate.delete(key(workspaceId, userId));
            log.debug("[인가 projection 삭제] workspaceId={} userId={}", workspaceId, userId);
        }, "authz:role:" + workspaceId + ":" + userId);
    }

    /** 워크스페이스 삭제·복구처럼 멤버 전원의 판정이 바뀌는 경우 workspace 단위로 무효화한다. */
    public void evictWorkspace(String workspaceId) {
        runAfterCommit(() -> deleteWorkspaceKeys(workspaceId), KEY_PREFIX + workspaceId + ":*");
    }

    private void deleteWorkspaceKeys(String workspaceId) {
        List<String> keys = new ArrayList<>();
        try (Cursor<String> cursor = redisTemplate.scan(
                ScanOptions.scanOptions().match(KEY_PREFIX + workspaceId + ":*").count(SCAN_COUNT).build())) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        }
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        log.debug("[인가 projection workspace 삭제] workspaceId={} deletedCount={}", workspaceId, keys.size());
    }

    /** 트랜잭션이 활성이면 커밋 후에, 아니면 즉시 실행한다. */
    private void runAfterCommit(Runnable action, String target) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runGuarded(action, target);
                }
            });
        } else {
            runGuarded(action, target);
        }
    }

    /**
     * Redis 장애로 무효화가 실패하면 커밋된 권한 변경이 projection TTL까지 적용되지 않는다.
     * 일시적인 command timeout은 즉시 1회 재시도로 흡수하고, 그래도 실패하면 운영자가
     * 수동으로 키를 지울 수 있도록 대상 키를 포함해 ERROR로 남긴다.
     */
    private void runGuarded(Runnable action, String target) {
        try {
            action.run();
            return;
        } catch (RuntimeException first) {
            log.warn("[인가 projection 무효화 재시도] target={} reason={}", target, first.toString());
        }
        try {
            action.run();
        } catch (RuntimeException retry) {
            log.error("[인가 projection 무효화 실패] target={} — 커밋된 권한 변경이 projection TTL까지"
                    + " 적용되지 않습니다. Redis 복구 후 해당 키를 삭제하세요.", target, retry);
        }
    }

    private String key(String workspaceId, String userId) {
        return KEY_PREFIX + workspaceId + ":" + userId;
    }
}
