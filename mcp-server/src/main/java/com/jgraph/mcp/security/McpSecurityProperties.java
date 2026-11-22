package com.jgraph.mcp.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;

import java.net.URI;
import java.util.List;
import java.util.Set;

/**
 * Security settings of the {@code http} profile (ADR-0013), bound from {@code jar-graph.security.*}, which
 * {@code application-http.yml} fills from environment variables.
 * <p>
 * The resource URL is one value with two uses: the audience every token must carry, and the identity published in the
 * Protected Resource Metadata. Keeping a single value means the two can never diverge.
 *
 * @param issuerUri      the authorization server whose tokens are trusted ({@code MCP_OAUTH_ISSUER_URI})
 * @param resourceUrl    the public URL of the MCP endpoint, e.g. {@code https://mcp.example.com/mcp}
 *                       ({@code MCP_RESOURCE_URL})
 * @param requiredScope  the scope every request needs, as the authorization server writes it in the token
 *                       ({@code MCP_REQUIRED_SCOPE})
 * @param allowedOrigins the {@code Origin} header values accepted; empty refuses every request coming from a web page
 *                       ({@code MCP_ALLOWED_ORIGINS})
 */
@Profile("http") // Found by @ConfigurationPropertiesScan in every profile: STDIO has none of these variables
@ConfigurationProperties("jar-graph.security")
record McpSecurityProperties(URI issuerUri, URI resourceUrl, String requiredScope, Set<String> allowedOrigins) {

    /** RFC 9728, section 3.1: the well-known path goes between the host and the path of the resource. */
    private static final String METADATA_PATH = "/.well-known/oauth-protected-resource";

    McpSecurityProperties {
        // Blank values reach here when a variable is set but empty: refuse them like missing ones, naming the variable
        requireAbsolute(issuerUri, "MCP_OAUTH_ISSUER_URI");
        requireAbsolute(resourceUrl, "MCP_RESOURCE_URL");
        // A missing variable does not fail the binding: Spring Boot keeps "${MCP_REQUIRED_SCOPE}" as literal text
        if (requiredScope == null || requiredScope.isBlank() || requiredScope.startsWith("${")) {
            throw new IllegalArgumentException("MCP_REQUIRED_SCOPE must name the scope every request needs, got: "
                    + requiredScope);
        }
        allowedOrigins = allowedOrigins == null ? Set.of() : Set.copyOf(allowedOrigins);
    }

    /** Where clients read the metadata: {@code https://host/mcp} → {@code https://host/.well-known/oauth-protected-resource/mcp}. */
    URI resourceMetadataUrl() {
        String path = resourceUrl.getRawPath() == null ? "" : resourceUrl.getRawPath();
        return URI.create(resourceUrl.getScheme() + "://" + resourceUrl.getRawAuthority() + METADATA_PATH
                + (path.equals("/") ? "" : path));
    }

    List<String> scopes() {
        return List.of(requiredScope);
    }

    private static void requireAbsolute(URI uri, String variable) {
        if (uri == null || !uri.isAbsolute() || uri.getRawAuthority() == null) {
            throw new IllegalArgumentException(variable + " must be an absolute URL such as https://host/path, got: "
                    + uri);
        }
    }
}
