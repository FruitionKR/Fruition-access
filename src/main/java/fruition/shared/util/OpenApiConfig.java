package fruition.shared.util;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springdoc.core.customizers.OpenApiCustomizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Configuration
public class OpenApiConfig {

    private static final String BEARER_SECURITY_SCHEME = "bearerAuth";
    private static final String INTERNAL_SECURITY_SCHEME = "internalToken";
    private static final String WORKSPACE_ID_EXAMPLE = "ws_9d47a0e9a6324341b47562553b75f92a";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Fruition API")
                        .description("Fruition PoC 백엔드 API")
                        .version("0.0.1")
                        .contact(new Contact()
                                .name("Fruition")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SECURITY_SCHEME))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SECURITY_SCHEME, new SecurityScheme()
                                .name(BEARER_SECURITY_SCHEME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT"))
                        .addSecuritySchemes(INTERNAL_SECURITY_SCHEME, new SecurityScheme()
                                .name("X-Internal-Token")
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)));
    }

    /**
     * tag 목록을 이름순으로 고정한다. springdoc.writer-with-order-by-keys는 map만 정렬하므로
     * 배열인 tags는 스캔 순서에 따라 흔들린다 — 명세를 파일로 커밋하려면 여기서 정렬해야 한다.
     */
    @Bean
    public OpenApiCustomizer tagOrderCustomizer() {
        return openApi -> {
            List<Tag> tags = openApi.getTags();
            if (tags == null) {
                return;
            }
            List<Tag> sorted = new ArrayList<>(tags);
            sorted.sort(Comparator.comparing(Tag::getName, Comparator.nullsLast(Comparator.naturalOrder())));
            openApi.setTags(sorted);
        };
    }

    @Bean
    public OpenApiCustomizer workspaceIdExampleCustomizer() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().values().stream()
                    .flatMap(pathItem -> pathItem.readOperations().stream())
                    .filter(operation -> operation.getParameters() != null)
                    .flatMap(operation -> operation.getParameters().stream())
                    .filter(parameter -> "workspace_id".equals(parameter.getName()))
                    .forEach(parameter -> {
                        parameter.setExample(WORKSPACE_ID_EXAMPLE);
                        if (parameter.getSchema() != null) {
                            parameter.getSchema().setDefault(WORKSPACE_ID_EXAMPLE);
                        }
                    });
        };
    }

    @Bean
    public OpenApiCustomizer mcpAuthorizationNullableResponseCustomizer() {
        return openApi -> {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
                return;
            }
            var schemas = openApi.getComponents().getSchemas();
            nullableStringEnum(schemas.get("McpAuthorizationResponse"), "deny_reason");
            nullableReference(schemas.get("McpAuthorizationResponse"), "grant");
            nullableReference(schemas.get("McpAuthorizationResponse"), "policy");
            nullableReference(schemas.get("McpAuthorizationResponse"), "current_access");
            nullableStringEnum(schemas.get("GrantView"), "revocation_reason");
            nullableStringEnum(schemas.get("Connection"), "revocation_reason");
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void nullableStringEnum(Schema<?> parent, String property) {
        Schema<?> original = parent.getProperties().get(property);
        Schema stringEnum = new Schema<>().types(Set.of("string"));
        stringEnum.setEnum(original.getEnum());
        parent.addProperties(property, nullable(stringEnum));
    }

    private static void nullableReference(Schema<?> parent, String property) {
        Schema<?> original = parent.getProperties().get(property);
        parent.addProperties(property, nullable(new Schema<>().$ref(original.get$ref())));
    }

    private static Schema<?> nullable(Schema<?> nonNull) {
        return new ComposedSchema()
                .addOneOfItem(nonNull)
                .addOneOfItem(new Schema<>().types(Set.of("null")));
    }
}
