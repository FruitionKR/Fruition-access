package fruition.access.mcp.oauth;

@FunctionalInterface
public interface CimdDocumentFetcher {
    byte[] fetch(String clientId);
}
