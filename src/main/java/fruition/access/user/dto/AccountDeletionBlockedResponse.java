package fruition.access.user.dto;

import fruition.access.user.exception.AccountDeletionBlockedException;
import fruition.shared.util.ErrorResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 탈퇴 거절(409). 공통 오류 본문에 OWNER를 넘겨야 할 워크스페이스 목록을 더한다. */
public record AccountDeletionBlockedResponse(
        ErrorResponse.ErrorDetail error,
        @Schema(description = "다른 멤버에게 OWNER를 넘겨야 하는 워크스페이스")
        List<AccountDeletionBlockedException.Workspace> workspaces
) {}
