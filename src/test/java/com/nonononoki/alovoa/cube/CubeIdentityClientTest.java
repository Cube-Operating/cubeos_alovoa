package com.nonononoki.alovoa.cube;

import com.nonononoki.alovoa.cube.auth.CubeIdentityClient;
import com.nonononoki.alovoa.cube.web.CubeApiException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** The Cubemail/registrar bridge against a local mock of both HTTP APIs. */
class CubeIdentityClientTest {

    private static FakeCubeServices fake;

    @BeforeAll
    static void start() throws Exception {
        fake = FakeCubeServices.start();
    }

    @AfterAll
    static void stop() {
        fake.stop();
    }

    @BeforeEach
    void reset() {
        fake.reset();
    }

    private CubeIdentityClient client() {
        return new CubeIdentityClient(fake.cubemailUrl(), "/wallet/me", fake.registrarUrl(), 5, 300);
    }

    @Test
    void handleTakesPrecedenceOverUsernameAndEmail() {
        fake.meBody("tok1", "{\"handle\":\" Alice \",\"username\":\"bob\",\"email\":\"carol@cubemail.io\"}");
        assertEquals(Optional.of("alice"), client().handleForToken("tok1"));
        fake.meBody("tok2", "{\"username\":\"Bob\",\"email\":\"carol@cubemail.io\"}");
        assertEquals(Optional.of("bob"), client().handleForToken("tok2"));
        fake.meBody("tok3", "{\"email\":\"Carol.X@cubemail.io\"}");
        assertEquals(Optional.of("carol.x"), client().handleForToken("tok3"));
        assertEquals("Bearer tok1", fake.lastAuthorization("/api/cubemail/wallet/me", "tok1"));
    }

    @Test
    void fallsBackToTokenClaimsOnceCubemailAcceptedTheToken() {
        String claims = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"dave@cubemail.io\"}".getBytes(StandardCharsets.UTF_8));
        String jwt = "eyJhbGciOiJIUzI1NiJ9." + claims + ".sig";
        fake.meBody(jwt, "{}");
        assertEquals(Optional.of("dave"), client().handleForToken(jwt));
    }

    @Test
    void rejectedTokenIsUnauthenticated() {
        assertEquals(Optional.empty(), client().handleForToken("nope"));
        assertEquals(Optional.empty(), client().handleForToken(""));
        // a handle that can't be an e-mail local part is refused
        fake.meBody("tokx", "{\"handle\":\"a b<script>\"}");
        assertEquals(Optional.empty(), client().handleForToken("tokx"));
    }

    @Test
    void validatedTokensAreCached() {
        fake.meBody("tok1", "{\"handle\":\"alice\"}");
        CubeIdentityClient c = client();
        assertEquals(Optional.of("alice"), c.handleForToken("tok1"));
        assertEquals(Optional.of("alice"), c.handleForToken("tok1"));
        assertEquals(Optional.of("alice"), c.handleForToken("tok1"));
        assertEquals(1, fake.hits("/api/cubemail/wallet/me"));
        // invalid tokens are not cached as valid
        assertEquals(Optional.empty(), c.handleForToken("bad"));
        assertEquals(Optional.empty(), c.handleForToken("bad"));
        assertEquals(3, fake.hits("/api/cubemail/wallet/me"));
    }

    @Test
    void cacheExpires() {
        fake.meBody("tok1", "{\"handle\":\"alice\"}");
        CubeIdentityClient c = new CubeIdentityClient(fake.cubemailUrl(), "/wallet/me", fake.registrarUrl(), 5, 0);
        c.handleForToken("tok1");
        c.handleForToken("tok1");
        assertEquals(2, fake.hits("/api/cubemail/wallet/me"));
    }

    @Test
    void unreachableCubemailIsBadGateway() {
        CubeIdentityClient c = new CubeIdentityClient("http://127.0.0.1:1/api/cubemail", "/wallet/me", fake.registrarUrl(), 2, 300);
        CubeApiException e = assertThrows(CubeApiException.class, () -> c.handleForToken("tok1"));
        assertEquals(HttpStatus.BAD_GATEWAY, e.getStatus());
    }

    @Test
    void cubelineFromRegistrar() {
        fake.meBody("tok1", "{\"handle\":\"alice\"}");
        fake.cubeline("alice", "09123456789");
        assertEquals(Optional.of("09123456789"), client().cubelineForToken("tok1"));
        assertEquals("Bearer tok1", fake.lastAuthorization("/cubeline/me", "tok1"));
        fake.cubeline("alice", null);
        assertEquals(Optional.empty(), client().cubelineForToken("tok1"));
        fake.cubeline("alice", "12345"); // malformed numbers are ignored
        assertEquals(Optional.empty(), client().cubelineForToken("tok1"));
        assertEquals(Optional.empty(), client().cubelineForToken("unknown-token"));
        CubeIdentityClient down = new CubeIdentityClient(fake.cubemailUrl(), "/wallet/me", "http://127.0.0.1:1/cubeline", 2, 300);
        assertEquals(Optional.empty(), down.cubelineForToken("tok1"));
    }
}
