package fruition.shared.util;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 업로드된 이미지 바이트의 형식 판별과 해시. 워크스페이스 아이콘과 프로필 사진이 함께 쓴다.
 *
 * <p>선언된 Content-Type은 클라이언트가 정하는 값이라 믿지 않고 매직 바이트로 판별한다.
 */
public final class ImageSignature {

    private ImageSignature() {}

    /** PNG·JPEG·GIF·WebP 중 하나면 그 Content-Type, 아니면 empty. */
    public static Optional<String> detectContentType(byte[] bytes) {
        if (startsWith(bytes, new int[]{0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})) return Optional.of("image/png");
        if (startsWith(bytes, new int[]{0xff, 0xd8, 0xff})) return Optional.of("image/jpeg");
        if (asciiAt(bytes, 0, "GIF87a") || asciiAt(bytes, 0, "GIF89a")) return Optional.of("image/gif");
        if (asciiAt(bytes, 0, "RIFF") && asciiAt(bytes, 8, "WEBP")) return Optional.of("image/webp");
        return Optional.empty();
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("해시 계산 실패", exception);
        }
    }

    private static boolean startsWith(byte[] bytes, int[] signature) {
        if (bytes.length < signature.length) return false;
        for (int index = 0; index < signature.length; index++) {
            if ((bytes[index] & 0xff) != signature[index]) return false;
        }
        return true;
    }

    private static boolean asciiAt(byte[] bytes, int offset, String expected) {
        if (bytes.length < offset + expected.length()) return false;
        for (int index = 0; index < expected.length(); index++) {
            if ((bytes[offset + index] & 0xff) != expected.charAt(index)) return false;
        }
        return true;
    }
}
