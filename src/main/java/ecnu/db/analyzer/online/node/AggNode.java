package ecnu.db.analyzer.online.node;

public class AggNode extends ExecutionNode {
    /**
     * 如果aggregate中含有filter，则记录经过filter之后的行数
     */
    private FilterNode aggFilter;

    public AggNode(String id, int outputRows, String info) {
        super(id, ExecutionNodeType.AGGREGATE, outputRows, info);
    }

    public FilterNode getAggFilter() {
        return aggFilter;
    }

    public void setAggFilter(FilterNode aggFilter) {
        this.aggFilter = aggFilter;
    }
}
