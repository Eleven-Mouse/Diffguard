package com.diffguard.domain.coderag;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 基于 TF-IDF 的本地向量化实现。
 * <p>
 * 零外部依赖，使用词频 + 逆文档频率构建稀疏向量。
 * 适用于代码检索场景：方法名、类名、变量名、关键字等都有较高的区分度。
 * <p>
 * 向量维度为词表大小（动态扩展），向量经 L2 归一化。
 */
public class LocalTFIDFProvider implements EmbeddingProvider {

    private static final Pattern TOKENIZER = Pattern.compile(
            "[a-zA-Z_][a-zA-Z0-9_]*|\\d+\\.?\\d*");

    // IDF 统计：word -> 出现在多少个文档中
    private final Map<String, Integer> documentFrequency = new HashMap<>();
    private int totalDocuments = 0;

    // 词表：固定维度，取 top-N 最常见的词
    private List<String> vocabulary;
    private final int maxVocabularySize;

    public LocalTFIDFProvider() {
        this(512);
    }

    public LocalTFIDFProvider(int maxVocabularySize) {
        this.maxVocabularySize = maxVocabularySize;
        this.vocabulary = List.of();
    }

    /**
     * 从语料库构建词表和 IDF 统计。必须在 embed() 之前调用。
     */
    public void buildVocabulary(List<String> documents) {
        documentFrequency.clear();
        totalDocuments = documents.size();

        Map<String, Integer> globalFreq = new HashMap<>();

        for (String doc : documents) {
            Set<String> uniqueTokens = new HashSet<>(tokenize(doc));
            for (String token : uniqueTokens) {
                documentFrequency.merge(token, 1, Integer::sum);
            }
            for (String token : tokenize(doc)) {
                globalFreq.merge(token, 1, Integer::sum);
            }
        }

        // 取频率最高的词作为词表（排除过于稀疏或过于常见的词）
        vocabulary = globalFreq.entrySet().stream()
                .filter(e -> e.getValue() >= 2 || totalDocuments < 10)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(maxVocabularySize)
                .map(Map.Entry::getKey)
                .toList();
    }

    @Override
    public int dimension() {
        return vocabulary.size();
    }

    @Override
    public float[] embed(String text) {
        if (vocabulary.isEmpty()) {
            return new float[0];
        }

        float[] vector = new float[vocabulary.size()];
        List<String> tokens = tokenize(text);
        if (tokens.isEmpty()) return vector;

        // 计算 TF
        Map<String, Integer> termFreq = new HashMap<>();
        for (String token : tokens) {
            termFreq.merge(token, 1, Integer::sum);
        }

        // 构建 TF-IDF 向量
        for (int i = 0; i < vocabulary.size(); i++) {
            String word = vocabulary.get(i);
            Integer tf = termFreq.get(word);
            if (tf == null) continue;

            float tfVal = (float) tf / tokens.size();
            float idf = (float) Math.log((totalDocuments + 1.0) /
                    (documentFrequency.getOrDefault(word, 0) + 1.0)) + 1.0f;
            vector[i] = tfVal * idf;
        }

        // L2 归一化
        normalize(vector);
        return vector;
    }

    /**
     * 代码感知的分词：拆分 camelCase、snake_case，提取标识符和关键字。
     */
    List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        // 先拆分 camelCase: processOrder -> process, order
        String split = text.replaceAll("([a-z])([A-Z])", "$1 $2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2");
        // 拆分 snake_case
        split = split.replace('_', ' ');
        // 拆分运算符和标点
        split = split.replaceAll("[{}()\\[\\];,.<>!=+\\-*/&|?:@#~\"']", " ");

        var matcher = TOKENIZER.matcher(split.toLowerCase());
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() > 1) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private void normalize(float[] vector) {
        float norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        if (norm == 0) return;
        norm = (float) Math.sqrt(norm);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= norm;
        }
    }

    List<String> getVocabulary() {
        return vocabulary;
    }
}
