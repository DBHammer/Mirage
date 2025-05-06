package ecnu.db.generator.constraintchain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import ecnu.db.generator.constraintchain.agg.ConstraintChainAggregateNode;
import ecnu.db.generator.constraintchain.filter.ConstraintChainFilterNode;
import ecnu.db.generator.constraintchain.filter.Parameter;
import ecnu.db.generator.constraintchain.join.ConstraintChainFkJoinNode;
import ecnu.db.generator.constraintchain.join.ConstraintChainPkJoinNode;

import java.util.*;

/**
 * @author wangqingshuai
 */
public class ConstraintChain {

    private final List<ConstraintChainNode> nodes = new ArrayList<>();

    @JsonIgnore
    private final Set<String> joinTables = new HashSet<>();
    private String tableName;

    @JsonIgnore
    private int chainIndex;

    public ConstraintChain() {
    }

    public ConstraintChain(String tableName) {
        this.tableName = tableName;
    }

    public void addJoinTable(String tableName) {
        joinTables.add(tableName);
    }

    public Set<String> getJoinTables() {
        return joinTables;
    }

    public void addNode(ConstraintChainNode node) {
        nodes.add(node);
    }

    public List<ConstraintChainNode> getNodes() {
        return nodes;
    }

    private boolean involvedNode(ConstraintChainNode node, List<String> fkCols) {
        boolean involvedFk = node instanceof ConstraintChainFkJoinNode fkNode && fkCols.contains(fkNode.getLocalCols());
        // todo 处理复合的groupby key
        boolean involvedAgg = node instanceof ConstraintChainAggregateNode aggNode && aggNode.getGroupKey() != null && fkCols.contains(aggNode.getGroupKey().get(0));
        return involvedFk || involvedAgg;
    }

    public List<ConstraintChainNode> getInvolvedNodes(List<String> fkCols) {
        return nodes.stream().filter(node -> involvedNode(node, fkCols)).toList();
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    @JsonIgnore
    public List<Parameter> getParameters() {
        return nodes.stream().filter(ConstraintChainFilterNode.class::isInstance)
                .map(ConstraintChainFilterNode.class::cast)
                .map(ConstraintChainFilterNode::getParameters)
                .flatMap(Collection::stream).toList();
    }

    @Override
    public String toString() {
        return "{tableName:" + tableName + ",nodes:" + nodes + "}";
    }

    /**
     * 给定range空间 计算filter的状态
     *
     * @param range 批大小
     * @return filter状态
     */
    public boolean[] evaluateFilterStatus(int range) {
        if (nodes.get(0) instanceof ConstraintChainFilterNode filterNode) {
            return filterNode.evaluate();
        } else {
            boolean[] result = new boolean[range];
            Arrays.fill(result, true);
            return result;
        }
    }

    public boolean hasFkNode() {
        return nodes.stream().anyMatch(node -> node.getConstraintChainNodeType() == ConstraintChainNodeType.FK_JOIN);
    }

    public boolean hasAggNode(){
        return nodes.stream().anyMatch(node -> node.getConstraintChainNodeType() == ConstraintChainNodeType.AGGREGATE);
    }

    @JsonIgnore
    public List<ConstraintChainFkJoinNode> getFkNodes() {
        return nodes.stream().filter(constraintChainNode ->
                        constraintChainNode.getConstraintChainNodeType() == ConstraintChainNodeType.FK_JOIN)
                .map(ConstraintChainFkJoinNode.class::cast).toList();
    }

    public StringBuilder presentConstraintChains(Map<String, SubGraph> subGraphHashMap, String color) {
        String lastNodeInfo = "";
        double lastProbability = 0;
        String conditionColor = String.format("[style=filled, color=\"%s\"];%n", color);
        String tableColor = String.format("[shape=box,style=filled, color=\"%s\"];%n", color);
        StringBuilder graph = new StringBuilder();
        for (ConstraintChainNode node : nodes) {
            String currentNodeInfo;
            double currentProbability = 0;
            switch (node.constraintChainNodeType) {
                case FILTER -> {
                    currentNodeInfo = String.format("\"%s\"", node);
                    currentProbability = ((ConstraintChainFilterNode) node).getProbability().doubleValue();
                    graph.append("\t").append(currentNodeInfo).append(conditionColor);
                }
                case FK_JOIN -> {
                    ConstraintChainFkJoinNode fkJoinNode = ((ConstraintChainFkJoinNode) node);
                    String pkCols = fkJoinNode.getRefCols().split("\\.")[2];
                    currentNodeInfo = String.format("\"Fk%s%d\"", pkCols, fkJoinNode.getPkTag());
                    String subGraphTag = String.format("cluster%s%d", pkCols, fkJoinNode.getPkTag());
                    currentProbability = fkJoinNode.getProbability().doubleValue();
                    subGraphHashMap.putIfAbsent(subGraphTag, new SubGraph(subGraphTag));
                    subGraphHashMap.get(subGraphTag).fkInfo = currentNodeInfo + conditionColor;
                    subGraphHashMap.get(subGraphTag).joinLabel = switch (fkJoinNode.getType()) {
                        case INNER_JOIN -> "eq join";
                        case SEMI_JOIN -> "semi join: " + fkJoinNode.getPkDistinctProbability();
                        case OUTER_JOIN -> "outer join: " + fkJoinNode.getPkDistinctProbability();
                        case ANTI_SEMI_JOIN -> "anti semi join";
                        case ANTI_JOIN -> "anti join";
                    };
                    if (fkJoinNode.getProbabilityWithFailFilter() != null) {
                        subGraphHashMap.get(subGraphTag).joinLabel = String.format("%s filterWithCannotJoin: %2$,.4f",
                                subGraphHashMap.get(subGraphTag).joinLabel,
                                fkJoinNode.getProbabilityWithFailFilter());
                    }
                }
                case PK_JOIN -> {
                    ConstraintChainPkJoinNode pkJoinNode = ((ConstraintChainPkJoinNode) node);
                    String locPks = pkJoinNode.getPkColumns()[0];
                    currentNodeInfo = String.format("\"Pk%s%d\"", locPks, pkJoinNode.getPkTag());
                    String localSubGraph = String.format("cluster%s%d", locPks, pkJoinNode.getPkTag());
                    subGraphHashMap.putIfAbsent(localSubGraph, new SubGraph(localSubGraph));
                    subGraphHashMap.get(localSubGraph).pkInfo = currentNodeInfo + conditionColor;
                }
                case AGGREGATE -> {
                    ConstraintChainAggregateNode aggregateNode = ((ConstraintChainAggregateNode) node);
                    List<String> keys = aggregateNode.getGroupKey();
                    currentProbability = aggregateNode.getAggProbability().doubleValue();
                    currentNodeInfo = String.format("\"GroupKey:%s\"", keys == null ? "" : String.join(",", keys));
                    graph.append("\t").append(currentNodeInfo).append(conditionColor);
                    if (aggregateNode.getAggFilter() != null) {
                        if (!lastNodeInfo.isBlank()) {
                            graph.append(String.format("\t%s->%s[label=\"%3$,.4f\"];%n", lastNodeInfo, currentNodeInfo, lastProbability));
                        } else {
                            graph.append(String.format("\t\"%s\"%s", tableName, tableColor));
                            graph.append(String.format("\t\"%s\"->%s[label=\"1.0\"]%n", tableName, currentNodeInfo));
                        }
                        lastNodeInfo = currentNodeInfo;
                        lastProbability = currentProbability;
                        ConstraintChainFilterNode aggFilter = aggregateNode.getAggFilter();
                        currentNodeInfo = String.format("\"%s\"", aggFilter);
                        graph.append("\t").append(currentNodeInfo).append(conditionColor);
                        currentProbability = aggFilter.getProbability().doubleValue();
                    }
                }
                default -> throw new UnsupportedOperationException();
            }
            if (!lastNodeInfo.isBlank()) {
                graph.append(String.format("\t%s->%s[label=\"%3$,.4f\"];%n", lastNodeInfo, currentNodeInfo, lastProbability));
            } else {
                graph.append(String.format("\t\"%s\"%s", tableName, tableColor));
                graph.append(String.format("\t\"%s\"->%s[label=\"1.0\"]%n", tableName, currentNodeInfo));
            }
            lastNodeInfo = currentNodeInfo;
            lastProbability = currentProbability;
        }
        if (!lastNodeInfo.startsWith("\"Pk")) {
            graph.append("\t").append("RESULT").append(conditionColor);
            graph.append(String.format("\t%s->RESULT[label=\"%2$,.4f\"]%n", lastNodeInfo, lastProbability));
        }
        return graph;
    }


    public void setChainIndex(int chainIndex) {
        this.chainIndex = chainIndex;
    }

    public int getChainIndex() {
        return chainIndex;
    }

    static class SubGraph {
        private final String joinTag;
        String pkInfo;
        String fkInfo;
        String joinLabel;

        public SubGraph(String joinTag) {
            this.joinTag = joinTag;
        }

        @Override
        public String toString() {
            return String.format("""
                    subgraph "%s" {
                            %s
                            %slabel="%s";labelloc=b;
                    }""".indent(4), joinTag, pkInfo, fkInfo, joinLabel);
        }
    }
}
