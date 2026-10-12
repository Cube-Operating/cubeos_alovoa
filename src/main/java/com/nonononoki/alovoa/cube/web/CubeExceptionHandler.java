package com.nonononoki.alovoa.cube.web;

import com.nonononoki.alovoa.model.AlovoaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.Map;

/**
 * CubeOS: turns every exception raised by the Cube App API into {@code {"error": "user-facing message"}}.
 * Scoped to the cube web package so Alovoa's own {@code ExceptionHandler} keeps handling the web UI.
 */
@RestControllerAdvice(basePackageClasses = CubeController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CubeExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CubeExceptionHandler.class);
    static final String GENERIC = "Something went wrong. Please try again.";

    /** Upstream Alovoa error codes that can reach the Cube API, mapped to user-facing text. */
    private static final Map<String, String> ALOVOA_MESSAGES = Map.ofEntries(
            Map.entry("intention cooldown not finished", "You can change what you're looking for once a day."),
            Map.entry("max_length_exceeded", "That text is too long."),
            Map.entry("url_detected", "Links aren't allowed in your bio."),
            Map.entry("interest_invalid_size", "Interests must be 3 to 30 characters, up to 10 interests."),
            Map.entry("interest_unsupported_characters", "Interests can only contain letters, numbers and dashes."),
            Map.entry("interest_already_exists", "You already added that interest."),
            Map.entry("max_image_amount_exceeded", "You can have up to 6 photos."),
            Map.entry("users_not_compatible", "You can't like this profile."),
            Map.entry("is_blocked", "This profile is no longer available."),
            Map.entry("user_is_same_user", "You can't do that with your own profile."),
            Map.entry("user_not_found", "This profile is no longer available."),
            Map.entry("max_distance_exceeded", "Distance must be between 1 and 200 km."),
            Map.entry("not_supported", "That option isn't available.")
    );

    public static String alovoaMessage(AlovoaException e) {
        String code = e.getMessage() == null ? "" : e.getMessage();
        if (code.startsWith("user_not_found")) {
            return ALOVOA_MESSAGES.get("user_not_found");
        }
        return ALOVOA_MESSAGES.getOrDefault(code, GENERIC);
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Map.of("error", message));
    }

    @ExceptionHandler(CubeApiException.class)
    public ResponseEntity<Map<String, String>> cube(CubeApiException e) {
        if (e.getCode() != null) {
            return ResponseEntity.status(e.getStatus()).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("error", e.getMessage(), "code", e.getCode()));
        }
        return body(e.getStatus(), e.getMessage());
    }

    @ExceptionHandler(AlovoaException.class)
    public ResponseEntity<Map<String, String>> alovoa(AlovoaException e) {
        LOGGER.info("Cube API: Alovoa error {}", e.getMessage());
        String msg = alovoaMessage(e);
        HttpStatus status = msg.equals(ALOVOA_MESSAGES.get("user_not_found")) ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        return body(status, msg);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> tooLarge(MaxUploadSizeExceededException e) {
        return body(HttpStatus.PAYLOAD_TOO_LARGE, "Photos must be 5 MB or smaller.");
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    public ResponseEntity<Map<String, String>> multipart(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "Please attach a photo.");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<Map<String, String>> badInput(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "The request was not valid.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, String>> method(HttpRequestMethodNotSupportedException e) {
        return body(HttpStatus.METHOD_NOT_ALLOWED, "The request was not valid.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> other(Exception e) {
        LOGGER.error("Cube API: unexpected error", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC);
    }
}