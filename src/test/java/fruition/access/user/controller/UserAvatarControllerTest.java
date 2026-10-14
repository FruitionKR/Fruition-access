package fruition.access.user.controller;

import fruition.access.AccessExceptionHandler;
import fruition.access.security.SecurityConfig;
import fruition.access.security.oauth.OAuthExchangeCodeStore;
import fruition.access.security.oauth.handler.OAuth2AuthenticationFailureHandler;
import fruition.access.security.oauth.handler.OAuth2AuthenticationSuccessHandler;
import fruition.access.security.oauth.service.CustomOAuth2UserService;
import fruition.access.user.exception.UserAvatarNotFoundException;
import fruition.access.user.exception.UserNotFoundException;
import fruition.access.user.service.UserAvatarService;
import fruition.shared.security.JwtAuthenticationFilter;
import fruition.shared.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserAvatarController.class)
@Import({AccessExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class,
        OAuthExchangeCodeStore.class, OAuth2AuthenticationSuccessHandler.class, OAuth2AuthenticationFailureHandler.class})
class UserAvatarControllerTest {

    private static final String USER_ID = "user_1f9a74af";
    private static final String OTHER_ID = "user_other";
    private static final byte[] PNG_BYTES = new byte[]{
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3};

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @MockBean UserAvatarService userAvatarService;
    @MockBean CustomOAuth2UserService customOAuth2UserService;
    // OAuthExchangeCodeStore가 Redis에 의존하므로 web slice에는 mock template을 채운다.
    @MockBean org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    private String bearerToken() {
        return "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com");
    }

    @Test
    void getAvatar_returnsBytesWithEtag() throws Exception {
        when(userAvatarService.readAvatar(USER_ID, OTHER_ID))
                .thenReturn(new UserAvatarService.AvatarImage(PNG_BYTES, "image/png", "hash-1"));

        mockMvc.perform(get("/api/users/" + OTHER_ID + "/avatar").param("v", "hash-1")
                        .header("Authorization", bearerToken()))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"hash-1\""))
                .andExpect(header().string("Cache-Control", "no-cache, private"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(PNG_BYTES));
    }

    @Test
    void getAvatar_matchingEtag_returns304() throws Exception {
        when(userAvatarService.readAvatar(USER_ID, USER_ID))
                .thenReturn(new UserAvatarService.AvatarImage(PNG_BYTES, "image/png", "hash-1"));

        mockMvc.perform(get("/api/users/" + USER_ID + "/avatar")
                        .header("Authorization", bearerToken())
                        .header("If-None-Match", "\"hash-1\""))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", "\"hash-1\""))
                .andExpect(header().string("Cache-Control", "no-cache, private"));
    }

    @Test
    void getAvatar_afterReplacement_returnsNewImageForOldEtag() throws Exception {
        byte[] jpeg = new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff};
        when(userAvatarService.readAvatar(USER_ID, USER_ID))
                .thenReturn(new UserAvatarService.AvatarImage(jpeg, "image/jpeg", "hash-2"));

        mockMvc.perform(get("/api/users/" + USER_ID + "/avatar")
                        .header("Authorization", bearerToken())
                        .header("If-None-Match", "\"hash-1\""))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"hash-2\""))
                .andExpect(content().contentType(MediaType.IMAGE_JPEG));
    }

    @Test
    void getAvatar_strangerOrUnknownUser_returns404() throws Exception {
        when(userAvatarService.readAvatar(USER_ID, OTHER_ID)).thenThrow(new UserNotFoundException(OTHER_ID));

        mockMvc.perform(get("/api/users/" + OTHER_ID + "/avatar").header("Authorization", bearerToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
    }

    @Test
    void getAvatar_noAvatar_returns404() throws Exception {
        when(userAvatarService.readAvatar(USER_ID, OTHER_ID)).thenThrow(new UserAvatarNotFoundException(OTHER_ID));

        mockMvc.perform(get("/api/users/" + OTHER_ID + "/avatar").header("Authorization", bearerToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_AVATAR_NOT_FOUND"));
    }

    @Test
    void getAvatar_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/users/" + OTHER_ID + "/avatar"))
                .andExpect(status().isUnauthorized());
    }
}
