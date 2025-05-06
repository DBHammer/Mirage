package ecnu.db.analyzer.online.node;

public enum ExecutionNodeType {
    /**
     * filter节点，过滤节点，只能作为叶子节点, 没有filter info是为全表scan
     */
    FILTER,
    /**
     * join 节点，同时具有左右子节点，只能作为非叶子节点
     */
    JOIN,
    /**
     * aggregate 节点，有子节点，只能作为非叶子节点
     */
    AGGREGATE
}
