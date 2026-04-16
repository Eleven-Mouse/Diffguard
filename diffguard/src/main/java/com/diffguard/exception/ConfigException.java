package com.diffguard.exception;

/**
 * 配置加载或解析异常。
 */
public class ConfigException extends DiffGuardException {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
