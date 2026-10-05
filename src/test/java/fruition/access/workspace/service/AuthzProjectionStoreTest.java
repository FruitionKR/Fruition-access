package fruition.access.workspace.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentCaptor.forClass;

@ExtendWith(MockitoExtension.class)
class AuthzProjectionStoreTest {

    @Mock StringRedisTemplate redisTemplate;

    @Test
    void evict_deletesSingleKey() {
        AuthzProjectionStore store = new AuthzProjectionStore(redisTemplate);

        store.evict("ws_1", "user_1");

        verify(redisTemplate).delete("authz:role:ws_1:user_1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void evictWorkspace_scansWorkspacePrefixAndDeletesAllMatches() {
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn("authz:role:ws_1:user_1", "authz:role:ws_1:user_2");
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        AuthzProjectionStore store = new AuthzProjectionStore(redisTemplate);

        store.evictWorkspace("ws_1");

        var optionsCaptor = forClass(ScanOptions.class);
        verify(redisTemplate).scan(optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().getPattern()).isEqualTo("authz:role:ws_1:*");
        verify(redisTemplate).delete(List.of("authz:role:ws_1:user_1", "authz:role:ws_1:user_2"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void evictWorkspace_noMatches_doesNotDelete() {
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(false);
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        AuthzProjectionStore store = new AuthzProjectionStore(redisTemplate);

        store.evictWorkspace("ws_1");

        verify(redisTemplate, never()).delete(anyList());
    }

    /**
     * 커밋 전에 지우면 evict와 commit 사이의 조회가 아직 커밋되지 않은 옛 역할을 다시 캐시하고,
     * 그 판정이 TTL 만료까지 남는다. 그래서 무효화는 커밋 이후여야 한다.
     */
    @Test
    void evict_insideTransaction_isDeferredUntilCommit() {
        AuthzProjectionStore store = new AuthzProjectionStore(redisTemplate);
        TransactionSynchronizationManager.initSynchronization();
        try {
            store.evict("ws_1", "user_1");
            verify(redisTemplate, never()).delete("authz:role:ws_1:user_1");

            for (TransactionSynchronization synchronization :
                    TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
            verify(redisTemplate).delete("authz:role:ws_1:user_1");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void evictWorkspace_insideTransaction_doesNotScanBeforeCommit() {
        AuthzProjectionStore store = new AuthzProjectionStore(redisTemplate);
        TransactionSynchronizationManager.initSynchronization();
        try {
            store.evictWorkspace("ws_1");

            verify(redisTemplate, never()).scan(any(ScanOptions.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * 커밋이 끝난 뒤 실행되므로 여기서 예외가 올라가면 호출자는 500을 받지만 DB 변경은
     * 이미 확정돼 있다. 재시도는 404가 되고 stale 판정만 남으므로, 한 번 더 시도한 뒤
     * ERROR로 남기고 응답은 성공으로 둔다.
     */
    @Test
    void evict_redisFailure_isRetriedThenSwallowed() {
        when(redisTemplate.delete("authz:role:ws_1:user_1"))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        AuthzProjectionStore store = new AuthzProjectionStore(redisTemplate);

        assertThatCode(() -> store.evict("ws_1", "user_1")).doesNotThrowAnyException();

        verify(redisTemplate, times(2)).delete("authz:role:ws_1:user_1");
    }

    @Test
    void evictWorkspace_redisFailure_isRetriedThenSwallowed() {
        when(redisTemplate.scan(any(ScanOptions.class)))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        AuthzProjectionStore store = new AuthzProjectionStore(redisTemplate);

        assertThatCode(() -> store.evictWorkspace("ws_1")).doesNotThrowAnyException();

        verify(redisTemplate, times(2)).scan(any(ScanOptions.class));
    }
}
