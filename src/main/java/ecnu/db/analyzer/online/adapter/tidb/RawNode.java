package ecnu.db.analyzer.online.adapter.tidb;

/**
 * @author lianxuechao
 */
public class RawNode {
    public final String nodeType;
    public final String operatorInfo;
    public final int rowCount;
    public final String id;
    public RawNode left;
    public RawNode right;

    public RawNode(String id, RawNode left, RawNode right, String nodeType, String operatorInfo, int rowCount) {
        this.id = id;
        this.left = left;
        this.right = right;
        this.nodeType = nodeType;
        this.operatorInfo = operatorInfo;
        this.rowCount = rowCount;
    }

    @Override
    public String toString() {
        return "RawNode{id=" + id + ", operatorInfo:" + operatorInfo + "}";
    }
}
