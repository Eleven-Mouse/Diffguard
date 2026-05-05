package com.diffguard.domain.ast.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AST 分析结果容器，包含从单个 Java 文件提取的所有结构化信息。
 */
public class ASTAnalysisResult {

    private final String filePath;
    private final String contentHash;
    private final List<MethodInfo> methods;
    private final List<CallEdge> callEdges;
    private final List<ClassInfo> classes;
    private final List<ControlFlowNode> controlFlowNodes;
    private final List<String> imports;
    private final List<ResolvedCallEdge> resolvedCallEdges;
    private final List<FieldAccessInfo> fieldAccesses;
    private final List<DataFlowNode> dataFlowNodes;
    private final boolean parseSucceeded;
    private final String parseError;

    private ASTAnalysisResult(Builder builder) {
        this.filePath = builder.filePath;
        this.contentHash = builder.contentHash;
        this.methods = Collections.unmodifiableList(new ArrayList<>(builder.methods));
        this.callEdges = Collections.unmodifiableList(new ArrayList<>(builder.callEdges));
        this.classes = Collections.unmodifiableList(new ArrayList<>(builder.classes));
        this.controlFlowNodes = Collections.unmodifiableList(new ArrayList<>(builder.controlFlowNodes));
        this.imports = Collections.unmodifiableList(new ArrayList<>(builder.imports));
        this.resolvedCallEdges = Collections.unmodifiableList(new ArrayList<>(builder.resolvedCallEdges));
        this.fieldAccesses = Collections.unmodifiableList(new ArrayList<>(builder.fieldAccesses));
        this.dataFlowNodes = Collections.unmodifiableList(new ArrayList<>(builder.dataFlowNodes));
        this.parseSucceeded = builder.parseSucceeded;
        this.parseError = builder.parseError;
    }

    public static ASTAnalysisResult failure(String filePath, String contentHash, String error) {
        return new Builder(filePath, contentHash).parseSucceeded(false).parseError(error).build();
    }

    public String getFilePath() { return filePath; }
    public String getContentHash() { return contentHash; }
    public List<MethodInfo> getMethods() { return methods; }
    public List<CallEdge> getCallEdges() { return callEdges; }
    public List<ClassInfo> getClasses() { return classes; }
    public List<ControlFlowNode> getControlFlowNodes() { return controlFlowNodes; }
    public List<String> getImports() { return imports; }
    public List<ResolvedCallEdge> getResolvedCallEdges() { return resolvedCallEdges; }
    public List<FieldAccessInfo> getFieldAccesses() { return fieldAccesses; }
    public List<DataFlowNode> getDataFlowNodes() { return dataFlowNodes; }
    public boolean isParseSucceeded() { return parseSucceeded; }
    public String getParseError() { return parseError; }

    public static class Builder {
        private final String filePath;
        private final String contentHash;
        private final List<MethodInfo> methods = new ArrayList<>();
        private final List<CallEdge> callEdges = new ArrayList<>();
        private final List<ClassInfo> classes = new ArrayList<>();
        private final List<ControlFlowNode> controlFlowNodes = new ArrayList<>();
        private final List<String> imports = new ArrayList<>();
        private final List<ResolvedCallEdge> resolvedCallEdges = new ArrayList<>();
        private final List<FieldAccessInfo> fieldAccesses = new ArrayList<>();
        private final List<DataFlowNode> dataFlowNodes = new ArrayList<>();
        private boolean parseSucceeded = true;
        private String parseError;

        public Builder(String filePath, String contentHash) {
            this.filePath = filePath;
            this.contentHash = contentHash;
        }

        public Builder method(MethodInfo method) { methods.add(method); return this; }
        public Builder callEdge(CallEdge edge) { callEdges.add(edge); return this; }
        public Builder classInfo(ClassInfo info) { classes.add(info); return this; }
        public Builder controlFlowNode(ControlFlowNode node) { controlFlowNodes.add(node); return this; }
        public Builder imports(List<String> imports) { this.imports.addAll(imports); return this; }
        public Builder resolvedCallEdge(ResolvedCallEdge edge) { resolvedCallEdges.add(edge); return this; }
        public Builder fieldAccess(FieldAccessInfo access) { fieldAccesses.add(access); return this; }
        public Builder dataFlowNode(DataFlowNode node) { dataFlowNodes.add(node); return this; }
        public Builder parseSucceeded(boolean succeeded) { this.parseSucceeded = succeeded; return this; }
        public Builder parseError(String error) { this.parseError = error; return this; }

        public ASTAnalysisResult build() { return new ASTAnalysisResult(this); }
    }
}
