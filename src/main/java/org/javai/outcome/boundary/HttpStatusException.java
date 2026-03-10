package org.javai.outcome.boundary;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * A checked exception indicating an HTTP response with a non-successful status code.
 *
 * <p>Carries the HTTP status code and an optional {@code Retry-After} hint parsed
 * from the response header. This exception is designed to be thrown from within a
 * {@link Boundary#call} lambda so that the {@link BoundaryFailureClassifier} can
 * classify it into an appropriate {@link org.javai.outcome.Failure} with retry information.
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * Outcome<String> result = boundary.call("Api.fetch", () -> {
 *     HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
 *     HttpResponses.requireSuccess(response);
 *     return response.body();
 * });
 * }</pre>
 *
 * @see HttpResponses#requireSuccess
 * @see BoundaryFailureClassifier
 */
public class HttpStatusException extends Exception {

    private final int statusCode;
    private final Duration retryAfter;

    /**
     * Creates an HttpStatusException with the given status code and no retry hint.
     *
     * @param statusCode the HTTP status code
     * @param message a human-readable description
     */
    public HttpStatusException(int statusCode, String message) {
        this(statusCode, message, null);
    }

    /**
     * Creates an HttpStatusException with the given status code and retry hint.
     *
     * @param statusCode the HTTP status code
     * @param message a human-readable description
     * @param retryAfter the parsed Retry-After duration, or null if not present
     */
    public HttpStatusException(int statusCode, String message, Duration retryAfter) {
        super(message);
        this.statusCode = statusCode;
        this.retryAfter = retryAfter;
    }

    /**
     * Returns the HTTP status code.
     */
    public int statusCode() {
        return statusCode;
    }

    /**
     * Returns the parsed Retry-After duration, or null if the header was not present.
     */
    public Duration retryAfter() {
        return retryAfter;
    }

    /**
     * Parses a {@code Retry-After} header value into a {@link Duration}.
     *
     * <p>Supports both formats defined by HTTP/1.1:
     * <ul>
     *   <li>Seconds: {@code Retry-After: 120} → {@code Duration.ofSeconds(120)}</li>
     *   <li>HTTP-date: {@code Retry-After: Fri, 31 Dec 1999 23:59:59 GMT} → duration from now</li>
     * </ul>
     *
     * @param headerValue the Retry-After header value
     * @return the parsed duration, or null if the value cannot be parsed
     */
    static Duration parseRetryAfter(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }

        String trimmed = headerValue.trim();

        // Try as seconds first (most common)
        try {
            long seconds = Long.parseLong(trimmed);
            if (seconds >= 0) {
                return Duration.ofSeconds(seconds);
            }
            return null;
        } catch (NumberFormatException ignored) {
            // Not a number — try as HTTP-date
        }

        // Try as HTTP-date (RFC 7231 format)
        try {
            ZonedDateTime retryAt = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME);
            Duration delay = Duration.between(ZonedDateTime.now(), retryAt);
            return delay.isNegative() ? Duration.ZERO : delay;
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
