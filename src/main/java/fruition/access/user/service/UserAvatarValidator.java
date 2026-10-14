package fruition.access.user.service;

import fruition.access.user.exception.UnsupportedUserAvatarException;
import fruition.access.user.exception.UserAvatarTooLargeException;
import fruition.shared.util.ImageSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 프로필 사진 검증. 워크스페이스 아이콘({@code WorkspaceIconValidator})과 같은 규칙이다.
 *
 * <p>형식은 매직 바이트로 판별하고 픽셀 크기는 보지 않는다 — 서버가 이미지를 디코딩하지 않고
 * 그대로 저장·스트리밍하므로 바이트 상한만으로 저장량이 묶인다.
 */
@Component
public class UserAvatarValidator {

    public static final long MAX_BYTES = 1024L * 1024;

    public ValidatedAvatar validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new UnsupportedUserAvatarException("프로필 사진 파일이 필요합니다.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new UserAvatarTooLargeException(MAX_BYTES);
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException exception) {
            throw new UnsupportedUserAvatarException("프로필 사진을 읽지 못했습니다.");
        }
        // getSize()는 헤더 값이라 실제 바이트로 한 번 더 확인한다.
        if (bytes.length > MAX_BYTES) {
            throw new UserAvatarTooLargeException(MAX_BYTES);
        }
        String contentType = ImageSignature.detectContentType(bytes)
                .orElseThrow(() -> new UnsupportedUserAvatarException("PNG, JPEG, WebP, GIF 이미지만 프로필 사진으로 쓸 수 있습니다."));
        return new ValidatedAvatar(bytes, contentType, ImageSignature.sha256(bytes));
    }

    public record ValidatedAvatar(byte[] bytes, String contentType, String hash) {}
}
