package fruition.access.mcp.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum McpScope {
    READ("read"),
    WRITE_PROPOSE("write_propose"),
    WIKI_WRITE("wiki_write");

    private final String value;

    McpScope(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static McpScope fromValue(String value) {
        for (McpScope scope : values()) {
            if (scope.value.equals(value)) {
                return scope;
            }
        }
        throw new IllegalArgumentException("Unknown MCP scope");
    }
}
