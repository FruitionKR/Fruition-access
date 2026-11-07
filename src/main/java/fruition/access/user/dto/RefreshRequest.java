package fruition.access.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(
        @JsonProperty("refresh_token")
        @NotBlank(message = "{validation.refresh_token.required}")
        @Schema(description = "로그인 때 받은 refresh token. 재발급에 성공하면 폐기되고 새 토큰으로 교체된다.",
                example = "EXAMPLE-refresh-token-not-a-real-value-0000")
        String refreshToken
) {}
