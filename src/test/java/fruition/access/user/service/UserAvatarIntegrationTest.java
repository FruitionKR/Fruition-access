package fruition.access.user.service;

import fruition.TestcontainersConfiguration;
import fruition.access.user.domain.User;
import fruition.access.user.repository.UserRepository;
import fruition.access.workspace.domain.Workspace;
import fruition.access.workspace.domain.WorkspaceMember;
import fruition.access.workspace.domain.WorkspaceRole;
import fruition.access.workspace.repository.WorkspaceMemberRepository;
import fruition.access.workspace.repository.WorkspaceRepository;
import fruition.shared.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 프로필 사진을 실제 Postgres(V26 migration)와 함께 올리고·바꾸고·지우고·멤버가 조회하는 흐름을 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class UserAvatarIntegrationTest {

    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3};
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 4, 5, 6};

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository users;
    @Autowired WorkspaceRepository workspaces;
    @Autowired WorkspaceMemberRepository members;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired JdbcTemplate jdbc;

    @Test
    void uploadReplaceAndDeleteOwnAvatar() throws Exception {
        String me = user();

        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatar_url").value(nullValue()));

        MvcResult uploaded = mockMvc.perform(multipart(HttpMethod.PUT, "/api/auth/me/avatar")
                        .file(new MockMultipartFile("file", "me.png", "application/octet-stream", PNG))
                        .header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(me))
                .andReturn();
        String firstUrl = avatarUrl(uploaded);
        assertThat(firstUrl).startsWith("/api/users/" + me + "/avatar?v=");

        // 본인은 ETag 304까지 받는다.
        MvcResult image = mockMvc.perform(get(firstUrl).header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(PNG))
                .andReturn();
        String etag = image.getResponse().getHeader("ETag");
        assertThat(etag).isEqualTo("\"" + firstUrl.substring(firstUrl.indexOf("?v=") + 3) + "\"");
        mockMvc.perform(get(firstUrl).header("Authorization", bearer(me)).header("If-None-Match", etag))
                .andExpect(status().isNotModified());

        // 교체하면 URL(해시)이 바뀌고 row는 하나만 남는다.
        MvcResult replaced = mockMvc.perform(multipart(HttpMethod.PUT, "/api/auth/me/avatar")
                        .file(new MockMultipartFile("file", "me.jpg", "image/jpeg", JPEG))
                        .header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andReturn();
        String secondUrl = avatarUrl(replaced);
        assertThat(secondUrl).isNotEqualTo(firstUrl);
        assertThat(count("SELECT count(*) FROM user_avatars WHERE user_id = ?", me)).isEqualTo(1);
        mockMvc.perform(get(secondUrl).header("Authorization", bearer(me)).header("If-None-Match", etag))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(content().bytes(JPEG));

        mockMvc.perform(delete("/api/auth/me/avatar").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatar_url").value(nullValue()));
        assertThat(count("SELECT count(*) FROM user_avatars WHERE user_id = ?", me)).isZero();
        assertThat(jdbc.queryForObject("SELECT avatar_hash FROM users WHERE id = ?", String.class, me)).isNull();
        mockMvc.perform(get(secondUrl).header("Authorization", bearer(me)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_AVATAR_NOT_FOUND"));
    }

    @Test
    void rejectsUnsupportedFormatAndOversizedFile() throws Exception {
        String me = user();

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/auth/me/avatar")
                        .file(new MockMultipartFile("file", "me.png", "image/png", "<svg/>".getBytes()))
                        .header("Authorization", bearer(me)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_USER_AVATAR"));

        byte[] tooLarge = new byte[1024 * 1024 + 1];
        System.arraycopy(PNG, 0, tooLarge, 0, PNG.length);
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/auth/me/avatar")
                        .file(new MockMultipartFile("file", "me.png", "image/png", tooLarge))
                        .header("Authorization", bearer(me)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("USER_AVATAR_TOO_LARGE"));

        assertThat(count("SELECT count(*) FROM user_avatars WHERE user_id = ?", me)).isZero();
    }

    @Test
    void onlySharedWorkspaceMembersCanReadAvatarAndSeeItInMemberList() throws Exception {
        String owner = user();
        String member = user();
        String stranger = user();
        String workspaceId = workspace(owner);
        join(workspaceId, member);

        MvcResult uploaded = mockMvc.perform(multipart(HttpMethod.PUT, "/api/auth/me/avatar")
                        .file(new MockMultipartFile("file", "me.png", "image/png", PNG))
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andReturn();
        String url = avatarUrl(uploaded);

        mockMvc.perform(get(url).header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PNG));
        mockMvc.perform(get(url).header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));

        mockMvc.perform(get("/api/workspaces/" + workspaceId + "/members").header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[0].user_id").value(owner))
                .andExpect(jsonPath("$.members[0].avatar_url").value(url))
                .andExpect(jsonPath("$.members[1].user_id").value(member))
                .andExpect(jsonPath("$.members[1].avatar_url").value(nullValue()));

        // 워크스페이스가 휴지통에 가면 더는 같이 쓰는 것이 아니다.
        jdbc.update("UPDATE workspaces SET deleted_at = now(), deleted_by = ? WHERE id = ?", owner, workspaceId);
        mockMvc.perform(get(url).header("Authorization", bearer(member)))
                .andExpect(status().isNotFound());
    }

    private String bearer(String userId) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(userId, userId + "@example.com");
    }

    private String avatarUrl(MvcResult result) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.avatar_url");
    }

    private String user() {
        String id = "user_" + UUID.randomUUID();
        users.saveAndFlush(new User(id, id + "@example.com", User.PROVIDER_LOCAL, "사용자", null));
        return id;
    }

    private String workspace(String owner) {
        Workspace workspace = workspaces.saveAndFlush(new Workspace("ws_" + UUID.randomUUID(), "공간"));
        members.saveAndFlush(new WorkspaceMember(workspace, users.getReferenceById(owner), WorkspaceRole.OWNER));
        return workspace.getId();
    }

    private void join(String workspaceId, String userId) {
        members.saveAndFlush(new WorkspaceMember(
                workspaces.getReferenceById(workspaceId), users.getReferenceById(userId), WorkspaceRole.MEMBER));
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
