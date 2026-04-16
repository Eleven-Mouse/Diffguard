
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
}
