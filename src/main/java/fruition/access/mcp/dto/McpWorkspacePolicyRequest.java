package fruition.access.mcp.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import fruition.access.mcp.domain.McpScope;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.io.IOException;
import java.util.Set;

@Schema(requiredProperties = {"enabled", "allowed_scopes"},
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record McpWorkspacePolicyRequest(
        @NotNull @JsonDeserialize(using = StrictBooleanDeserializer.class) Boolean enabled,
        @JsonProperty("allowed_scopes") @NotNull
        @JsonDeserialize(using = McpAuthorizationRequest.McpScopeSetDeserializer.class)
        Set<McpScope> allowedScopes
) {
    @JsonAnySetter
    public void rejectUnknown(String field, Object ignored) {
        throw new IllegalArgumentException("Unknown field: " + field);
    }

    public static final class StrictBooleanDeserializer extends JsonDeserializer<Boolean> {
        @Override
        public Boolean deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_TRUE) && !parser.hasToken(JsonToken.VALUE_FALSE)) {
                context.reportInputMismatch(Boolean.class, "Expected a JSON boolean");
            }
            return parser.getBooleanValue();
        }
    }
}
