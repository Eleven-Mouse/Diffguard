package com.diffguard.adapter.toolserver.model;

/**
 * Diff file entry used by tool server and diff collection.
 */
public class DiffFileEntry {

    private final String filePath;
    private final String content;
    private final int lineCount;
    private final int tokenCount;

    public DiffFileEntry(String filePath, String content, int tokenCount) {
        this.filePath = filePath;
        this.content = content;
        this.tokenCount = tokenCount;
        this.lineCount = content.isEmpty() ? 0 : countLines(content);
    }

    public String getFilePath() { return filePath; }
    public String getContent() { return content; }
    public int getLineCount() { return lineCount; }
    public int getTokenCount() { return tokenCount; }
    public boolean exceedsTokenLimit(int maxTokens) { return tokenCount > maxTokens; }

    private static int countLines(String text) {
        int count = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') count++;
        }
        return count;
    }
}
