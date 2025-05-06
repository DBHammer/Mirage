package ecnu.db.generator.constraintchain.filter;

import com.fasterxml.jackson.annotation.JsonIgnore;
import ecnu.db.generator.constraintchain.filter.operation.AbstractFilterOperation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * @author wangqingshuai
 */
public abstract class BoolExprNode {

    /**
     * 是否在化简的过程中被reverse过，默认为false
     */
    @JsonIgnore
    protected boolean isReverse = false;

    @JsonIgnore
    public abstract boolean hasKeyColumn();

    /**
     * 计算所有子节点的概率
     *
     * @param probability 当前节点的总概率
     */
    protected abstract List<AbstractFilterOperation> pushDownProbability(BigDecimal probability);

    public abstract void getColumn2ParameterBucket(Map<String, Map<String, List<Integer>>> column2Value2ParameterList);

    /**
     * 获得当前布尔表达式节点的类型
     *
     * @return 类型
     */
    public abstract BoolExprType getType();

    /**
     * 获取生成好column以后，evaluate表达式的布尔值
     *
     * @return evaluate表达式的布尔值
     */
    protected abstract boolean[] evaluate();

    /**
     * 获取该filter条件中的所有参数
     *
     * @return 所有的参数
     */
    public abstract List<Parameter> getParameters();

    /**
     * 对逻辑表达树取反
     */
    public abstract void reverse();

    /**
     * 获取该filter条件中的所有列名
     *
     * @return 所有的参数
     */
    @JsonIgnore
    public abstract List<String> getColumns();

    /**
     * 获得改operation的最大null概率
     *
     * @return null的概率
     */
    @JsonIgnore
    public abstract BigDecimal getNullProbability();

    /**
     * 获得改operation的过滤比
     *
     * @return 过滤比
     */
    @JsonIgnore
    public abstract BigDecimal getFilterProbability();

    /**
     * 判定子树是否可以标记为True
     *
     * @return 子树可以标记为True
     */
    @JsonIgnore
    public abstract boolean isTrue();



    /**
     * 判定子树是否包含其他的表
     *
     * @param tableName 表名
     * @return 是否含有其他的表
     */
    @JsonIgnore
    public abstract boolean isDifferentTable(String tableName);
}
