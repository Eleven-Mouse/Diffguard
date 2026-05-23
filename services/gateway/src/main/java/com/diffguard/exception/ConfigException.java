package com.diffguard.exception;

/**
 * Configuration loading/validation exception.
 */
public class ConfigException extends DiffGuardException {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
