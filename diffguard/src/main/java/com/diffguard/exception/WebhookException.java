package com.diffguard.exception;

/**
 * Webhook 处理过程中的异常。
 */
public class WebhookException extends DiffGuardException {

    public WebhookException(String message, Throwable cause) {
        super(message, cause);
    }
}
