package com.nonononoki.alovoa.cube.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nonononoki.alovoa.config.SecurityConfig;
import com.nonononoki.alovoa.cube.service.CubeAccountService;
import com.nonononoki.alovoa.cube.web.CubeApiException;
import com.nonononoki.alovoa.entity.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CubeOS: authenticates {@code /api/v1/cube/**} with {@code Authorization: Bearer <cubemail token>}.
 * The token is validated by Cubemail (cached), the Alovoa user for the handle is created on first use,
 * and the request runs as that user (principal = user UUID, which Alovoa's AuthService resolves).
 * Stateless: no session, no cookies.
 * <p>
 * Deliberately not a Spring bean, so Spring Boot doesn't also register it as a global servlet filter.
 */
public class CubemailBearerFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(CubemailBearerFilter.class);
    public static final String ATTR_TOKEN = "cube.bearer";

    private final CubeIdentityClient identityClient;
    private final CubeAccountService accountService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CubemailBearerFilter(CubeIdentityClient identityClient, CubeAccountService accountService) {
        this.identityClient = identityClient;
        this.accountService = accountService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            writeError(response, HttpStatus.UNAUTHORIZED, "Sign in to Cube App to use dating.");
            return;
        }
        String token = header.substring(7).strip();
        User user;
        try {
            Optional<String> handle = identityClient.handleForToken(token);
            if (handle.isEmpty()) {
                writeError(response, HttpStatus.UNAUTHORIZED, "Your Cube App session has expired. Please sign in again.");
                return;
            }
            user = accountService.provision(handle.get());
            if (user.isDisabled() || user.isAdmin()) {
                writeError(response, HttpStatus.FORBIDDEN, "This dating account has been disabled.");
                return;
            }
            accountService.refreshCubelineIfDue(user, token);
        } catch (CubeApiException e) {
            writeError(response, e.getStatus(), e.getMessage());
            return;
        } catch (RuntimeException e) {
            LOGGER.error("Cube App sign-in failed", e);
            writeError(response, HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong. Please try again.");
            return;
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(user.getUuid().toString(), null,
                List.of(new SimpleGrantedAuthority(SecurityConfig.ROLE_USER))));
        SecurityContextHolder.setContext(context);
        request.setAttribute(ATTR_TOKEN, token);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        if (status == HttpStatus.UNAUTHORIZED) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        objectMapper.writeValue(response.getOutputStream(), Map.of("error", message));
    }
}