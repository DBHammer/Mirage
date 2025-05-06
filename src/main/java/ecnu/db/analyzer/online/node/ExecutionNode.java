package ecnu.db.analyzer.online.node;


import java.util.Objects;

/**
 * @author wangqingshuai
 */
public class ExecutionNode {
    /**
     * 节点类型
     */
    private final ExecutionNodeType type;
    /**
     * 节点额外信息
     * filter node -> filter info
     * join node -> join condition
     */
    private String info;
    /**
     * 节点输出的数据量
     */
    private long outputRows;
    /**
     * 指向右节点
     */
    private ExecutionNode rightNode;
    /**
     * 指向左节点
     */
    private ExecutionNode leftNode;
    /**
     * 表名
     */
    private String tableName;
    /**
     * 对应explain analyze的query plan树的节点名称
     */
    private String id;


    ExecutionNode(String id, ExecutionNodeType type, long outputRows, String info) {
        this.type = type;
        this.info = info;
        this.id = id;
        this.outputRows = outputRows;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public ExecutionNode getRightNode() {
        return rightNode;
    }

    public void setRightNode(ExecutionNode rightNode) {
        this.rightNode = rightNode;
    }

    public ExecutionNode getLeftNode() {
        return leftNode;
    }

    public void setLeftNode(ExecutionNode leftNode) {
        this.leftNode = leftNode;
    }

    public String getInfo() {
        return info;
    }

    public void setInfo(String info) {
        this.info = info;
    }

    public long getOutputRows() {
        return outputRows;
    }

    public void setOutputRows(long outputRows) {
        this.outputRows = outputRows;
    }

    public ExecutionNodeType getType() {
        return type;
    }

    @Override
    public String toString() {
        return String.format("node type:%s; table name:%s; node info:%s", type, tableName, info);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ExecutionNode that = (ExecutionNode) o;
        return outputRows == that.outputRows && type == that.type && Objects.equals(info, that.info) && Objects.equals(rightNode, that.rightNode) && Objects.equals(leftNode, that.leftNode) && Objects.equals(tableName, that.tableName) && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, info, outputRows, rightNode, leftNode, tableName, id);
    }
}
