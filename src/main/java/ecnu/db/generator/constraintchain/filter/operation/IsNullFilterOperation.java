package ecnu.db.generator.constraintchain.filter.operation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import ecnu.db.generator.constraintchain.filter.BoolExprType;
import ecnu.db.schema.ColumnManager;
import ecnu.db.schema.TableManager;
import ecnu.db.utils.CommonUtils;
import ecnu.db.utils.exception.analyze.IllegalQueryColumnNameException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        if (CommonUtils.isNotCanonicalColumnName(canonicalColumnName)) {
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
    public boolean hasKeyColumn() {
        return TableManager.getInstance().isPrimaryKey(canonicalColumnName) ||
                TableManager.getInstance().isForeignKey(canonicalColumnName);
    }

    @Override
    public List<AbstractFilterOperation> pushDownProbability(BigDecimal probability) {
        throw new UnsupportedOperationException();
    }

    @Override
    public BigDecimal getNullProbability() {
        return ColumnManager.getInstance().getNullPercentage(canonicalColumnName);
    }

    @Override
    public void getColumn2ParameterBucket(Map<String, Map<String, List<Integer>>> column2Value2ParameterList) {
        throw new UnsupportedOperationException();
    }

    @Override
    public BoolExprType getType() {
        return BoolExprType.ISNULL_FILTER_OPERATION;
    }

    @Override
    public String toString() {
        return canonicalColumnName.split("\\.")[2] + CompareOperator.toSQL(operator);
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
    @JsonIgnore
    public List<String> getColumns() {
        return new ArrayList<>(List.of(canonicalColumnName));
    }

    @JsonIgnore
    @Override
    public boolean isDifferentTable(String tableName) {
        return !canonicalColumnName.contains(tableName);
    }
}
