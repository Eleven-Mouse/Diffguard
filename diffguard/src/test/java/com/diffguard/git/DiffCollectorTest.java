package com.diffguard.git;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class DiffCollectorTest {

    @ParameterizedTest(name = "pattern=''{1}'' matches path=''{0}''")
    @DisplayName("matchGlob - 标准匹配")
    @CsvSource({
            "src/Main.java, **/*.java, true",
            "Main.java, *.java, true",
            "Main.java, *.java, true",
            "src/com/example/Main.java, **/*.java, true",
            "target/classes/App.class, **/target/**, true",
            "target/maven-status/compile.txt, **/target/**, true",
            "src/Main.java, **/target/**, false",
            "Main.java, *.txt, false",
            "build/output/app.js, build/**, true",
            "src/test1.java, src/test?.java, true",
            "src/testA.java, src/test?.java, true",
            "src/test.java, src/test?.java, false",
            "src/Main.java, *.java, false",
            "src/Main.java, src/*.java, true"
    })
    void matchGlobStandardPatterns(String path, String pattern, boolean expected) {
        assertEquals(expected, invokeMatchGlob(path, pattern),
                () -> pattern + " should " + (expected ? "" : "not ") + "match " + path);
    }

    @ParameterizedTest(name = "pattern=''{0}'' 不崩溃")
    @DisplayName("matchGlob - 正则元字符安全")
    @ValueSource(strings = {
            "src/test(file).java",
            "src/[dev].java",
            "src/{v1}.java",
            "src/file+1.java",
            "src/file^2.java",
            "src/file$3.java",
            "src/a|b.java",
            "src/(group).java"
    })
    void matchGlobRegexCharsNoCrash(String pattern) {
        // 只要不出 PatternSyntaxException 就行
        assertDoesNotThrow(() -> invokeMatchGlob("src/anything.java", pattern));
    }

    @ParameterizedTest(name = "pattern=''{0}''")
    @DisplayName("matchGlob - **/ 前缀匹配")
    @CsvSource({
            "src/Main.java, **/*.java, true",
            "deep/nested/path/File.java, **/*.java, true",
            "File.java, **/*.java, true",
            "build/output/app.js, **/target/**, false"
    })
    void matchGlobDoubleStarSlash(String path, String pattern, boolean expected) {
        assertEquals(expected, invokeMatchGlob(path, pattern));
    }

    /**
     * 通过反射调用 private static matchGlob 方法。
     */
    private boolean invokeMatchGlob(String path, String pattern) {
        try {
            var method = DiffCollector.class.getDeclaredMethod("matchGlob", String.class, String.class);
            method.setAccessible(true);
            return (boolean) method.invoke(null, path, pattern);
        } catch (Exception e) {
            throw new RuntimeException("反射调用 matchGlob 失败", e);
        }
    }
}
