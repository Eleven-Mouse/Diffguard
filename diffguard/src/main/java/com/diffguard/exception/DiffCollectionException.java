package com.diffguard.exception;

/**
 * Git 差异采集异常。
 */
public class DiffCollectionException extends DiffGuardException {

    public DiffCollectionException(String message) {
        super(message);
    }

    public DiffCollectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
