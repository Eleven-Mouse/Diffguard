package com.diffguard.orchestrator.dto;

public class ApiErrorResponse {
    public boolean success = false;
    public String code;
    public String message;
    public String trace_id;
    public long timestamp;

    public static ApiErrorResponse of(String code, String message, String traceId) {
        ApiErrorResponse resp = new ApiErrorResponse();
        resp.code = code;
        resp.message = message;
        resp.trace_id = traceId;
        resp.timestamp = System.currentTimeMillis();
        return resp;
    }
}

