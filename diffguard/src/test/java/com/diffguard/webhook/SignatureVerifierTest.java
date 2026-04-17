package com.diffguard.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SignatureVerifierTest {

    // ------------------------------------------------------------------
    // 正常验签
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("正常验签")
    class ValidSignature {

        @Test
        @DisplayName("正确的 HMAC-SHA256 签名校验通过")
        void validSignaturePasses() {
            String secret = "my-webhook-secret";
            String payload = "{\"action\":\"opened\",\"number\":1}";
            SignatureVerifier verifier = new SignatureVerifier(secret);
            String signature = computeSignature(secret, payload);
            assertTrue(verifier.verify(payload, "sha256=" + signature));
        }

        @Test
        @DisplayName("篡改 payload 后签名校验失败")
        void tamperedPayloadFails() {
            String secret = "my-webhook-secret";
            String originalPayload = "{\"action\":\"opened\"}";
            String tamperedPayload = "{\"action\":\"closed\"}";

            SignatureVerifier verifier = new SignatureVerifier(secret);
            String signature = computeSignature(secret, originalPayload);
            assertFalse(verifier.verify(tamperedPayload, "sha256=" + signature));
        }

        @Test
        @DisplayName("错误的 secret 导致验签失败")
        void wrongSecretFails() {
            String payload = "{\"action\":\"opened\"}";
            String signature = computeSignature("correct-secret", payload);

            SignatureVerifier verifier = new SignatureVerifier("wrong-secret");
            assertFalse(verifier.verify(payload, "sha256=" + signature));
        }
    }

    // ------------------------------------------------------------------
    // 边界情况
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("边界情况")
    class EdgeCases {

        @Test
        @DisplayName("signature 头为 null → 失败")
        void nullSignatureFails() {
            SignatureVerifier verifier = new SignatureVerifier("secret");
            assertFalse(verifier.verify("payload", null));
        }

        @Test
        @DisplayName("signature 不以 sha256= 开头 → 失败")
        void wrongPrefixFails() {
            SignatureVerifier verifier = new SignatureVerifier("secret");
            assertFalse(verifier.verify("payload", "sha1=abc123"));
        }

        @Test
        @DisplayName("secret 未配置 → 跳过校验，返回 true")
        void noSecretSkipsVerification() {
            SignatureVerifier verifier = new SignatureVerifier(null);
            assertTrue(verifier.verify("payload", null));
        }

        @Test
        @DisplayName("空白 secret → 跳过校验，返回 true")
        void blankSecretSkipsVerification() {
            SignatureVerifier verifier = new SignatureVerifier("   ");
            assertTrue(verifier.verify("payload", null));
        }
    }

    /**
     * 辅助方法：用指定 secret 计算 payload 的 HMAC-SHA256 十六进制值。
     */
    private static String computeSignature(String secret, String payload) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
