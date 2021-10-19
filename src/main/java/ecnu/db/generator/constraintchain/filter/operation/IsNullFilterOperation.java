package ecnu.db.generator.constraintchain.filter.operation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import ecnu.db.analyzer.online.adapter.pg.parser.PgSelectSymbol;
import ecnu.db.generator.constraintchain.filter.BoolExprNode;
import ecnu.db.generator.constraintchain.filter.BoolExprType;
import ecnu.db.schema.ColumnManager;
import ecnu.db.utils.CommonUtils;
import ecnu.db.utils.exception.analyze.IllegalQueryColumnNameException;

import java.math.BigDecimal;
import java.util.List;

/**
 * @author alan
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class IsNullFilterOperation extends AbstractFilterOperation {
    private String canonicalColumnName;

    public IsNullFilterOperation() {
        super(CompareOperator.ISNULL);
    }

    public IsNullFilterOperation(String canonicalColumnName) throws IllegalQueryColumnNameException {
        super(CompareOperator.ISNULL);
        if(!CommonUtils.isCanonicalColumnName(canonicalColumnName)){
            throw new IllegalQueryColumnNameException();
        }
        this.canonicalColumnName = canonicalColumnName;
    }

    @Override
    public BigDecimal getProbability() {
        throw new UnsupportedOperationException();
    }

    public void setCanonicalColumnName(String canonicalColumnName) {
        this.canonicalColumnName = canonicalColumnName;
    }

    @Override
    public List<AbstractFilterOperation> pushDownProbability(BigDecimal probability) {
        throw new UnsupportedOperationException();
    }

    @Override
    public BoolExprType getType() {
        return BoolExprType.ISNULL_FILTER_OPERATION;
    }

    @Override
    public String toString() {
        return switch (operator) {
            case ISNULL -> String.format("isnull(%s)", this.canonicalColumnName);
            case IS_NOT_NULL -> String.format("not_isnull(%s)", this.canonicalColumnName);
            default -> throw new UnsupportedOperationException();
        };
    }

    public String getColumnName() {
        return canonicalColumnName;
    }

    public void setColumnName(String columnName) {
        this.canonicalColumnName = columnName;
    }

    @Override
    public boolean[] evaluate() {
        return ColumnManager.getInstance().evaluate(canonicalColumnName, CompareOperator.ISNULL, null);
    }

    @Override
    public List<BoolExprNode> initProbability() {
        return null;
    }

    @Override
    public void setType(BoolExprType type) {

    }

}
