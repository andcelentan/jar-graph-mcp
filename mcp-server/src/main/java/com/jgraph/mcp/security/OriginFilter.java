package com.jgraph.mcp.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Refuses requests coming from a web page whose origin is not allowed, before any token is read (ADR-0013,
 * decision 6). Browsers add {@code Origin} by themselves and scripts cannot forge it; MCP clients are not browsers and
 * do not send it, so a request without {@code Origin} goes on.
 * <p>
 * This is not CORS. In a DNS rebinding attack the page and the target share the same origin for the browser, so CORS
 * never applies, and Spring's CORS support, which compares {@code Origin} with the host of the request itself, lets the
 * request through. Only a comparison with a configured list stops it.
 */
final class OriginFilter extends OncePerRequestFilter {

    private final Set<String> allowedOrigins;

    OriginFilter(Set<String> allowedOrigins) {
        this.allowedOrigins = Set.copyOf(allowedOrigins);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !allowedOrigins.contains(origin)) {
            // setStatus, not sendError: sendError dispatches to /error, which the filter chain would answer with 401
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        chain.doFilter(request, response);
    }
}
