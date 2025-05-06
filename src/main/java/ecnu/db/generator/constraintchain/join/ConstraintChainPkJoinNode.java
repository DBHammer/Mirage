package ecnu.db.generator.constraintchain.join;

import ecnu.db.generator.constraintchain.ConstraintChainNode;
import ecnu.db.generator.constraintchain.ConstraintChainNodeType;

import java.util.Arrays;

/**
 * @author wangqingshuai
 */
public class ConstraintChainPkJoinNode extends ConstraintChainNode {
    private String[] pkColumns;
    private int pkTag;

    public ConstraintChainPkJoinNode() {
        super(ConstraintChainNodeType.PK_JOIN);
    }

    public ConstraintChainPkJoinNode(int pkTag, String[] pkColumns) {
        super(ConstraintChainNodeType.PK_JOIN);
        this.pkTag = pkTag;
        this.pkColumns = pkColumns;
    }

    @Override
    public String toString() {
        return String.format("{pkTag:%d,pkColumns:%s}", pkTag, Arrays.toString(pkColumns));
    }

    public String[] getPkColumns() {
        return pkColumns;
    }

    public int getPkTag() {
        return pkTag;
    }

    public void setPkTag(int pkTag) {
        this.pkTag = pkTag;
    }
}
