package fruition.access.user.service;

import fruition.access.user.domain.User;
import fruition.access.user.domain.UserAvatar;
import fruition.access.user.dto.MeResponse;
import fruition.access.user.exception.UserAvatarNotFoundException;
import fruition.access.user.exception.UserNotFoundException;
import fruition.access.user.repository.UserAvatarRepository;
import fruition.access.user.repository.UserRepository;
import fruition.access.workspace.repository.WorkspaceMemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 사용자 프로필 사진. 설정·삭제는 본인만, 조회는 본인과 워크스페이스를 하나라도 같이 쓰는 멤버가 할 수 있다.
 */
@Service
public class UserAvatarService {

    private static final Logger log = LoggerFactory.getLogger(UserAvatarService.class);

    private final UserRepository userRepository;
    private final UserAvatarRepository userAvatarRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final UserAvatarValidator validator;
    private final AuthService authService;

    public UserAvatarService(UserRepository userRepository,
                             UserAvatarRepository userAvatarRepository,
                             WorkspaceMemberRepository workspaceMemberRepository,
                             UserAvatarValidator validator,
                             AuthService authService) {
        this.userRepository = userRepository;
        this.userAvatarRepository = userAvatarRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.validator = validator;
        this.authService = authService;
    }

    @Transactional
    public MeResponse updateAvatar(String userId, MultipartFile file) {
        User user = findUser(userId);
        UserAvatarValidator.ValidatedAvatar avatar = validator.validate(file);

        userAvatarRepository.findById(userId)
                .ifPresentOrElse(
                        existing -> existing.replace(avatar.bytes()),
                        () -> userAvatarRepository.save(new UserAvatar(userId, avatar.bytes())));
        user.changeAvatar(avatar.contentType(), avatar.hash());
        log.info("[프로필 사진 변경] userId={} contentType={} bytes={}", userId, avatar.contentType(), avatar.bytes().length);
        return authService.toMe(user);
    }

    @Transactional
    public MeResponse deleteAvatar(String userId) {
        User user = findUser(userId);
        if (user.hasAvatar()) {
            userAvatarRepository.deleteById(userId);
            user.clearAvatar();
            log.info("[프로필 사진 삭제] userId={}", userId);
        }
        return authService.toMe(user);
    }

    /**
     * 프로필 사진 bytes. 본인이거나 활성 워크스페이스를 하나라도 같이 쓰는 멤버만 받을 수 있고,
     * 아니면 사용자가 없는 것과 같은 404다.
     */
    // 메타데이터 조회 뒤 재업로드·삭제가 커밋돼도 바이너리는 같은 스냅샷에서 읽는다.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AvatarImage readAvatar(String requesterId, String userId) {
        if (!requesterId.equals(userId) && !workspaceMemberRepository.sharesActiveWorkspace(requesterId, userId)) {
            throw new UserNotFoundException(userId);
        }
        User user = findUser(userId);
        if (!user.hasAvatar()) {
            throw new UserAvatarNotFoundException(userId);
        }
        UserAvatar avatar = userAvatarRepository.findById(userId)
                .orElseThrow(() -> new UserAvatarNotFoundException(userId));
        return new AvatarImage(avatar.getImage(), user.getAvatarContentType(), user.getAvatarHash());
    }

    private User findUser(String userId) {
        return userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
    }

    public record AvatarImage(byte[] bytes, String contentType, String hash) {}
}
