package ecnu.db.generator.constraintchain;

/**
 * @author wangqingshuai
 */
public abstract class ConstraintChainNode {
    protected ConstraintChainNodeType constraintChainNodeType;

    protected ConstraintChainNode(ConstraintChainNodeType constraintChainNodeType) {
        this.constraintChainNodeType = constraintChainNodeType;
    }

    public ConstraintChainNodeType getConstraintChainNodeType() {
        return constraintChainNodeType;
    }

    public void setConstraintChainNodeType(ConstraintChainNodeType constraintChainNodeType) {
        this.constraintChainNodeType = constraintChainNodeType;
    }
}
