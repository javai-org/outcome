package org.javai.outcome;

/**
 * Expresses the application's intent for operator notification.
 */
public enum NotificationIntent {
    /**
     * No notification needed. The failure is handled entirely by the application.
     */
    NONE,

    /**
     * Record for observability (metrics, traces) but no active notification.
     */
    OBSERVE,

    /**
     * Notify operators during business hours. Something needs attention but isn't urgent.
     */
    ALERT,

    /**
     * Wake someone up. Immediate human intervention required.
     */
    PAGE
}
