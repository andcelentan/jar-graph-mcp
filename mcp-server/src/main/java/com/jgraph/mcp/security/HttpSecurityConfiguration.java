package com.jgraph.mcp.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The security of the {@code http} profile (ADR-0013): every request needs a JWT access token for this server, with
 * the required scope. Declared explicitly rather than inherited from Spring Boot's defaults, which change with the
 * classpath and the web application type.
 * <p>
 * The token itself (signature with {@code RS256} through the JWKS of the issuer, {@code exp}/{@code nbf}, {@code iss},
 * {@code aud}) is validated by the decoder Spring Boot builds from {@code spring.security.oauth2.resourceserver.jwt.*}
 * in {@code application-http.yml}; this class decides what a valid token may do and what a client is told otherwise.
 */
@Configuration(proxyBeanMethods = false)
@Profile("http")
class HttpSecurityConfiguration {

    @Bean
    SecurityFilterChain mcpSecurityFilterChain(HttpSecurity http, McpSecurityProperties properties) throws Exception {
        // 401 with WWW-Authenticate: Bearer ..., resource_metadata="<configured URL>", never derived from the request:
        // behind a load balancer the address the server sees is not the one clients use
        BearerTokenAuthenticationEntryPoint entryPoint = new BearerTokenAuthenticationEntryPoint();
        String resourceMetadataUrl = properties.resourceMetadataUrl().toString();
        entryPoint.setResourceMetadataParameterResolver(request -> resourceMetadataUrl);

        http
                // Scopes become authorities named SCOPE_<scope>: one scope for every tool and resource (decision 4)
                .authorizeHttpRequests(requests -> requests
                        .anyRequest().hasAuthority("SCOPE_" + properties.requiredScope()))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(entryPoint)
                        // RFC 9728 document, public, at /.well-known/oauth-protected-resource/** (decision 5)
                        .protectedResourceMetadata(metadata -> metadata.protectedResourceMetadataCustomizer(document ->
                                document.resource(properties.resourceUrl().toString())
                                        .authorizationServers(servers -> {
                                            servers.clear();
                                            servers.add(properties.issuerUri().toString());
                                        })
                                        .scopes(scopes -> {
                                            scopes.clear();
                                            scopes.addAll(properties.scopes());
                                        }))))
                // Stateless (decision 1): no HTTP session, every request proves itself with its token
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // CSRF abuses credentials a browser sends by itself (cookies); a bearer token is never sent that way
                .csrf(AbstractHttpConfigurer::disable)
                // Before the token is read: a disallowed origin gets 403 whatever it carries (decision 6)
                .addFilterBefore(new OriginFilter(properties.allowedOrigins()), BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
