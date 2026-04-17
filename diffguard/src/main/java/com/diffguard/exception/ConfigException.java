package com.diffguard.exception;

/**
 * 配置加载或解析异常。
 * 携带配置文件路径和解析错误字段名等上下文信息。
 */
public class ConfigException extends DiffGuardException {

    private final String configPath;
    private final String field;

    public ConfigException(String message) {
        super(message);
        this.configPath = null;
        this.field = null;
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
        this.configPath = null;
        this.field = null;
    }

    public ConfigException(String message, String configPath, Throwable cause) {
        super(message, cause);
        this.configPath = configPath;
        this.field = null;
    }

    public ConfigException(String message, String configPath, String field, Throwable cause) {
        super(message, cause);
        this.configPath = configPath;
        this.field = field;
    }

    public String getConfigPath() {
        return configPath;
    }

    public String getField() {
        return field;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ConfigException");
        if (configPath != null) {
            sb.append(" [").append(configPath).append("]");
        }
        if (field != null) {
            sb.append(" field=").append(field);
        }
        sb.append(": ").append(getMessage());
        return sb.toString();
    }
}
