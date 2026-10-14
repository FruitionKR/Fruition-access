package fruition.access.workspace.controller;

import fruition.access.workspace.dto.WorkspaceMemberListResponse;
import fruition.access.workspace.dto.WorkspaceMemberResponse;
import fruition.access.workspace.dto.WorkspaceMemberRoleUpdateRequest;
import fruition.access.workspace.service.WorkspaceMemberService;
import fruition.shared.util.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/{workspace_id}/members")
@Tag(name = "Workspace Members", description = "워크스페이스 멤버 관리 API")
public class WorkspaceMemberController {

    private final WorkspaceMemberService workspaceMemberService;

    public WorkspaceMemberController(WorkspaceMemberService workspaceMemberService) {
        this.workspaceMemberService = workspaceMemberService;
    }

    @Operation(summary = "멤버 목록 조회",
            description = "워크스페이스의 활성 멤버를 합류 순으로 반환합니다. 멤버만 조회할 수 있습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공",
            content = @Content(schema = @Schema(implementation = WorkspaceMemberListResponse.class))),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없거나 멤버가 아님",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<WorkspaceMemberListResponse> listMembers(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "워크스페이스 ID", example = "ws_abc12345")
            @PathVariable("workspace_id") String workspaceId) {
        return ResponseEntity.ok(workspaceMemberService.list(userId, workspaceId));
    }

    @Operation(summary = "멤버 역할·권한 변경",
            description = "멤버의 역할(role)과 권한 항목(permissions)을 변경합니다. 둘 다 선택이지만 하나는 필요하며, "
                    + "permissions는 전체 집합을 교체합니다. OWNER만 호출할 수 있습니다. "
                    + "결과 역할이 OWNER인데 permissions를 보내면 400 OWNER_PERMISSIONS_FIXED입니다. "
                    + "역할과 권한 항목은 독립이라 역할을 바꿔도 부여된 권한은 그대로입니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "변경 성공",
            content = @Content(schema = @Schema(implementation = WorkspaceMemberResponse.class))),
        @ApiResponse(responseCode = "400",
            description = "role·permissions가 모두 없음, 알 수 없거나 중복된 권한 항목, 또는 OWNER_PERMISSIONS_FIXED",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "OWNER 권한 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "워크스페이스 또는 대상 멤버를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "마지막 OWNER는 강등할 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/{user_id}")
    public ResponseEntity<WorkspaceMemberResponse> changeRole(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "워크스페이스 ID", example = "ws_abc12345")
            @PathVariable("workspace_id") String workspaceId,
            @Parameter(description = "대상 멤버 사용자 ID", example = "user_1f9a74af")
            @PathVariable("user_id") String targetUserId,
            @Valid @RequestBody WorkspaceMemberRoleUpdateRequest request) {
        return ResponseEntity.ok(
                workspaceMemberService.changeRole(userId, workspaceId, targetUserId, request));
    }

    @Operation(summary = "멤버 제거·탈퇴",
            description = "OWNER는 다른 멤버를 제거할 수 있고, 멤버는 자신을 제거해 탈퇴할 수 있습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "제거 성공"),
        @ApiResponse(responseCode = "403", description = "다른 멤버를 제거할 OWNER 권한 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "워크스페이스 또는 대상 멤버를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "마지막 OWNER는 제거할 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{user_id}")
    public ResponseEntity<Void> remove(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "워크스페이스 ID", example = "ws_abc12345")
            @PathVariable("workspace_id") String workspaceId,
            @Parameter(description = "대상 멤버 사용자 ID", example = "user_1f9a74af")
            @PathVariable("user_id") String targetUserId) {
        workspaceMemberService.remove(userId, workspaceId, targetUserId);
        return ResponseEntity.noContent().build();
    }
}
