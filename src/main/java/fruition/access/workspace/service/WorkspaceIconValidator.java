package fruition.access.workspace.service;

import fruition.access.workspace.exception.UnsupportedWorkspaceIconException;
import fruition.access.workspace.exception.WorkspaceIconTooLargeException;
import fruition.shared.util.ImageSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 아이콘 이미지 검증.
 *
 * <p>선언된 Content-Type은 클라이언트가 정하는 값이라 믿지 않고 매직 바이트로 판별한다.
 * 픽셀 크기는 보지 않는다 — 서버가 이미지를 디코딩하지 않고 그대로 저장·스트리밍하므로
 * 디코딩 폭탄이 서버에 영향을 주지 않고, 바이트 상한만으로 저장량이 묶인다.
 */
@Component
public class WorkspaceIconValidator {

    public static final long MAX_BYTES = 1024L * 1024;

    public ValidatedIcon validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new UnsupportedWorkspaceIconException("아이콘 이미지 파일이 필요합니다.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new WorkspaceIconTooLargeException(MAX_BYTES);
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException exception) {
            throw new UnsupportedWorkspaceIconException("아이콘 이미지를 읽지 못했습니다.");
        }
        // getSize()는 헤더 값이라 실제 바이트로 한 번 더 확인한다.
        if (bytes.length > MAX_BYTES) {
            throw new WorkspaceIconTooLargeException(MAX_BYTES);
        }
        return new ValidatedIcon(bytes, detectContentType(bytes), ImageSignature.sha256(bytes));
    }

    private String detectContentType(byte[] bytes) {
        return ImageSignature.detectContentType(bytes)
                .orElseThrow(() -> new UnsupportedWorkspaceIconException("PNG, JPEG, WebP, GIF 이미지만 아이콘으로 쓸 수 있습니다."));
    }

    public record ValidatedIcon(byte[] bytes, String contentType, String hash) {}
}
