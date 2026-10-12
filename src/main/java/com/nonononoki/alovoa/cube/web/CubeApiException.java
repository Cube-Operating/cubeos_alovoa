package com.nonononoki.alovoa.cube.web;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * CubeOS: an error with a user-facing message, rendered as {@code {"error": "..."}} with the given
 * 4xx/5xx status by {@link CubeExceptionHandler}.
 */
@Getter
public class CubeApiException extends RuntimeException {

    private final HttpStatus status;
    /** Optional machine-readable code, rendered as {@code "code"} next to {@code "error"}. */
    private final String code;

    public CubeApiException(HttpStatus status, String userMessage) {
        this(status, userMessage, null);
    }

    public CubeApiException(HttpStatus status, String userMessage, String code) {
        super(userMessage);
        this.status = status;
        this.code = code;
    }

    public static CubeApiException badRequest(String msg) {
        return new CubeApiException(HttpStatus.BAD_REQUEST, msg);
    }

    public static CubeApiException notFound(String msg) {
        return new CubeApiException(HttpStatus.NOT_FOUND, msg);
    }

    public static CubeApiException conflict(String msg) {
        return new CubeApiException(HttpStatus.CONFLICT, msg);
    }

    public static CubeApiException profileGone() {
        return notFound("This profile is no longer available.");
    }

    public static CubeApiException notOnboarded() {
        return new CubeApiException(HttpStatus.FORBIDDEN, "Finish setting up your dating profile first.", "not_onboarded");
    }

    /** The requester is hidden (visible=false, "browse privately") and so may not like anyone. */
    public static CubeApiException notVisible() {
        return new CubeApiException(HttpStatus.FORBIDDEN,
                "Turn on \"See and be seen\" in Settings to like people", "not_visible");
    }

    /** Turning visibility on before onboarding. */
    public static CubeApiException visibilityNeedsProfile() {
        return new CubeApiException(HttpStatus.CONFLICT, "Set up your dating profile first", "not_onboarded");
    }
}