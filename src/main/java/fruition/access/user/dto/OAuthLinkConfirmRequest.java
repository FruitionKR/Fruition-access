package fruition.access.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record OAuthLinkConfirmRequest(
        @JsonProperty("link_code")
        @NotBlank
        @Schema(description = "연동 콜백이 ?link_code= 로 넘긴 1회용 code")
        String linkCode
) {}
