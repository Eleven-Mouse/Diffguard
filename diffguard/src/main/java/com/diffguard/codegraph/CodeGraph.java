package com.diffguard.codegraph;

import java.util.*;

/**
 * 代码知识图谱。
 * <p>
 * 存储节点（文件/类/方法/接口）和边（调用/实现/继承/导入/包含），
 * 提供图查询能力：查找调用方、被调用方、依赖路径、影响范围等。
 * <p>
 * 线程安全：构建完成后只读查询，构建阶段非线程安全。
 */
public class CodeGraph {

    private final Map<String, GraphNode> nodes = new LinkedHashMap<>();
    private final List<GraphEdge> edges = new ArrayList<>();

    // 索引：加速查询
    private final Map<String, List<GraphEdge>> outgoingEdges = new HashMap<>();
    private final Map<String, List<GraphEdge>> incomingEdges = new HashMap<>();
    private final Map<GraphNode.Type, List<GraphNode>> nodesByType = new EnumMap<>(GraphNode.Type.class);

    public void addNode(GraphNode node) {
        nodes.putIfAbsent(node.getId(), node);
        nodesByType.computeIfAbsent(node.getType(), k -> new ArrayList<>()).add(node);
    }

    public void addEdge(GraphEdge edge) {
        edges.add(edge);
        outgoingEdges.computeIfAbsent(edge.getSourceId(), k -> new ArrayList<>()).add(edge);
        incomingEdges.computeIfAbsent(edge.getTargetId(), k -> new ArrayList<>()).add(edge);
    }

    // --- 查询 API ---

    public Optional<GraphNode> getNode(String id) {
        return Optional.ofNullable(nodes.get(id));
    }

    public Collection<GraphNode> getAllNodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    public List<GraphEdge> getAllEdges() {
        return Collections.unmodifiableList(edges);
    }

    public List<GraphNode> getNodesByType(GraphNode.Type type) {
        return Collections.unmodifiableList(nodesByType.getOrDefault(type, List.of()));
    }

    /**
     * 获取指定节点的所有出边。
     */
    public List<GraphEdge> getOutgoingEdges(String nodeId) {
        return Collections.unmodifiableList(outgoingEdges.getOrDefault(nodeId, List.of()));
    }

    /**
     * 获取指定节点的所有入边。
     */
    public List<GraphEdge> getIncomingEdges(String nodeId) {
        return Collections.unmodifiableList(incomingEdges.getOrDefault(nodeId, List.of()));
    }

    /**
     * 获取指定节点的所有出边（按边类型过滤）。
     */
    public List<GraphEdge> getOutgoingEdges(String nodeId, GraphEdge.Type edgeType) {
        return outgoingEdges.getOrDefault(nodeId, List.of()).stream()
                .filter(e -> e.getType() == edgeType)
                .toList();
    }

    /**
     * 获取指定节点的所有入边（按边类型过滤）。
     */
    public List<GraphEdge> getIncomingEdges(String nodeId, GraphEdge.Type edgeType) {
        return incomingEdges.getOrDefault(nodeId, List.of()).stream()
                .filter(e -> e.getType() == edgeType)
                .toList();
    }

    /**
     * 查找谁调用了指定方法（直接调用方）。
     */
    public List<GraphNode> getCallersOf(String methodNodeId) {
        return getIncomingEdges(methodNodeId, GraphEdge.Type.CALLS).stream()
                .map(e -> nodes.get(e.getSourceId()))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 查找指定方法调用了哪些方法（直接被调用方）。
     */
    public List<GraphNode> getCalleesOf(String methodNodeId) {
        return getOutgoingEdges(methodNodeId, GraphEdge.Type.CALLS).stream()
                .map(e -> nodes.get(e.getTargetId()))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 查找指定类的所有实现（接口 → 实现类列表）。
     */
    public List<GraphNode> getImplementationsOf(String interfaceNodeId) {
        return getIncomingEdges(interfaceNodeId, GraphEdge.Type.IMPLEMENTS).stream()
                .map(e -> nodes.get(e.getSourceId()))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 查找指定类的父类。
     */
    public Optional<GraphNode> getParentClass(String classNodeId) {
        return getOutgoingEdges(classNodeId, GraphEdge.Type.EXTENDS).stream()
                .map(e -> nodes.get(e.getTargetId()))
                .filter(Objects::nonNull)
                .findFirst();
    }

    /**
     * 查找指定类的所有子类。
     */
    public List<GraphNode> getSubClasses(String classNodeId) {
        return getIncomingEdges(classNodeId, GraphEdge.Type.EXTENDS).stream()
                .map(e -> nodes.get(e.getSourceId()))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 查找指定文件中包含的所有类。
     */
    public List<GraphNode> getClassesInFile(String fileNodeId) {
        return getOutgoingEdges(fileNodeId, GraphEdge.Type.CONTAINS).stream()
                .filter(e -> {
                    GraphNode target = nodes.get(e.getTargetId());
                    return target != null && (target.getType() == GraphNode.Type.CLASS
                            || target.getType() == GraphNode.Type.INTERFACE);
                })
                .map(e -> nodes.get(e.getTargetId()))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 查找指定类中的所有方法。
     */
    public List<GraphNode> getMethodsInClass(String classNodeId) {
        return getOutgoingEdges(classNodeId, GraphEdge.Type.CONTAINS).stream()
                .map(e -> nodes.get(e.getTargetId()))
                .filter(n -> n != null && n.getType() == GraphNode.Type.METHOD)
                .toList();
    }

    /**
     * 查找指定文件/类/方法的所有直接依赖（被它引用的节点）。
     */
    public Set<GraphNode> getDependencies(String nodeId) {
        Set<GraphNode> deps = new LinkedHashSet<>();
        for (GraphEdge edge : getOutgoingEdges(nodeId)) {
            if (edge.getType() != GraphEdge.Type.CONTAINS) {
                GraphNode target = nodes.get(edge.getTargetId());
                if (target != null) deps.add(target);
            }
        }
        return deps;
    }

    /**
     * 查找指定文件/类/方法的所有直接依赖方（引用它的节点）。
     */
    public Set<GraphNode> getDependents(String nodeId) {
        Set<GraphNode> deps = new LinkedHashSet<>();
        for (GraphEdge edge : getIncomingEdges(nodeId)) {
            if (edge.getType() != GraphEdge.Type.CONTAINS) {
                GraphNode source = nodes.get(edge.getSourceId());
                if (source != null) deps.add(source);
            }
        }
        return deps;
    }

    /**
     * 计算变更影响范围：给定变更的文件/类/方法，找到所有受影响的节点（BFS）。
     *
     * @param changedNodeIds 变更的节点 ID 集合
     * @param maxDepth       最大搜索深度
     * @return 受影响的节点集合（不包含变更节点本身）
     */
    public Set<GraphNode> computeImpactSet(Set<String> changedNodeIds, int maxDepth) {
        Set<GraphNode> impacted = new LinkedHashSet<>();
        Set<String> visited = new HashSet<>(changedNodeIds);
        Deque<String> queue = new ArrayDeque<>(changedNodeIds);
        int depth = 0;

        while (!queue.isEmpty() && depth < maxDepth) {
            int levelSize = queue.size();
            for (int i = 0; i < levelSize; i++) {
                String current = queue.poll();
                for (GraphEdge edge : getIncomingEdges(current)) {
                    if (edge.getType() == GraphEdge.Type.CONTAINS) continue;
                    String sourceId = edge.getSourceId();
                    if (visited.add(sourceId)) {
                        GraphNode source = nodes.get(sourceId);
                        if (source != null) {
                            impacted.add(source);
                            queue.add(sourceId);
                        }
                    }
                }
            }
            depth++;
        }

        return impacted;
    }

    /**
     * 查找两个节点之间的最短路径（BFS）。
     */
    public List<GraphEdge> findShortestPath(String fromId, String toId) {
        if (fromId.equals(toId)) return List.of();

        Map<String, GraphEdge> parentEdge = new HashMap<>();
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(fromId);
        visited.add(fromId);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (GraphEdge edge : getOutgoingEdges(current)) {
                String next = edge.getTargetId();
                if (visited.add(next)) {
                    parentEdge.put(next, edge);
                    if (next.equals(toId)) {
                        return reconstructPath(parentEdge, fromId, toId);
                    }
                    queue.add(next);
                }
            }
        }
        return List.of();
    }

    private List<GraphEdge> reconstructPath(Map<String, GraphEdge> parentEdge,
                                             String fromId, String toId) {
        List<GraphEdge> path = new ArrayList<>();
        String current = toId;
        while (!current.equals(fromId)) {
            GraphEdge edge = parentEdge.get(current);
            if (edge == null) break;
            path.add(edge);
            current = edge.getSourceId();
        }
        Collections.reverse(path);
        return path;
    }

    /**
     * 导出图谱为 Mermaid 图表格式。
     */
    public String toMermaid() {
        StringBuilder sb = new StringBuilder();
        sb.append("graph TD\n");

        for (GraphNode node : nodes.values()) {
            String shape = switch (node.getType()) {
                case FILE -> "([{}])";
                case CLASS -> "[{}]";
                case INTERFACE -> "[[{}]]";
                case METHOD -> "({})";
            };
            String label = shape.replace("{}", node.getName());
            sb.append("  ").append(sanitizeId(node.getId())).append(label).append("\n");
        }

        for (GraphEdge edge : edges) {
            if (edge.getType() == GraphEdge.Type.CONTAINS) continue;
            String label = switch (edge.getType()) {
                case CALLS -> "calls";
                case IMPLEMENTS -> "implements";
                case EXTENDS -> "extends";
                case IMPORTS -> "imports";
                default -> "";
            };
            sb.append("  ").append(sanitizeId(edge.getSourceId()))
                    .append(" -->|").append(label).append("| ")
                    .append(sanitizeId(edge.getTargetId())).append("\n");
        }

        return sb.toString();
    }

    private String sanitizeId(String id) {
        return id.replace(".", "_").replace("/", "_").replace(" ", "_");
    }

    // --- 统计 ---

    public int nodeCount() { return nodes.size(); }
    public int edgeCount() { return edges.size(); }

    public Map<String, Integer> getStatistics() {
        Map<String, Integer> stats = new LinkedHashMap<>();
        stats.put("totalNodes", nodes.size());
        stats.put("totalEdges", edges.size());
        for (GraphNode.Type type : GraphNode.Type.values()) {
            stats.put(type.name().toLowerCase() + "Nodes",
                    nodesByType.getOrDefault(type, List.of()).size());
        }
        for (GraphEdge.Type type : GraphEdge.Type.values()) {
            stats.put(type.name().toLowerCase() + "Edges",
                    (int) edges.stream().filter(e -> e.getType() == type).count());
        }
        return stats;
    }
}
