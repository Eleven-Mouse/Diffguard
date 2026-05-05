package com.diffguard.exception;

/**
 * DiffGuard 基础异常类，所有业务异常均继承此类。
 */
public class DiffGuardException extends Exception {

    public DiffGuardException(String message) {
        super(message);
    }

    public DiffGuardException(String message, Throwable cause) {
        super(message, cause);
    }
}
