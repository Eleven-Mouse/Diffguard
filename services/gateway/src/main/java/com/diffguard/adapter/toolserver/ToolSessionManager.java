package com.diffguard.adapter.toolserver;

import com.diffguard.domain.agent.core.AgentContext;
import com.diffguard.domain.agent.core.AgentTool;
import com.diffguard.domain.agent.tools.*;
import com.diffguard.domain.review.model.DiffFileEntry;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具服务端会话管理器。
 * <p>
 * 为每次 Python Agent 审查请求创建会话，持有 projectDir、diffEntries、allowedFiles，
 * 供工具端点回调使用。会话 10 分钟自动过期。
 */
public class ToolSessionManager {

    private static final long SESSION_TTL_MS = 10 * 60 * 1000;

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    public static class Session {
        private final String id;
        private final Path projectDir;
        private final List<DiffFileEntry> diffEntries;
        private final Set<String> allowedFiles;
        private final long createdAt;
        private final AgentContext context;
        private final Map<String, AgentTool> tools;

        public Session(String id, Path projectDir, List<DiffFileEntry> diffEntries, Set<String> allowedFiles) {
            this.id = id;
            this.projectDir = projectDir;
            this.diffEntries = List.copyOf(diffEntries);
            this.allowedFiles = Set.copyOf(allowedFiles);
            this.createdAt = System.currentTimeMillis();
            this.context = new AgentContext(projectDir, diffEntries);

            FileAccessSandbox sandbox = new FileAccessSandbox(projectDir, this.allowedFiles);
            this.tools = new LinkedHashMap<>();
            GetCallGraphTool callGraphTool = new GetCallGraphTool(projectDir);
            this.tools.put("get_file_content", new GetFileContentTool(projectDir, sandbox));
            this.tools.put("get_diff_context", new GetDiffContextTool());
            this.tools.put("get_method_definition", new GetMethodDefinitionTool(projectDir, sandbox));
            this.tools.put("get_call_graph", callGraphTool);
            this.tools.put("get_related_files", new GetRelatedFilesTool(callGraphTool));
            this.tools.put("semantic_search", new SemanticSearchTool(projectDir));
        }

        public String getId() { return id; }
        public Path getProjectDir() { return projectDir; }
        public List<DiffFileEntry> getDiffEntries() { return diffEntries; }
        public AgentContext getContext() { return context; }
        public AgentTool getTool(String name) { return tools.get(name); }
        public Collection<AgentTool> getAllTools() { return tools.values(); }

        public boolean isExpired() {
            return System.currentTimeMillis() - createdAt > SESSION_TTL_MS;
        }
    }

    public Session create(String id, Path projectDir, List<DiffFileEntry> diffEntries, Set<String> allowedFiles) {
        cleanup();
        Session session = new Session(id, projectDir, diffEntries, allowedFiles);
        sessions.put(id, session);
        return session;
    }

    public Session get(String id) {
        Session session = sessions.get(id);
        if (session == null || session.isExpired()) {
            sessions.remove(id);
            return null;
        }
        return session;
    }

    public void remove(String id) {
        sessions.remove(id);
    }

    private void cleanup() {
        sessions.entrySet().removeIf(e -> e.getValue().isExpired());
    }
}
