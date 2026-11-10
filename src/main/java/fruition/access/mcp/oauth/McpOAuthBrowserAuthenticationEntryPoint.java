package fruition.access.mcp.oauth;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class McpOAuthBrowserAuthenticationEntryPoint implements AuthenticationEntryPoint {
    static final int MAX_RAW_QUERY_BYTES = 8192;
    static final int MAX_LOCATION_BYTES = 4096;
    private static final int MAX_STATE_CHARACTERS = 1024;
    private static final Set<String> PARAMETERS = Set.of("response_type", "client_id", "redirect_uri",
            "scope", "state", "code_challenge", "code_challenge_method", "resource");
    // JAR request objects are unsupported; silently dropping them could change what the client asked for.
    private static final Set<String> REJECTED_PARAMETERS = Set.of("request", "request_uri");
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");
    private static final AuthenticationEntryPoint UNAUTHORIZED =
            new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);

    private final URI frontendAuthorizationUri;

    McpOAuthBrowserAuthenticationEntryPoint(String configuredUri) {
        frontendAuthorizationUri = requireFrontendAuthorizationUri(configuredUri);
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authenticationException) throws IOException, ServletException {
        if (!isAnonymousHtmlAuthorization(request)) {
            UNAUTHORIZED.commence(request, response, authenticationException);
            return;
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        try {
            response.setHeader(HttpHeaders.LOCATION, handoffLocation(request.getQueryString()));
            response.setStatus(HttpStatus.FOUND.value());
        } catch (IllegalArgumentException invalidRequest) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
        }
    }

    String handoffLocation(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return frontendAuthorizationUri.toString();
        }
        if (rawQuery.getBytes(StandardCharsets.UTF_8).length > MAX_RAW_QUERY_BYTES) {
            throw new IllegalArgumentException("OAuth authorization query is too large");
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&", -1)) {
            int equals = pair.indexOf('=');
            String name = decode(equals < 0 ? pair : pair.substring(0, equals));
            String value = decode(equals < 0 ? "" : pair.substring(equals + 1));
            if (REJECTED_PARAMETERS.contains(name)) {
                throw new IllegalArgumentException("Unsupported OAuth request object");
            }
            // RFC 6749 §3.1: unrecognized authorization request parameters are ignored, not forwarded.
            if (!PARAMETERS.contains(name)) continue;
            if (parameters.putIfAbsent(name, value) != null
                    || "state".equals(name) && value.length() > MAX_STATE_CHARACTERS) {
                throw new IllegalArgumentException("Invalid OAuth authorization query");
            }
        }
        if (parameters.isEmpty()) {
            return frontendAuthorizationUri.toString();
        }
        String query = parameters.entrySet().stream()
                .map(parameter -> URLEncoder.encode(parameter.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(parameter.getValue(), StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.joining("&"));
        String location = frontendAuthorizationUri + "?" + query;
        if (location.getBytes(StandardCharsets.UTF_8).length > MAX_LOCATION_BYTES) {
            throw new IllegalArgumentException("OAuth authorization handoff is too large");
        }
        return location;
    }

    private static boolean isAnonymousHtmlAuthorization(HttpServletRequest request) {
        return "GET".equals(request.getMethod())
                && (request.getContextPath() + "/oauth2/authorize").equals(request.getRequestURI())
                && request.getHeader(HttpHeaders.AUTHORIZATION) == null
                && acceptsHtml(request.getHeader(HttpHeaders.ACCEPT));
    }

    private static boolean acceptsHtml(String accept) {
        if (accept == null) return false;
        try {
            return MediaType.parseMediaTypes(accept).stream().anyMatch(type -> type.getQualityValue() > 0
                    && !type.isWildcardType() && !type.isWildcardSubtype()
                    && MediaType.TEXT_HTML.isCompatibleWith(type));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static URI requireFrontendAuthorizationUri(String value) {
        try {
            URI uri = URI.create(value);
            boolean loopbackHttp = "http".equals(uri.getScheme()) && LOOPBACK_HOSTS.contains(uri.getHost());
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                    || !("https".equals(uri.getScheme()) || loopbackHttp)
                    || !"/mcp/authorize".equals(uri.getRawPath()) || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535
                    || value.getBytes(StandardCharsets.UTF_8).length > MAX_LOCATION_BYTES) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("MCP frontend authorization URI must be HTTPS (or loopback HTTP) "
                    + "with the exact /mcp/authorize path and no userinfo, query, or fragment", invalid);
        }
    }

    private static String decode(String raw) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(raw.length());
        for (int index = 0; index < raw.length();) {
            int codePoint = raw.codePointAt(index);
            if (codePoint == '%') {
                if (index + 2 >= raw.length()) throw new IllegalArgumentException("Malformed query encoding");
                int high = Character.digit(raw.charAt(index + 1), 16);
                int low = Character.digit(raw.charAt(index + 2), 16);
                if (high < 0 || low < 0) throw new IllegalArgumentException("Malformed query encoding");
                bytes.write((high << 4) + low);
                index += 3;
            } else {
                byte[] encoded = (codePoint == '+' ? " " : new String(Character.toChars(codePoint)))
                        .getBytes(StandardCharsets.UTF_8);
                bytes.writeBytes(encoded);
                index += Character.charCount(codePoint);
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException("Malformed UTF-8 query encoding", malformed);
        }
    }
}
