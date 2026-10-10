package fruition.access.user.service;

import fruition.TestcontainersConfiguration;
import fruition.access.cleanup.DataPurgeRequestJob;
import fruition.access.user.domain.User;
import fruition.access.user.repository.UserRepository;
import fruition.access.workspace.domain.Workspace;
import fruition.access.workspace.domain.WorkspaceMember;
import fruition.access.workspace.domain.WorkspaceRole;
import fruition.access.workspace.repository.WorkspaceMemberRepository;
import fruition.access.workspace.repository.WorkspaceRepository;
import fruition.access.workspace.service.AiInternalClient;
import fruition.access.workspace.service.DocumentInternalClient;
import fruition.shared.security.JwtTokenProvider;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 회원 탈퇴를 실제 Postgres FK·트리거와 함께 확인한다. document 파기 호출만 가짜로 둔다. */
@SpringBootTest(properties = "app.purge.retry-delay-ms=3600000")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class AccountDeletionIntegrationTest {

    static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository users;
    @Autowired WorkspaceRepository workspaces;
    @Autowired WorkspaceMemberRepository members;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired DataPurgeRequestJob purgeJob;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean DocumentInternalClient documentClient;
    @MockitoBean AiInternalClient aiClient;

    @Test
    void passwordAccountDeletesUserSessionsAndSoloWorkspaceThenPurgesDocumentData() throws Exception {
        String user = passwordUser();
        String other = passwordUser();
        String solo = workspace(user, WorkspaceRole.OWNER);
        String shared = workspace(other, WorkspaceRole.OWNER);
        join(shared, user, WorkspaceRole.MEMBER);
        String refreshToken = refreshToken(user);

        deleteAccount(user, null, "{\"password\":\"" + PASSWORD + "\"}").andExpect(status().isNoContent());

        assertThat(users.existsById(user)).isFalse();
        assertThat(count("SELECT count(*) FROM user_refresh_tokens WHERE user_id = ?", user)).isZero();
        assertThat(count("SELECT count(*) FROM workspace_members WHERE user_id = ?", user)).isZero();
        assertThat(count("SELECT count(*) FROM workspace_membership_periods WHERE user_id = ? AND left_at IS NOT NULL",
                user)).isEqualTo(2);
        assertThat(workspaces.existsById(shared)).isTrue();
        mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie("fruition_refresh_token", refreshToken)))
                .andExpect(status().isUnauthorized());

        purgeJob.run(Instant.now().plus(Duration.ofDays(1)));

        InOrder order = inOrder(documentClient, aiClient);
        order.verify(documentClient).purgeUser(user);
        order.verify(aiClient).purgeUser(user);
        order.verify(documentClient).purgeWorkspace(solo);
        order.verify(aiClient).purgeWorkspace(solo);
        assertThat(workspaces.existsById(solo)).isFalse();
        assertThat(count("SELECT count(*) FROM data_purge_requests WHERE target_id IN (?, ?)", user, solo)).isZero();

        // 같은 이메일로 다시 가입할 수 있다.
        users.saveAndFlush(new User(UUID.randomUUID().toString(), user + "@example.com", "local", "재가입", null));
    }

    @Test
    void rejectsWrongPasswordAndSoleOwnerOfSharedWorkspace() throws Exception {
        String user = passwordUser();
        String member = passwordUser();
        String shared = workspace(user, WorkspaceRole.OWNER);
        join(shared, member, WorkspaceRole.MEMBER);

        deleteAccount(user, null, "{\"password\":\"wrong\"}").andExpect(status().isUnauthorized());
        deleteAccount(user, null, "{\"password\":\"" + PASSWORD + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SOLE_OWNER_OF_SHARED_WORKSPACE"))
                .andExpect(jsonPath("$.workspaces[0].id").value(shared));

        assertThat(users.existsById(user)).isTrue();
        assertThat(count("SELECT count(*) FROM data_purge_requests WHERE target_id = ?", user)).isZero();
    }

    @Test
    void socialAccountNeedsRecentLogin() throws Exception {
        String user = UUID.randomUUID().toString();
        users.saveAndFlush(new User(user, user + "@example.com", "google", "소셜", null));

        deleteAccount(user, null, null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("REAUTHENTICATION_REQUIRED"));
        deleteAccount(user, Instant.now().minus(Duration.ofMinutes(11)), null).andExpect(status().isUnauthorized());
        deleteAccount(user, Instant.now(), null).andExpect(status().isNoContent());

        assertThat(users.existsById(user)).isFalse();
    }

    @Test
    void failedPurgeStaysForRetry() throws Exception {
        String user = passwordUser();
        doThrow(new RestClientException("document 다운")).when(documentClient).purgeUser(user);

        deleteAccount(user, null, "{\"password\":\"" + PASSWORD + "\"}").andExpect(status().isNoContent());
        Instant now = Instant.now().plus(Duration.ofDays(1));
        purgeJob.run(now);

        assertThat(users.existsById(user)).isFalse();
        Timestamp nextAttempt = jdbc.queryForObject(
                "SELECT next_attempt_at FROM data_purge_requests WHERE kind = 'user' AND target_id = ?",
                Timestamp.class, user);
        assertThat(nextAttempt.toInstant()).isAfter(now);
        assertThat(count("SELECT count(*) FROM data_purge_requests WHERE target_id = ? AND last_error IS NOT NULL",
                user)).isOne();
    }

    @Test
    void aiPurgeFailureKeepsRequestsPendingAndRetriesWholeSequence() throws Exception {
        String user = passwordUser();
        String solo = workspace(user, WorkspaceRole.OWNER);
        doThrow(new RestClientException("ai 다운")).when(aiClient).purgeWorkspace(solo);

        deleteAccount(user, null, "{\"password\":\"" + PASSWORD + "\"}").andExpect(status().isNoContent());
        Instant first = Instant.now().plus(Duration.ofDays(1));
        purgeJob.run(first);

        // 워크스페이스 요청은 남고 workspaces 행도 지워지지 않는다. 사용자 요청은 끝난다.
        assertThat(workspaces.existsById(solo)).isTrue();
        assertThat(count("SELECT count(*) FROM data_purge_requests WHERE kind = 'workspace' AND target_id = ? AND attempts = 1 AND last_error IS NOT NULL",
                solo)).isOne();
        assertThat(count("SELECT count(*) FROM data_purge_requests WHERE kind = 'user' AND target_id = ?", user)).isZero();

        doNothing().when(aiClient).purgeWorkspace(solo);
        purgeJob.run(first.plus(Duration.ofDays(1)));

        verify(documentClient, times(2)).purgeWorkspace(solo);
        verify(aiClient, times(2)).purgeWorkspace(solo);
        assertThat(workspaces.existsById(solo)).isFalse();
        assertThat(count("SELECT count(*) FROM data_purge_requests WHERE target_id = ?", solo)).isZero();
    }

    @Test
    void documentFailureSkipsAiPurge() throws Exception {
        String user = passwordUser();
        doThrow(new RestClientException("document 다운")).when(documentClient).purgeUser(user);

        deleteAccount(user, null, "{\"password\":\"" + PASSWORD + "\"}").andExpect(status().isNoContent());
        purgeJob.run(Instant.now().plus(Duration.ofDays(1)));

        verify(aiClient, never()).purgeUser(user);
    }

    private ResultActions deleteAccount(String userId, Instant authTime, String body) throws Exception {
        var request = delete("/api/auth/me").header("Authorization",
                "Bearer " + jwtTokenProvider.generateAccessToken(userId, userId + "@example.com", authTime));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request);
    }

    private String passwordUser() {
        String id = UUID.randomUUID().toString();
        users.saveAndFlush(new User(id, id + "@example.com", "local", "사용자", passwordEncoder.encode(PASSWORD)));
        return id;
    }

    private String workspace(String owner, WorkspaceRole role) {
        Workspace workspace = workspaces.saveAndFlush(new Workspace(UUID.randomUUID().toString(), "공간"));
        join(workspace.getId(), owner, role);
        return workspace.getId();
    }

    private void join(String workspaceId, String userId, WorkspaceRole role) {
        members.saveAndFlush(new WorkspaceMember(
                workspaces.getReferenceById(workspaceId), users.getReferenceById(userId), role));
    }

    private String refreshToken(String userId) throws Exception {
        String token = UUID.randomUUID().toString();
        String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        jdbc.update("INSERT INTO user_refresh_tokens(created_at, expires_at, token_hash, user_id) "
                + "VALUES (now(), now() + interval '1 day', ?, ?)", hash, userId);
        return token;
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
