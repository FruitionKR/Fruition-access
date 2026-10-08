package fruition.access.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record EmailAvailabilityResponse(
        @Schema(description = "이메일로 신규 가입할 수 있으면 true", example = "true")
        boolean available,

        @JsonProperty("oauth_providers")
        @Schema(description = "같은 이메일로 소셜 가입한 계정의 provider(이름순). 비어 있지 않으면 새로 가입하기보다 "
                + "그 소셜 계정으로 로그인한 뒤 설정에서 연동하도록 안내한다. 가입 자체는 막지 않는다.",
                example = "[\"google\"]")
        List<String> oauthProviders
) {}
