package fruition.access.user.controller;

import fruition.access.user.service.UserAvatarService;
import fruition.shared.util.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/** 다른 사용자의 프로필 사진 조회. {@code MeResponse.avatar_url}·{@code WorkspaceMemberResponse.avatar_url}이 가리키는 경로다. */
@RestController
@RequestMapping("/api/users")
@Tag(name = "Users", description = "사용자 프로필 사진 조회 API")
public class UserAvatarController {

    private static final CacheControl PRIVATE_CACHE =
            CacheControl.noCache().cachePrivate();

    private final UserAvatarService userAvatarService;

    public UserAvatarController(UserAvatarService userAvatarService) {
        this.userAvatarService = userAvatarService;
    }

    @Operation(summary = "프로필 사진 조회",
            description = "본인이거나 워크스페이스를 하나라도 같이 쓰는 멤버에게 프로필 사진 bytes를 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "이미지 반환"),
        @ApiResponse(responseCode = "304", description = "캐시된 이미지 사용"),
        @ApiResponse(responseCode = "401", description = "인증되지 않음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "사용자를 볼 수 없거나 프로필 사진이 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{user_id}/avatar")
    public ResponseEntity<byte[]> getAvatar(
            @AuthenticationPrincipal String requesterId,
            @Parameter(description = "사용자 ID", example = "user_3f1c8a6b52d7411e9c04ab5d2e7f6081")
            @PathVariable("user_id") String userId,
            WebRequest webRequest) {
        UserAvatarService.AvatarImage avatar = userAvatarService.readAvatar(requesterId, userId);
        // 직접 비교하면 W/"..." 약한 validator나 콤마로 이어진 목록에서 304를 놓친다.
        if (webRequest.checkNotModified(avatar.hash())) {
            return ResponseEntity.status(304).cacheControl(PRIVATE_CACHE).eTag(avatar.hash()).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(avatar.contentType()))
                .contentLength(avatar.bytes().length)
                .cacheControl(PRIVATE_CACHE)
                .eTag(avatar.hash())
                .header("X-Content-Type-Options", "nosniff")
                .body(avatar.bytes());
    }
}
