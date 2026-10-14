package fruition.access.user.service;

import fruition.access.user.domain.User;
import fruition.access.user.domain.UserAvatar;
import fruition.access.user.dto.MeResponse;
import fruition.access.user.exception.UnsupportedUserAvatarException;
import fruition.access.user.exception.UserAvatarNotFoundException;
import fruition.access.user.exception.UserAvatarTooLargeException;
import fruition.access.user.exception.UserNotFoundException;
import fruition.access.user.repository.UserAvatarRepository;
import fruition.access.user.repository.UserRepository;
import fruition.access.workspace.repository.WorkspaceMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAvatarServiceTest {

    private static final String USER_ID = "user_me";
    private static final String OTHER_ID = "user_other";

    private static final byte[] PNG = new byte[]{
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3};
    private static final byte[] JPEG = new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1, 2, 3};

    @Mock UserRepository userRepository;
    @Mock UserAvatarRepository userAvatarRepository;
    @Mock WorkspaceMemberRepository workspaceMemberRepository;
    @Mock AuthService authService;

    UserAvatarService service;

    @BeforeEach
    void setUp() {
        service = new UserAvatarService(
                userRepository, userAvatarRepository, workspaceMemberRepository, new UserAvatarValidator(), authService);
    }

    private User user(String id) {
        return new User(id, id + "@example.com", User.PROVIDER_LOCAL, "이름", null);
    }

    private User found(String id) {
        User user = user(id);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        return user;
    }

    private void meFrom(User user) {
        when(authService.toMe(user)).thenAnswer(invocation -> new MeResponse(
                user.getId(), user.getEmail(), user.getDisplayName(), user.avatarUrl(), user.getCreatedAt(),
                List.of(), false));
    }

    private MockMultipartFile upload(byte[] bytes) {
        return new MockMultipartFile("file", "me.png", "image/png", bytes);
    }

    @Test
    void updateAvatar_storesBinaryAndReturnsVersionedUrl() {
        User user = found(USER_ID);
        meFrom(user);
        when(userAvatarRepository.findById(USER_ID)).thenReturn(Optional.empty());

        MeResponse response = service.updateAvatar(USER_ID, upload(PNG));

        verify(userAvatarRepository).save(any(UserAvatar.class));
        assertThat(user.getAvatarContentType()).isEqualTo("image/png");
        assertThat(user.getAvatarHash()).isNotBlank();
        assertThat(response.avatarUrl()).isEqualTo("/api/users/" + USER_ID + "/avatar?v=" + user.getAvatarHash());
    }

    @Test
    void updateAvatar_replacesExistingBinaryAndChangesUrl() {
        User user = found(USER_ID);
        user.changeAvatar("image/png", "hash-old");
        meFrom(user);
        UserAvatar existing = new UserAvatar(USER_ID, PNG);
        when(userAvatarRepository.findById(USER_ID)).thenReturn(Optional.of(existing));

        MeResponse response = service.updateAvatar(USER_ID, upload(JPEG));

        // 새 row를 만들지 않고 기존 row를 덮어써 고아 바이너리가 남지 않는다.
        verify(userAvatarRepository, never()).save(any());
        assertThat(existing.getImage()).isEqualTo(JPEG);
        assertThat(user.getAvatarContentType()).isEqualTo("image/jpeg");
        assertThat(user.getAvatarHash()).isNotEqualTo("hash-old");
        // 해시가 바뀌면 URL도 바뀌어 예전 이미지가 캐시에서 보이지 않는다.
        assertThat(response.avatarUrl()).endsWith("?v=" + user.getAvatarHash());
    }

    /** 선언된 Content-Type은 클라이언트가 정하므로 매직 바이트로 판별한다. */
    @Test
    void updateAvatar_rejectsNonImageDeclaredAsPng() {
        found(USER_ID);

        assertThatThrownBy(() -> service.updateAvatar(USER_ID, upload("<html>not an image</html>".getBytes())))
                .isInstanceOf(UnsupportedUserAvatarException.class);
        verify(userAvatarRepository, never()).save(any());
    }

    @Test
    void updateAvatar_rejectsEmptyFile() {
        found(USER_ID);

        assertThatThrownBy(() -> service.updateAvatar(USER_ID, upload(new byte[0])))
                .isInstanceOf(UnsupportedUserAvatarException.class);
    }

    @Test
    void updateAvatar_rejectsOversizedFile() {
        found(USER_ID);
        byte[] tooLarge = new byte[(int) UserAvatarValidator.MAX_BYTES + 1];
        System.arraycopy(PNG, 0, tooLarge, 0, PNG.length);

        assertThatThrownBy(() -> service.updateAvatar(USER_ID, upload(tooLarge)))
                .isInstanceOf(UserAvatarTooLargeException.class);
        verify(userAvatarRepository, never()).save(any());
    }

    @Test
    void updateAvatar_unknownUserThrows() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateAvatar(USER_ID, upload(PNG)))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void deleteAvatar_clearsMetadataAndBinary() {
        User user = found(USER_ID);
        user.changeAvatar("image/png", "hash-1");
        meFrom(user);

        MeResponse response = service.deleteAvatar(USER_ID);

        verify(userAvatarRepository).deleteById(USER_ID);
        assertThat(user.hasAvatar()).isFalse();
        assertThat(response.avatarUrl()).isNull();
    }

    @Test
    void deleteAvatar_withoutAvatarIsNoop() {
        User user = found(USER_ID);
        meFrom(user);

        MeResponse response = service.deleteAvatar(USER_ID);

        verify(userAvatarRepository, never()).deleteById(any());
        assertThat(response.avatarUrl()).isNull();
    }

    @Test
    void readAvatar_selfGetsBytes() {
        User user = found(USER_ID);
        user.changeAvatar("image/png", "hash-1");
        when(userAvatarRepository.findById(USER_ID)).thenReturn(Optional.of(new UserAvatar(USER_ID, PNG)));

        UserAvatarService.AvatarImage avatar = service.readAvatar(USER_ID, USER_ID);

        assertThat(avatar.bytes()).isEqualTo(PNG);
        assertThat(avatar.contentType()).isEqualTo("image/png");
        assertThat(avatar.hash()).isEqualTo("hash-1");
        // 본인은 멤버십을 조회하지 않는다.
        verify(workspaceMemberRepository, never()).sharesActiveWorkspace(any(), any());
    }

    @Test
    void readAvatar_sharedWorkspaceMemberGetsBytes() {
        User user = found(OTHER_ID);
        user.changeAvatar("image/jpeg", "hash-2");
        when(workspaceMemberRepository.sharesActiveWorkspace(USER_ID, OTHER_ID)).thenReturn(true);
        when(userAvatarRepository.findById(OTHER_ID)).thenReturn(Optional.of(new UserAvatar(OTHER_ID, JPEG)));

        UserAvatarService.AvatarImage avatar = service.readAvatar(USER_ID, OTHER_ID);

        assertThat(avatar.bytes()).isEqualTo(JPEG);
        assertThat(avatar.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void readAvatar_strangerGets404WithoutTouchingAvatar() {
        when(workspaceMemberRepository.sharesActiveWorkspace(USER_ID, OTHER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.readAvatar(USER_ID, OTHER_ID))
                .isInstanceOf(UserNotFoundException.class);
        verify(userAvatarRepository, never()).findById(any());
    }

    @Test
    void readAvatar_withoutAvatarGets404() {
        found(USER_ID);

        assertThatThrownBy(() -> service.readAvatar(USER_ID, USER_ID))
                .isInstanceOf(UserAvatarNotFoundException.class);
    }
}
