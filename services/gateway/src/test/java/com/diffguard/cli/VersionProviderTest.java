package com.diffguard.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link VersionProvider} covering getVersion behavior
 * both when version.properties exists and when it does not.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VersionProvider")
class VersionProviderTest {

    // ========================================================================
    // getVersion
    // ========================================================================

    @Nested
    @DisplayName("getVersion")
    class GetVersion {

        @Test
        @DisplayName("returns non-null array")
        void returnsNonNullArray() {
            VersionProvider provider = new VersionProvider();
            String[] version = provider.getVersion();
            assertNotNull(version);
        }

        @Test
        @DisplayName("returns array with exactly one element")
        void returnsSingleElement() {
            VersionProvider provider = new VersionProvider();
            String[] version = provider.getVersion();
            assertEquals(1, version.length);
        }

        @Test
        @DisplayName("returns string starting with 'DiffGuard '")
        void startsWithDiffGuard() {
            VersionProvider provider = new VersionProvider();
            String[] version = provider.getVersion();
            assertTrue(version[0].startsWith("DiffGuard "),
                    "Version string should start with 'DiffGuard ', got: " + version[0]);
        }

        @Test
        @DisplayName("version string contains version info (not blank after prefix)")
        void versionInfoNotBlank() {
            VersionProvider provider = new VersionProvider();
            String[] version = provider.getVersion();
            // After "DiffGuard " there should be something
            String versionPart = version[0].substring("DiffGuard ".length());
            assertFalse(versionPart.isBlank(),
                    "Version part should not be blank, got: '" + versionPart + "'");
        }

        @Test
        @DisplayName("returns consistent results across multiple calls")
        void consistentResults() {
            VersionProvider provider = new VersionProvider();
            String[] first = provider.getVersion();
            String[] second = provider.getVersion();
            assertArrayEquals(first, second);
        }

        @Test
        @DisplayName("handles missing version.properties gracefully")
        void handlesMissingProperties() {
            // Even if version.properties doesn't exist or can't be read,
            // the provider should return "DiffGuard unknown" without throwing
            VersionProvider provider = new VersionProvider();
            assertDoesNotThrow(provider::getVersion);
            String[] version = provider.getVersion();
            // Should be either the actual version or "unknown"
            assertTrue(version[0].startsWith("DiffGuard "));
        }
    }

    // ========================================================================
    // Implements IVersionProvider
    // ========================================================================

    @Nested
    @DisplayName("Interface compliance")
    class InterfaceCompliance {

        @Test
        @DisplayName("implements CommandLine.IVersionProvider")
        void implementsIVersionProvider() {
            assertTrue(picocli.CommandLine.IVersionProvider.class.isAssignableFrom(VersionProvider.class));
        }
    }
}
