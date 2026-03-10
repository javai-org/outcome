package org.javai.outcome.boundary;

import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Utility for converting {@link HttpResponse} objects into the Outcome world.
 *
 * <p>Use {@link #requireSuccess(HttpResponse)} inside a {@link Boundary#call} lambda
 * to throw an {@link HttpStatusException} for non-2xx responses. The exception carries
 * the HTTP status code and any {@code Retry-After} hint, which the
 * {@link BoundaryFailureClassifier} translates into a properly classified
 * {@link org.javai.outcome.Failure} with retry information.
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
 * <p>When combined with a {@link org.javai.outcome.retry.Retrier}, HTTP 429 and 503
 * responses with {@code Retry-After} headers are automatically respected by retry policies.</p>
 */
public final class HttpResponses {

    private HttpResponses() {}

    /**
     * Throws {@link HttpStatusException} if the response status code is not in the 2xx range.
     *
     * <p>For responses that include a {@code Retry-After} header (common with 429 and 503),
     * the header is parsed and attached to the exception so that retry policies can respect it.
     *
     * @param response the HTTP response to check
     * @throws HttpStatusException if the status code is not 2xx
     */
    public static void requireSuccess(HttpResponse<?> response) throws HttpStatusException {
        int statusCode = response.statusCode();
        if (statusCode >= 200 && statusCode < 300) {
            return;
        }

        Duration retryAfter = response.headers()
                .firstValue("Retry-After")
                .map(HttpStatusException::parseRetryAfter)
                .orElse(null);

        String message = "HTTP " + statusCode;
        throw new HttpStatusException(statusCode, message, retryAfter);
    }
}
