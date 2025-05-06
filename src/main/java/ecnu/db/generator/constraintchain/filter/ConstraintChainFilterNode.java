package ecnu.db.generator.constraintchain.filter;

import com.fasterxml.jackson.annotation.JsonIgnore;
import ecnu.db.generator.constraintchain.ConstraintChainNode;
import ecnu.db.generator.constraintchain.ConstraintChainNodeType;
import ecnu.db.generator.constraintchain.filter.operation.AbstractFilterOperation;

import java.math.BigDecimal;
import java.util.List;

/**
 * @author wangqingshuai
 */
public class ConstraintChainFilterNode extends ConstraintChainNode {
    private LogicNode root;
    private BigDecimal probability;

    public ConstraintChainFilterNode() {
        super(ConstraintChainNodeType.FILTER);
    }

    public ConstraintChainFilterNode(BigDecimal probability, LogicNode root) {
        super(ConstraintChainNodeType.FILTER);
        this.probability = probability.stripTrailingZeros();
        this.root = root;
    }

    @JsonIgnore
    public List<String> getColumns() {
        return root.getColumns();
    }

    @JsonIgnore
    public boolean hasKeyColumn() {
        return root.hasKeyColumn();
    }

    public List<AbstractFilterOperation> pushDownProbability() {
        List<AbstractFilterOperation> operations = root.pushDownProbability(probability);
        // 处理GT和GE算子的NULL问题
        BigDecimal maxNullProbability = root.getNullProbability();
        for (AbstractFilterOperation operation : operations) {
            // 选在所有概率有效的GT和GE operation
            if (operation.getProbability().compareTo(BigDecimal.ZERO) > 0 &&
                    operation.getProbability().compareTo(BigDecimal.ONE) < 0 && operation.getOperator().isBigger()) {
                BigDecimal selfNull = operation.getNullProbability();
                if (maxNullProbability.compareTo(selfNull) > 0) {
                    operation.setProbability(operation.getProbability().add(maxNullProbability).subtract(selfNull));
                }
            }
        }
        return operations;
    }

    @JsonIgnore
    public List<Parameter> getParameters() {
        return root.getParameters();
    }

    public LogicNode getRoot() {
        return root;
    }

    public void setRoot(LogicNode root) {
        this.root = root;
    }

    public BigDecimal getProbability() {
        return probability;
    }

    public void setProbability(BigDecimal probability) {
        this.probability = probability.stripTrailingZeros();
    }

    @Override
    public String toString() {
        return root.toString();
    }

    public boolean[] evaluate() {
        return root.evaluate();
    }
}
