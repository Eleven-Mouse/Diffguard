package com.diffguard.model;

/**
 * 差异文件条目，用于存储单个差异文件的相关信息
 */
public class DiffFileEntry {

    /** 文件路径 */
    private final String filePath;
    /** 文件内容 */
    private final String content;
    /** 文件行数 */
    private final int lineCount;
    /** Token 数量 */
    private final int tokenCount;

    /**
     * 构造差异文件条目
     *
     * @param filePath   文件路径
     * @param content    文件内容
     * @param tokenCount Token 数量
     */
    public DiffFileEntry(String filePath, String content, int tokenCount) {
        this.filePath = filePath;
        this.content = content;
        this.tokenCount = tokenCount;
        this.lineCount = content.split("\n").length;
    }

    /**
     * 获取文件路径
     */
    public String getFilePath() {
        return filePath;
    }

    /**
     * 获取文件内容
     */
    public String getContent() {
        return content;
    }

    /**
     * 获取文件行数
     */
    public int getLineCount() {
        return lineCount;
    }

    /**
     * 获取 Token 数量
     */
    public int getTokenCount() {
        return tokenCount;
    }

    /**
     * 判断 Token 数量是否超过指定限制
     *
     * @param maxTokens 最大 Token 数量
     * @return 是否超过限制
     */
    public boolean exceedsTokenLimit(int maxTokens) {
        return tokenCount > maxTokens;
    }
}
