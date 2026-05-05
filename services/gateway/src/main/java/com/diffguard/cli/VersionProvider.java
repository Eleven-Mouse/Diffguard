package com.diffguard.cli;

import picocli.CommandLine;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 从 version.properties 读取版本号（由 Maven 资源过滤注入）。
 */
public class VersionProvider implements CommandLine.IVersionProvider {
    @Override
    public String[] getVersion() {
        try (InputStream is = getClass().getResourceAsStream("/version.properties")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                return new String[]{"DiffGuard " + props.getProperty("version", "unknown")};
            }
        } catch (IOException ignored) {}
        return new String[]{"DiffGuard unknown"};
    }
}
