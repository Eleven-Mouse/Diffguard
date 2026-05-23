package com.diffguard.exception;

/**
 * Raised when diff collection from git fails.
 */
public class DiffCollectionException extends DiffGuardException {

    public DiffCollectionException(String message) {
        super(message);
    }

    public DiffCollectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
