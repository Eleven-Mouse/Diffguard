package com.diffguard.agent.strategy;

import java.util.*;

/**
 * 审查策略：决定使用哪些 Agent、以什么权重和顺序执行。
 */
public class ReviewStrategy {

    public enum AgentType {
        SECURITY, PERFORMANCE, ARCHITECTURE, GENERAL
    }

    private final String name;
    private final String description;
    private final Map<AgentType, Double> agentWeights;
    private final List<String> focusAreas;
    private final List<String> additionalRules;
    private final int priority;

    private ReviewStrategy(Builder builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.agentWeights = Collections.unmodifiableMap(new LinkedHashMap<>(builder.agentWeights));
        this.focusAreas = List.copyOf(builder.focusAreas);
        this.additionalRules = List.copyOf(builder.additionalRules);
        this.priority = builder.priority;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public Map<AgentType, Double> getAgentWeights() { return agentWeights; }
    public List<String> getFocusAreas() { return focusAreas; }
    public List<String> getAdditionalRules() { return additionalRules; }
    public int getPriority() { return priority; }

    /**
     * 获取启用的 Agent 列表（权重 > 0）。
     */
    public List<AgentType> getEnabledAgents() {
        return agentWeights.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<AgentType, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String name;
        private String description;
        private final Map<AgentType, Double> agentWeights = new LinkedHashMap<>();
        private final List<String> focusAreas = new ArrayList<>();
        private final List<String> additionalRules = new ArrayList<>();
        private int priority;

        public Builder name(String v) { name = v; return this; }
        public Builder description(String v) { description = v; return this; }
        public void agentWeight(AgentType agent, double weight) {
            agentWeights.put(agent, weight);
        }
        public void focusArea(String v) { focusAreas.add(v);
        }
        public void additionalRule(String v) { additionalRules.add(v);
        }
        public Builder priority(int v) { priority = v; return this; }
        public ReviewStrategy build() { return new ReviewStrategy(this); }
    }
}
