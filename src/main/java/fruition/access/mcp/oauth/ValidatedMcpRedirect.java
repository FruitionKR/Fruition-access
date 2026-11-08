package fruition.access.mcp.oauth;

/** The exact requested redirect is retained for authorization-code binding. */
public record ValidatedMcpRedirect(String uri, String host, boolean loopbackPortException) {}
