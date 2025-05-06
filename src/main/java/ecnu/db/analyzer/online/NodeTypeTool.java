package ecnu.db.analyzer.online;


/**
 * @author lianxuechao
 */
public interface NodeTypeTool {
    /**
     * 是否为reader类型节点
     *
     * @param nodeType 表示节点类型的str
     * @return 返回true或者false
     */
    boolean isReaderNode(String nodeType);

    /**
     * 是否为需要跳过的节点
     *
     * @param nodeType 表示节点类型的str
     * @return 返回true或者false
     */
    boolean isPassNode(String nodeType);

    /**
     * 是否为join类型节点
     *
     * @param nodeType 表示节点类型的str
     * @return 返回true或者false
     */
    boolean isJoinNode(String nodeType);

    /**
     * 是否为filter类型节点
     *
     * @param nodeType 表示节点类型的str
     * @return 返回true或者false
     */
    boolean isFilterNode(String nodeType);

    /**
     * 是否为table scan类型节点
     *
     * @param nodeType 表示节点类型的str
     * @return 返回true或者false
     */
    boolean isTableScanNode(String nodeType);

    /**
     * 是否为index scan类型节点
     *
     * @param nodeType 表示节点类型的str
     * @return 返回true或者false
     */
    boolean isIndexScanNode(String nodeType);

    /**
     * 是否为range scan类型节点
     *
     * @param nodeType 表示节点类型的str
     * @return 返回true或者false
     */
    boolean isRangeScanNode(String nodeType);

    /**
     * 是否为Aggregate类型节点
     *
     * @param nodeType 表示节点类型的str
     * @return 返回true或者false
     */
    boolean isAggregateNode(String nodeType);
}
