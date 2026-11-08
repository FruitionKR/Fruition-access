package fruition.access.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DisplayNameUpdateRequest(
        @JsonProperty("display_name")
        @NotBlank(message = "{validation.display_name.required}")
        @Size(max = 255, message = "{validation.display_name.max255}")
        @Schema(description = "새 표시 이름(255자 이하)", example = "새 이름")
        String displayName
) {}
