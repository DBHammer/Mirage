package ecnu.db.analyzer.online.node;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

public class JoinNode extends ExecutionNode {
    private final boolean antiJoin;

    private final boolean semiJoin;

    private final CountDownLatch waitSetJoinTag = new CountDownLatch(1);

    public int getJoinTag() {
        return joinTag;
    }

    public void setJoinTag(int joinTag) {
        this.joinTag = joinTag;
        waitSetJoinTag.countDown();
    }

    /**
     * 记录Join的状态
     */
    private int joinStatus = Integer.MIN_VALUE;

    /**
     * 记录主键的join tag，第一次访问该节点后设置join tag，后续的访问可以找到之前对应的join tag
     */
    private int joinTag = Integer.MIN_VALUE;
    private final BigDecimal pkDistinctProbability;
    private long rowsRemoveByFilterAfterJoin;
    private String indexJoinFilter;


    public JoinNode(String id, long outputRows, String info, boolean antiJoin, boolean semiJoin, BigDecimal pkDistinctProbability) {
        super(id, ExecutionNodeType.JOIN, outputRows, info);
        if (info.contains("<>") && info.indexOf("<>") == info.lastIndexOf("<>")) {
            this.setInfo(info.replace("<>", "="));
            antiJoin = !antiJoin;
        }
        this.antiJoin = antiJoin;
        this.semiJoin = semiJoin;
        this.pkDistinctProbability = pkDistinctProbability;
    }

    /**
     * @return 当前表最新的join tag
     */
    public int getJoinStatus() {
        try {
            waitSetJoinTag.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
            Thread.currentThread().interrupt();
        }
        return joinStatus;
    }

    public void setJoinStatus(int joinStatus) {
        this.joinStatus = joinStatus;
        waitSetJoinTag.countDown();
    }

    public boolean isAntiJoin() {
        return antiJoin;
    }

    public BigDecimal getPkDistinctSize() {
        return pkDistinctProbability;
    }

    public String getIndexJoinFilter() {
        return indexJoinFilter;
    }

    public void setIndexJoinFilter(String indexJoinFilter) {
        this.indexJoinFilter = indexJoinFilter;
    }

    public boolean isSemiJoin() {
        return semiJoin;
    }

    public long getRowsRemoveByFilterAfterJoin() {
        return rowsRemoveByFilterAfterJoin;
    }

    public void setRowsRemoveByFilterAfterJoin(long rowsRemoveByFilterAfterJoin) {
        this.rowsRemoveByFilterAfterJoin = rowsRemoveByFilterAfterJoin;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;

        JoinNode joinNode = (JoinNode) o;

        if (antiJoin != joinNode.antiJoin) return false;
        if (semiJoin != joinNode.semiJoin) return false;
        if (joinStatus != joinNode.joinStatus) return false;
        if (rowsRemoveByFilterAfterJoin != joinNode.rowsRemoveByFilterAfterJoin) return false;
        if (!waitSetJoinTag.equals(joinNode.waitSetJoinTag)) return false;
        if (!Objects.equals(pkDistinctProbability, joinNode.pkDistinctProbability))
            return false;
        return Objects.equals(indexJoinFilter, joinNode.indexJoinFilter);
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + (antiJoin ? 1 : 0);
        result = 31 * result + (semiJoin ? 1 : 0);
        result = 31 * result + waitSetJoinTag.hashCode();
        result = 31 * result + joinStatus;
        result = 31 * result + (pkDistinctProbability != null ? pkDistinctProbability.hashCode() : 0);
        result = 31 * result + (int) (rowsRemoveByFilterAfterJoin ^ (rowsRemoveByFilterAfterJoin >>> 32));
        result = 31 * result + (indexJoinFilter != null ? indexJoinFilter.hashCode() : 0);
        return result;
    }
}
