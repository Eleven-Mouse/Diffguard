
package com.diffguard.exception;

/**
 * LLM API 调用异常。
 */
public class LlmApiException extends DiffGuardException {

    private final int statusCode;

    public LlmApiException(String message) {
        super(message);
        this.statusCode = -1;
    }

    public LlmApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
    }

    public LlmApiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public boolean isRateLimitError() {
        return statusCode == 429;
    }

    /** 5xx 服务端错误属于临时性错误，可以安全重试 */
    public boolean isServerError() {
        return statusCode >= 500 && statusCode < 600;
    }

    /** 是否为可重试的临时性错误（限流 + 服务端错误） */
    public boolean isRetryable() {
        return isRateLimitError() || isServerError();
    }
}
