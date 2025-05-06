package ecnu.db.analyzer.online.node;

public class FilterNode extends ExecutionNode {
    /**
     * 是否为新生成的节点
     */
    public boolean isAdd = false;
    private boolean isIndexScan = false;
    private String filterInfoWithQuote;

    public FilterNode(String id, long outputRows, String info) {
        super(id, ExecutionNodeType.FILTER, outputRows, info);
    }

    public boolean isAdd() {
        return isAdd;
    }

    public void setAdd() {
        isAdd = true;
    }

    public boolean isIndexScan() {
        return isIndexScan;
    }

    public void setIndexScan(boolean indexScan) {
        isIndexScan = indexScan;
    }

    public String getFilterInfoWithQuote() {
        return filterInfoWithQuote;
    }

    public void setFilterInfoWithQuote(String filterInfoWithQuote) {
        this.filterInfoWithQuote = filterInfoWithQuote;
    }
}
