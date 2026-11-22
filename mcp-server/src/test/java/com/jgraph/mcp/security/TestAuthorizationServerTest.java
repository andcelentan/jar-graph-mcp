package com.jgraph.mcp.security;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Checks the test authorization server against the decoder of Spring Security, configured from the issuer alone as a
 * resource server is: discovery, JWKS download, signature, time and issuer. If these fail, a failing security test
 * of the server would say nothing about the server.
 * <p>
 * The last test pins down what this default decoder does not check: the audience, which ADR-0013 adds explicitly.
 */
class TestAuthorizationServerTest {

    private static final String AUDIENCE = "https://mcp.test/mcp";
    private static final String SCOPE = "graph:read";

    private static TestAuthorizationServer authorizationServer;
    private static JwtDecoder decoder;

    @BeforeAll
    static void startAuthorizationServer() {
        authorizationServer = TestAuthorizationServer.start();
        // Reads /.well-known/openid-configuration, then the jwks_uri it names; validates time and issuer
        decoder = JwtDecoders.fromIssuerLocation(authorizationServer.issuer());
    }

    @AfterAll
    static void stopAuthorizationServer() {
        authorizationServer.close();
    }

    @Test
    void shouldIssueTokensThatADecoderConfiguredFromTheIssuerAccepts() {
        Jwt jwt = decoder.decode(authorizationServer.token(AUDIENCE, SCOPE).serialize());

        assertThat(jwt.getIssuer()).hasToString(authorizationServer.issuer());
        assertThat(jwt.getAudience()).containsExactly(AUDIENCE);
        assertThat(jwt.getClaimAsString("scope")).isEqualTo(SCOPE);
        assertThat(jwt.getHeaders()).containsEntry("alg", "RS256").containsEntry("kid", "test-signing-key");
    }

    @Test
    void shouldIssueTokensWithoutScope() {
        Jwt jwt = decoder.decode(authorizationServer.token(AUDIENCE, SCOPE).withoutScope().serialize());

        assertThat(jwt.hasClaim("scope")).isFalse();
    }

    @Test
    void shouldRejectASignatureMadeWithAnotherKey() {
        String token = authorizationServer.token(AUDIENCE, SCOPE).signedWithForeignKey().serialize();

        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("signature");
    }

    @Test
    void shouldRejectAnUnsignedToken() {
        String token = authorizationServer.token(AUDIENCE, SCOPE).unsigned().serialize();

        assertThat(token).endsWith(".");
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void shouldRejectAnExpiredToken() {
        String token = authorizationServer.token(AUDIENCE, SCOPE).expired(Duration.ofMinutes(5)).serialize();

        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void shouldRejectAnotherIssuer() {
        String token = authorizationServer.token(AUDIENCE, SCOPE).issuer("https://elsewhere.test").serialize();

        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("iss");
    }

    @Test
    void shouldShowThatTheDefaultDecoderDoesNotCheckTheAudience() {
        // A token for another service of the same issuer passes: the confused deputy ADR-0013 closes explicitly
        Jwt jwt = decoder.decode(
                authorizationServer.token(AUDIENCE, SCOPE).audience("https://other-service.test").serialize());

        assertThat(jwt.getAudience()).containsExactly("https://other-service.test");
    }
}
