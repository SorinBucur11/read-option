package app.readoption.draft;

/**
 * Lifecycle of a {@link DraftSession}. Persisted as the enum name
 * ({@code @Enumerated(EnumType.STRING)}) — never ordinal.
 */
public enum DraftStatus {
    ACTIVE,
    COMPLETE,
    ABANDONED
}
