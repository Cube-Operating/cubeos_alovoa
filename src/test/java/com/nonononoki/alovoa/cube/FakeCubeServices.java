package com.nonononoki.alovoa.cube;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local stand-in for Cubemail ({@code GET /api/cubemail/wallet/me}) and the Cubeline registrar
 * ({@code GET /cubeline/me}), both keyed by the bearer token.
 */
public class FakeCubeServices {

    private final HttpServer server;
    private final Map<String, String> meBodies = new ConcurrentHashMap<>();       // token -> /wallet/me body
    private final Map<String, String> tokenHandles = new ConcurrentHashMap<>();   // token -> handle
    private final Map<String, String> cubelines = new ConcurrentHashMap<>();      // handle -> number ("" = none)
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    private final Map<String, String> lastAuth = new ConcurrentHashMap<>();

    private FakeCubeServices(HttpServer server) {
        this.server = server;
    }

    public static FakeCubeServices start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        FakeCubeServices fake = new FakeCubeServices(server);
        server.createContext("/api/cubemail/wallet/me", fake::walletMe);
        server.createContext("/cubeline/me", fake::cubelineMe);
        server.start();
        return fake;
    }

    public void stop() {
        server.stop(0);
    }

    public void reset() {
        meBodies.clear();
        tokenHandles.clear();
        cubelines.clear();
        hits.clear();
        lastAuth.clear();
    }

    public String cubemailUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/cubemail";
    }

    public String registrarUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/cubeline";
    }

    /** Registers a Cube App user: token "tok-<handle>" and optionally a Cubeline. */
    public String user(String handle, String cubeline) {
        String token = "tok-" + handle;
        meBody(token, "{\"handle\":\"" + handle + "\",\"email\":\"" + handle + "@cubemail.io\"}");
        cubeline(handle, cubeline);
        return token;
    }

    public void meBody(String token, String body) {
        meBodies.put(token, body);
        String h = body.replaceAll(".*\"handle\":\"\\s*([^\"]*?)\\s*\".*", "$1");
        if (!h.equals(body)) {
            tokenHandles.put(token, h.toLowerCase());
        }
    }

    public void cubeline(String handle, String number) {
        cubelines.put(handle, number == null ? "" : number);
    }

    public int hits(String path) {
        AtomicInteger i = hits.get(path);
        return i == null ? 0 : i.get();
    }

    public String lastAuthorization(String path, String token) {
        return lastAuth.get(path + "|" + token);
    }

    private static String bearer(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        return auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : "";
    }

    private void count(HttpExchange ex) {
        String path = ex.getHttpContext().getPath();
        hits.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
        lastAuth.put(path + "|" + bearer(ex), ex.getRequestHeaders().getFirst("Authorization"));
    }

    private void walletMe(HttpExchange ex) throws IOException {
        count(ex);
        String body = meBodies.get(bearer(ex));
        if (body == null) {
            send(ex, 401, "{\"error\":\"unauthorized\"}");
        } else {
            send(ex, 200, body);
        }
    }

    private void cubelineMe(HttpExchange ex) throws IOException {
        count(ex);
        String handle = tokenHandles.get(bearer(ex));
        if (handle == null) {
            send(ex, 401, "{\"error\":\"unauthorized\"}");
            return;
        }
        String number = cubelines.getOrDefault(handle, "");
        send(ex, 200, number.isEmpty() ? "{\"number\":null}" : "{\"number\":\"" + number + "\"}");
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
}
