package com.jgraph.mcp.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Stands in for the authorization server (Cognito in production) in tests (ADR-0013): the server under test validates
 * real tokens, signed with real keys, through the same discovery and JWKS download it uses in production. Only the
 * issuer is fake.
 * <p>
 * It serves, on a random port of the loopback interface:
 * <ul>
 *   <li>{@code /.well-known/openid-configuration}: the discovery document, where a resource server configured with
 *   only the issuer finds the address of the keys ({@code jwks_uri});</li>
 *   <li>{@code /jwks}: the public key, never the private one.</li>
 * </ul>
 * Tokens are made with {@link #token(String, String)}: a valid token by default, changed one claim at a time to build
 * each rejected case.
 */
public final class TestAuthorizationServer implements AutoCloseable {

    private static final String DISCOVERY_PATH = "/.well-known/openid-configuration";
    private static final String JWKS_PATH = "/jwks";
    private static final String KEY_ID = "test-signing-key";

    private final RSAKey signingKey;
    // Same key id as the signing key: a token signed with it reaches the signature check (see Token#signedWithForeignKey)
    private final RSAKey foreignKey;
    private final HttpServer httpServer;
    private final String issuer;

    private TestAuthorizationServer(RSAKey signingKey, RSAKey foreignKey, HttpServer httpServer) {
        this.signingKey = signingKey;
        this.foreignKey = foreignKey;
        this.httpServer = httpServer;
        this.issuer = "http://127.0.0.1:" + httpServer.getAddress().getPort();
    }

    /** Generates the keys and starts serving discovery and JWKS; close it to stop the HTTP server. */
    public static TestAuthorizationServer start() {
        try {
            RSAKey signingKey = generateKey();
            RSAKey foreignKey = generateKey();
            HttpServer httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            TestAuthorizationServer server = new TestAuthorizationServer(signingKey, foreignKey, httpServer);
            httpServer.createContext(DISCOVERY_PATH, exchange -> respond(exchange, server.discoveryDocument()));
            httpServer.createContext(JWKS_PATH, exchange -> respond(exchange, server.publicKeys()));
            httpServer.start();
            return server;
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot generate the test signing keys", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot start the test authorization server", e);
        }
    }

    /** The {@code iss} of its tokens, and what the server under test is configured to trust. */
    public String issuer() {
        return issuer;
    }

    /**
     * A token valid for {@code audience} with {@code scope}, issued now and expiring in five minutes, until one of the
     * methods of the returned {@link Token} changes it.
     */
    public Token token(String audience, String scope) {
        return new Token(audience, scope);
    }

    @Override
    public void close() {
        httpServer.stop(0);
    }

    private static RSAKey generateKey() throws JOSEException {
        return new RSAKeyGenerator(2048)
                .keyID(KEY_ID)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .generate();
    }

    private String discoveryDocument() {
        // Only what a resource server reads: the issuer (checked against the configured one) and where the keys are
        return JSONObjectUtils.toJSONString(Map.of(
                "issuer", issuer,
                "jwks_uri", issuer + JWKS_PATH));
    }

    private String publicKeys() {
        // true = public parameters only: publishing the private exponent would let anyone sign tokens
        return JSONObjectUtils.toJSONString(new JWKSet(signingKey).toJSONObject(true));
    }

    private static void respond(HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /**
     * One token being built: every claim starts valid, each method spoils one thing, {@link #serialize()} writes it.
     */
    public final class Token {

        private String issuer = TestAuthorizationServer.this.issuer;
        private String audience;
        private String scope;
        private Instant issuedAt = Instant.now();
        private Instant expiresAt = issuedAt.plus(Duration.ofMinutes(5));
        private RSAKey key = signingKey;
        private boolean signed = true;

        private Token(String audience, String scope) {
            this.audience = Objects.requireNonNull(audience, "audience");
            this.scope = Objects.requireNonNull(scope, "scope");
        }

        public Token issuer(String issuer) {
            this.issuer = issuer;
            return this;
        }

        public Token audience(String audience) {
            this.audience = audience;
            return this;
        }

        /** No {@code scope} claim at all, as a token issued for another purpose. */
        public Token withoutScope() {
            this.scope = null;
            return this;
        }

        /**
         * Expired {@code ago}. Resource servers tolerate clock differences (60 seconds by default in Spring): an
         * expired token must be older than that to be rejected.
         */
        public Token expired(Duration ago) {
            this.expiresAt = Instant.now().minus(ago);
            this.issuedAt = expiresAt.minus(Duration.ofMinutes(5));
            return this;
        }

        /**
         * Signed with a key the server does not publish, under the same key id: the server finds a key for it and the
         * signature itself fails. A different key id would test the lookup of an unknown key instead.
         */
        public Token signedWithForeignKey() {
            this.key = foreignKey;
            return this;
        }

        /** {@code alg: none} and no signature: what a careless decoder trusting the header would accept. */
        public Token unsigned() {
            this.signed = false;
            return this;
        }

        /** The compact form sent as {@code Authorization: Bearer <token>}. */
        public String serialize() {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .audience(audience)
                    .subject("test-user")
                    .jwtID(UUID.randomUUID().toString())
                    .issueTime(Date.from(issuedAt))
                    .notBeforeTime(Date.from(issuedAt))
                    .expirationTime(Date.from(expiresAt));
            if (scope != null) {
                claims.claim("scope", scope);
            }
            if (!signed) {
                return new PlainJWT(claims.build()).serialize();
            }
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
            try {
                jwt.sign(new RSASSASigner(key));
            } catch (JOSEException e) {
                throw new IllegalStateException("Cannot sign the test token", e);
            }
            return jwt.serialize();
        }
    }
}
