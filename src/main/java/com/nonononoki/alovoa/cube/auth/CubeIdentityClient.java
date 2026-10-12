package com.nonononoki.alovoa.cube.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nonononoki.alovoa.cube.web.CubeApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * CubeOS identity bridge. Validates a Cube App (Cubemail) bearer token exactly like the Cubeline
 * registrar does ({@code cubemail_account} in backend/cubeline-registrar/registrar.py): {@code GET
 * <cubemail>/wallet/me} with the bearer, account = first non-empty of handle / username / email
 * (local part, trimmed, lower-cased), falling back to the token's own handle/sub claim once Cubemail
 * has accepted it. Validated tokens are cached by SHA-256 hash for a few minutes.
 * <p>
 * Also reads the user's Cubeline from the registrar ({@code GET <registrar>/me} with the same bearer).
 */
@Component
public class CubeIdentityClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(CubeIdentityClient.class);
    private static final Pattern HANDLE = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");
    public static final Pattern CUBELINE = Pattern.compile("^(09|28)[0-9]{9}$");
    private static final int CACHE_MAX = 20_000;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, CachedHandle> cache = new ConcurrentHashMap<>();
    private final HttpClient http;
    private final String cubemailUrl;
    private final String registrarUrl;
    private final Duration timeout;
    private final Duration cacheTtl;
    private Clock clock = Clock.systemUTC();

    private record CachedHandle(String handle, long expiresAtMillis) {
    }

    public CubeIdentityClient(@Value("${cube.cubemail.url}") String cubemailUrl,
                              @Value("${cube.registrar.url}") String registrarUrl,
                              @Value("${cube.http.timeout-seconds:15}") int timeoutSeconds,
                              @Value("${cube.token-cache-seconds:300}") int cacheSeconds) {
        this.cubemailUrl = stripSlash(cubemailUrl);
        this.registrarUrl = stripSlash(registrarUrl);
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.cacheTtl = Duration.ofSeconds(cacheSeconds);
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /**
     * @return the Cubemail handle for this bearer token, or empty when Cubemail rejects it.
     * @throws CubeApiException 502 when Cubemail can't be reached.
     */
    public Optional<String> handleForToken(String token) {
        if (token == null || token.isBlank() || token.length() > 8192) {
            return Optional.empty();
        }
        String key = sha256(token);
        long now = clock.millis();
        CachedHandle cached = cache.get(key);
        if (cached != null && cached.expiresAtMillis() > now) {
            return Optional.of(cached.handle());
        }
        Optional<String> handle = fetchHandle(token);
        handle.ifPresent(h -> {
            if (cache.size() >= CACHE_MAX) {
                cache.entrySet().removeIf(e -> e.getValue().expiresAtMillis() <= now);
                if (cache.size() >= CACHE_MAX) {
                    cache.clear();
                }
            }
            cache.put(key, new CachedHandle(h, now + cacheTtl.toMillis()));
        });
        if (handle.isEmpty()) {
            cache.remove(key);
        }
        return handle;
    }

    private Optional<String> fetchHandle(String token) {
        HttpResponse<String> r;
        try {
            r = http.send(get(cubemailUrl + "/wallet/me", token), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            LOGGER.warn("Cubemail unreachable: {}", e.toString());
            throw new CubeApiException(HttpStatus.BAD_GATEWAY, "Cube App sign-in is unreachable. Please try again shortly.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CubeApiException(HttpStatus.BAD_GATEWAY, "Cube App sign-in is unreachable. Please try again shortly.");
        }
        if (r.statusCode() != 200) {
            return Optional.empty();
        }
        String account = null;
        try {
            JsonNode me = objectMapper.readTree(r.body());
            if (me != null && me.isObject()) {
                for (String key : new String[]{"handle", "username", "email"}) {
                    JsonNode v = me.get(key);
                    String value = v != null && v.isTextual() ? v.asText() : "";
                    if (!value.isEmpty()) {
                        account = localPart(value);
                        break;
                    }
                }
            }
        } catch (IOException ignored) {
            // same as registrar.py: an unparsable body falls through to the token claims
        }
        if (account == null) {
            account = handleFromClaims(token);
        }
        if (account == null || !HANDLE.matcher(account).matches()) {
            return Optional.empty();
        }
        return Optional.of(account);
    }

    private static String localPart(String value) {
        return value.split("@", -1)[0].strip().toLowerCase();
    }

    private String handleFromClaims(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                return null;
            }
            byte[] json = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode claims = objectMapper.readTree(new String(json, StandardCharsets.UTF_8));
            String sub = "";
            if (claims.hasNonNull("handle") && !claims.get("handle").asText().isEmpty()) {
                sub = claims.get("handle").asText();
            } else if (claims.hasNonNull("sub")) {
                sub = claims.get("sub").asText();
            }
            String handle = localPart(sub);
            return handle.isEmpty() ? null : handle;
        } catch (IllegalArgumentException | IOException e) {
            return null;
        }
    }

    /**
     * @return the user's Cubeline (11 digits) from the registrar, empty when the user has none yet or the
     * registrar can't answer right now.
     */
    public Optional<String> cubelineForToken(String token) {
        try {
            HttpResponse<String> r = http.send(get(registrarUrl + "/me", token), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                LOGGER.info("Cubeline registrar answered {}", r.statusCode());
                return Optional.empty();
            }
            JsonNode body = objectMapper.readTree(r.body());
            JsonNode number = body == null ? null : body.get("number");
            if (number != null && number.isTextual() && CUBELINE.matcher(number.asText()).matches()) {
                return Optional.of(number.asText());
            }
            return Optional.empty();
        } catch (IOException e) {
            LOGGER.warn("Cubeline registrar unreachable: {}", e.toString());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    public void evictAll() {
        cache.clear();
    }

    private HttpRequest get(String url, String token) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET().build();
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}