package fruition.access.user.service;

import fruition.access.user.domain.User;
import fruition.access.user.dto.AccountDeletionRequest;
import fruition.access.user.exception.AccountDeletionBlockedException;
import fruition.access.user.exception.InvalidCredentialsException;
import fruition.access.user.exception.InvalidMfaCodeException;
import fruition.access.user.exception.ReauthenticationRequiredException;
import fruition.access.user.exception.UserNotFoundException;
import fruition.access.user.mfa.MfaService;
import fruition.access.user.repository.UserRepository;
import fruition.access.workspace.service.AuthzProjectionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 회원 탈퇴. 본인을 다시 확인하고, 다른 멤버가 남는 워크스페이스의 유일한 OWNER면 막는다.
 *
 * <p>통과하면 같은 트랜잭션에서 계정과 세션을 지우고 document 데이터 파기 요청을 남긴다.
 * 파기는 {@code DataPurgeRequestJob}이 커밋 뒤 호출하고, 실패해도 탈퇴는 되돌리지 않는다.
 * 계정을 지우면 OAuth 연결·MFA·멤버십·멱등 기록은 FK CASCADE로 함께 지워지고, 멤버십 기간 이력에는 left_at이 남는다.
 */
@Service
public class AccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);
    static final Duration RECENT_LOGIN = Duration.ofMinutes(10);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final MfaService mfaService;
    private final AuthzProjectionStore authzProjectionStore;
    private final JdbcTemplate jdbc;

    public AccountDeletionService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                                  MfaService mfaService, AuthzProjectionStore authzProjectionStore,
                                  JdbcTemplate jdbc) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.mfaService = mfaService;
        this.authzProjectionStore = authzProjectionStore;
        this.jdbc = jdbc;
    }

    /** {@code authTime}은 요청 access token의 직접 로그인 시각이다. refresh로 받은 토큰이면 null이다. */
    @Transactional
    public void delete(String userId, AccountDeletionRequest request, Instant authTime) {
        User user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
        reauthenticate(user, request, authTime);

        List<Membership> memberships = lockMemberships(userId);
        List<AccountDeletionBlockedException.Workspace> blocked = memberships.stream()
                .filter(Membership::blocksDeletion)
                .map(m -> new AccountDeletionBlockedException.Workspace(m.workspaceId(), m.name()))
                .toList();
        if (!blocked.isEmpty()) {
            log.warn("[회원 탈퇴 거부] reason=sole_owner userId={} workspaces={}", userId, blocked.size());
            throw new AccountDeletionBlockedException(blocked);
        }
        List<String> purgedWorkspaces = memberships.stream()
                .filter(Membership::purgedWithUser)
                .map(Membership::workspaceId)
                .toList();

        requestPurge("user", userId);
        purgedWorkspaces.forEach(workspaceId -> requestPurge("workspace", workspaceId));
        memberships.forEach(m -> authzProjectionStore.evict(m.workspaceId(), userId));
        purgedWorkspaces.forEach(authzProjectionStore::evictWorkspace);

        // MFA 검증이 남긴 JPA 변경을 먼저 내보낸다. 행을 지운 뒤에 flush되면 0행 갱신으로 실패한다.
        userRepository.flush();
        // refresh token에는 FK가 없어 CASCADE로 지워지지 않는다.
        jdbc.update("DELETE FROM user_refresh_tokens WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
        log.info("[회원 탈퇴] userId={} workspaces={} purgedWorkspaces={}",
                userId, memberships.size(), purgedWorkspaces.size());
    }

    private void reauthenticate(User user, AccountDeletionRequest request, Instant authTime) {
        if (user.getPasswordHash() != null) {
            if (request.password() == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
                throw new InvalidCredentialsException();
            }
        } else if (authTime == null || authTime.isBefore(Instant.now().minus(RECENT_LOGIN))) {
            throw new ReauthenticationRequiredException();
        }
        if (mfaService.isEnabled(user.getId())) {
            if (request.mfaCode() == null || request.mfaCode().isBlank()) {
                throw new InvalidMfaCodeException();
            }
            mfaService.verify(user.getId(), request.mfaCode());
        }
    }

    /**
     * 사용자가 속한 워크스페이스의 OWNER 행을 잠근 뒤 멤버 구성을 읽는다. 잠그지 않으면 다른 OWNER가 같은 순간
     * 강등·탈퇴해 OWNER 없는 워크스페이스가 남는다. 교착을 피하려고 역할 변경과 같은 순서(user_id)로 잠근다.
     */
    private List<Membership> lockMemberships(String userId) {
        jdbc.query("""
                SELECT 1 FROM workspace_members
                WHERE role = 'OWNER'
                  AND workspace_id IN (SELECT workspace_id FROM workspace_members WHERE user_id = ?)
                ORDER BY workspace_id, user_id
                FOR UPDATE
                """, rs -> {}, userId);
        return jdbc.query("""
                SELECT w.id, w.name, w.deleted_at IS NOT NULL AS trashed, m.role,
                       (SELECT count(*) FROM workspace_members o
                        WHERE o.workspace_id = w.id AND o.user_id <> m.user_id) AS others,
                       (SELECT count(*) FROM workspace_members o
                        WHERE o.workspace_id = w.id AND o.user_id <> m.user_id AND o.role = 'OWNER') AS other_owners
                FROM workspace_members m JOIN workspaces w ON w.id = m.workspace_id
                WHERE m.user_id = ?
                """, (rs, n) -> new Membership(rs.getString("id"), rs.getString("name"), rs.getBoolean("trashed"),
                "OWNER".equals(rs.getString("role")), rs.getLong("others"), rs.getLong("other_owners")), userId);
    }

    private void requestPurge(String kind, String targetId) {
        jdbc.update("INSERT INTO data_purge_requests(kind, target_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                kind, targetId);
    }

    private record Membership(String workspaceId, String name, boolean trashed, boolean owner,
                              long others, long otherOwners) {

        boolean soleOwner() {
            return owner && otherOwners == 0;
        }

        /** 활성 워크스페이스에 다른 멤버가 남는데 OWNER가 없어지면 아무도 관리할 수 없다. */
        boolean blocksDeletion() {
            return !trashed && soleOwner() && others > 0;
        }

        /** 혼자 쓰던 워크스페이스, 그리고 OWNER만 복구할 수 있는 휴지통 워크스페이스는 함께 지운다. */
        boolean purgedWithUser() {
            return others == 0 || (trashed && soleOwner());
        }
    }
}
