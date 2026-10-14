package fruition.access.user.dto;

import fruition.shared.util.ErrorResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 소셜 신규 가입 거절(409 EMAIL_REGISTERED). 공통 오류 본문에 같은 이메일로 가입한 계정의 provider 목록을 더한다. */
public record EmailRegisteredResponse(
        ErrorResponse.ErrorDetail error,
        @Schema(description = "같은 이메일로 가입한 계정의 provider(이름순). 이메일 가입 계정은 local", example = "[\"local\"]")
        List<String> providers
) {}
