package com.diffguard.infrastructure.common;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 全局共享 ObjectMapper 单例。
 * ObjectMapper 是线程安全的重量级对象，应全局复用以节省内存和提高性能。
 */
public final class JacksonMapper {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JacksonMapper() {}
}
