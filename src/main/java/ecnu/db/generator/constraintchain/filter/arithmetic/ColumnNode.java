package ecnu.db.generator.constraintchain.filter.arithmetic;

import com.fasterxml.jackson.annotation.JsonIgnore;
import ecnu.db.schema.ColumnManager;
import ecnu.db.utils.CommonUtils;
import ecnu.db.utils.exception.analyze.IllegalQueryColumnNameException;

import java.util.ArrayList;
import java.util.List;

/**
 * @author wangqingshuai
 */
public class ColumnNode extends ArithmeticNode {
    private String canonicalColumnName;

    public ColumnNode() {
        super(ArithmeticNodeType.COLUMN);
    }

    public String getCanonicalColumnName() {
        return canonicalColumnName;
    }

    public void setCanonicalColumnName(String canonicalColumnName) throws IllegalQueryColumnNameException {
        if (CommonUtils.isNotCanonicalColumnName(canonicalColumnName)) {
            throw new IllegalQueryColumnNameException();
        }
        this.canonicalColumnName = canonicalColumnName;
    }

    @Override
    public double[] calculate() {
        return ColumnManager.getInstance().calculate(canonicalColumnName);
    }

    @JsonIgnore
    @Override
    public boolean isDifferentTable(String tableName) {
        return !canonicalColumnName.contains(tableName);
    }

    @Override
    @JsonIgnore
    public List<String> getColumns() {
        return new ArrayList<>(List.of(canonicalColumnName));
    }

    @Override
    public String toString() {
        return canonicalColumnName;
    }
}
