package com.xops.hub.model;

/** Stable X account in the project's author archive. A previously verified local binding takes precedence. */
public final class AuthorIdentity {
    public static final String ARCHIVE_ACCOUNT_ID = "2095392916734976000";

    private AuthorIdentity() {}

    public static String trustedId(String boundAccountId) {
        return boundAccountId == null || boundAccountId.isBlank() ? ARCHIVE_ACCOUNT_ID : boundAccountId;
    }
}
