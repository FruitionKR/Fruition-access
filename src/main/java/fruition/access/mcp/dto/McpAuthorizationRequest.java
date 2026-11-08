package fruition.access.mcp.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import fruition.access.mcp.domain.McpClientIds;
import fruition.access.mcp.domain.McpScope;
import fruition.access.workspace.domain.WorkspacePermission;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.UniqueElements;

import java.io.IOException;
import java.util.EnumSet;
import java.util.Set;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record McpAuthorizationRequest(
        @JsonProperty("token_context") @NotNull @Valid TokenContext tokenContext,
        @NotNull @Valid Action action) {

    @JsonAnySetter
    public void rejectUnknown(String field, Object ignored) {
        throw new IllegalArgumentException("Unknown field: " + field);
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record TokenContext(
            @NotBlank @JsonDeserialize(using = StrictStringDeserializer.class) String subject,
            @JsonProperty("workspace_id") @NotBlank
            @JsonDeserialize(using = StrictStringDeserializer.class) String workspaceId,
            @JsonProperty("client_id") @NotBlank
            @JsonDeserialize(using = StrictStringDeserializer.class) String clientId,
            @JsonProperty("policy_generation") @NotNull @Min(1)
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            @JsonDeserialize(using = StrictLongDeserializer.class) Long policyGeneration,
            @NotEmpty @UniqueElements
            @JsonDeserialize(using = McpScopeSetDeserializer.class) Set<@NotNull McpScope> scopes) {

        @JsonIgnore
        @AssertTrue(message = "client_id must be an HTTPS CIMD URL")
        public boolean isClientIdValid() {
            return McpClientIds.isValid(clientId);
        }

        @JsonAnySetter
        public void rejectUnknown(String field, Object ignored) {
            throw new IllegalArgumentException("Unknown field: token_context." + field);
        }
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Action(
            @NotNull @JsonDeserialize(using = PhaseDeserializer.class) Phase phase,
            @JsonProperty("required_scopes") @NotNull @UniqueElements
            @JsonDeserialize(using = McpScopeSetDeserializer.class) Set<@NotNull McpScope> requiredScopes,
            @JsonProperty("required_permissions") @NotNull @UniqueElements
            @JsonDeserialize(using = WorkspacePermissionSetDeserializer.class)
            Set<@NotNull WorkspacePermission> requiredPermissions,
            @JsonProperty("execution_basis") @NotNull
            @JsonDeserialize(using = ExecutionBasisDeserializer.class) ExecutionBasis executionBasis,
            @JsonProperty("approved_policy_generation") @Min(1)
            @JsonDeserialize(using = StrictLongDeserializer.class) Long approvedPolicyGeneration) {

        @JsonIgnore
        @AssertTrue(message = "execution_basis and approved_policy_generation do not match phase")
        public boolean isExecutionContextValid() {
            if (executionBasis == null || phase == null) {
                return true;
            }
            return executionBasis == ExecutionBasis.EXPLICIT_WEB_APPROVAL
                    ? phase == Phase.OPERATION_BOUNDARY && approvedPolicyGeneration != null
                    : approvedPolicyGeneration == null;
        }

        @JsonAnySetter
        public void rejectUnknown(String field, Object ignored) {
            throw new IllegalArgumentException("Unknown field: action." + field);
        }
    }

    public enum Phase {
        TOOL_CALL,
        PROPOSAL_CREATE,
        WEB_APPROVAL,
        OPERATION_BOUNDARY,
        READ_RECEIPT
    }

    public enum ExecutionBasis {
        ACTIVE_GRANT,
        EXPLICIT_WEB_APPROVAL
    }

    public static final class StrictStringDeserializer extends JsonDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                context.reportInputMismatch(String.class, "Expected a JSON string");
            }
            return parser.getText();
        }
    }

    public static final class StrictLongDeserializer extends JsonDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                context.reportInputMismatch(Long.class, "Expected a JSON integer");
            }
            return parser.getLongValue();
        }
    }

    public static final class PhaseDeserializer extends JsonDeserializer<Phase> {
        @Override
        public Phase deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return strictEnum(parser, context, Phase.class);
        }
    }

    public static final class ExecutionBasisDeserializer extends JsonDeserializer<ExecutionBasis> {
        @Override
        public ExecutionBasis deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return strictEnum(parser, context, ExecutionBasis.class);
        }
    }

    public static final class McpScopeSetDeserializer extends JsonDeserializer<Set<McpScope>> {
        @Override
        public Set<McpScope> deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            requireArray(parser, context, "scope collection");
            Set<McpScope> values = EnumSet.noneOf(McpScope.class);
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                requireString(parser, context, "MCP scope");
                McpScope value;
                try {
                    value = McpScope.fromValue(parser.getText());
                } catch (IllegalArgumentException invalid) {
                    throw context.weirdStringException(parser.getText(), McpScope.class, "Unknown MCP scope");
                }
                if (!values.add(value)) {
                    context.reportInputMismatch(Set.class, "Duplicate MCP scope");
                }
            }
            return values;
        }
    }

    public static final class WorkspacePermissionSetDeserializer
            extends JsonDeserializer<Set<WorkspacePermission>> {
        @Override
        public Set<WorkspacePermission> deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            requireArray(parser, context, "permission collection");
            Set<WorkspacePermission> values = EnumSet.noneOf(WorkspacePermission.class);
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                requireString(parser, context, "workspace permission");
                WorkspacePermission value;
                try {
                    value = WorkspacePermission.fromValue(parser.getText());
                } catch (IllegalArgumentException invalid) {
                    throw context.weirdStringException(
                            parser.getText(), WorkspacePermission.class, "Unknown workspace permission");
                }
                if (!values.add(value)) {
                    context.reportInputMismatch(Set.class, "Duplicate workspace permission");
                }
            }
            return values;
        }
    }

    private static <E extends Enum<E>> E strictEnum(JsonParser parser, DeserializationContext context,
                                                     Class<E> type) throws IOException {
        requireString(parser, context, type.getSimpleName());
        try {
            return Enum.valueOf(type, parser.getText());
        } catch (IllegalArgumentException invalid) {
            throw context.weirdStringException(parser.getText(), type, "Unknown " + type.getSimpleName());
        }
    }

    private static void requireArray(JsonParser parser, DeserializationContext context, String name)
            throws IOException {
        if (!parser.isExpectedStartArrayToken()) {
            context.reportInputMismatch(Set.class, "Expected JSON array for " + name);
        }
    }

    private static void requireString(JsonParser parser, DeserializationContext context, String name)
            throws IOException {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
            context.reportInputMismatch(String.class, "Expected JSON string for " + name);
        }
    }
}
