package ecnu.db.generator.constraintchain.filter.arithmetic;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * @author wangqingshuai
 */
public abstract class ArithmeticNode {
    protected static int size = -1;
    protected ArithmeticNode leftNode;
    protected ArithmeticNode rightNode;
    protected ArithmeticNodeType type;

    protected ArithmeticNode(ArithmeticNodeType type) {
        this.type = type;
    }

    public static void setSize(int size) {
        ArithmeticNode.size = size;
    }

    public ArithmeticNodeType getType() {
        return this.type;
    }

    public void setType(ArithmeticNodeType type) {
        this.type = type;
    }

    public ArithmeticNode getLeftNode() {
        return leftNode;
    }

    public void setLeftNode(ArithmeticNode leftNode) {
        this.leftNode = leftNode;
    }

    public ArithmeticNode getRightNode() {
        return rightNode;
    }

    public void setRightNode(ArithmeticNode rightNode) {
        this.rightNode = rightNode;
    }

    /**
     * 获取当前节点在column生成好数据以后的计算结果
     *
     * @return 返回double类型的计算结果
     */
    public abstract double[] calculate();

    /**
     * 判定子树是否包含其他的表
     *
     * @param tableName 表名
     * @return 是否含有其他的表
     */
    @JsonIgnore
    public abstract boolean isDifferentTable(String tableName);

    @JsonIgnore
    public abstract List<String> getColumns();
}
