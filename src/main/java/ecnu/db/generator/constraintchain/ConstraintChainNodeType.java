package ecnu.db.generator.constraintchain;

/**
 * @author wangqingshuai
 * 约束链节点的类型
 */

public enum ConstraintChainNodeType {
    /**
     * 过滤节点
     */
    FILTER,
    /**
     * join操作中的主键节点
     */
    PK_JOIN,
    /**
     * join操作中的外键节点
     */
    FK_JOIN,
    /**
     * aggregate操作的节点
     */
    AGGREGATE
}
