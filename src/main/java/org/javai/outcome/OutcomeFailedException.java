package org.javai.outcome;

/**
 * Thrown when {@link Outcome#getOrThrow()} is called on a failed outcome.
 * This is an unchecked exception because it indicates misuse of the API—
 * the caller should have checked {@link Outcome#isFail()} first or used pattern matching.
 */
public class OutcomeFailedException extends RuntimeException {

    private final Failure failure;

    public OutcomeFailedException(Failure failure) {
        super("Outcome failed: " + failure.message());
        this.failure = failure;
    }

    public Failure failure() {
        return failure;
    }
}
