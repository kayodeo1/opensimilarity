package io.opensimilarity.store;

/** Who a document can be matched by. Declared from narrowest to widest. */
public enum IndexPolicy {
    /** Kept so it can itself be checked, but never appears as a source for anyone. */
    NEVER_INDEX,
    /** Source for documents in the same collection. */
    COURSE_ONLY,
    /** Source for documents in any collection of the same department. */
    DEPT_ONLY
}
