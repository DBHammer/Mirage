package ecnu.db.generator.constraintchain.filter.operation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import ecnu.db.generator.constraintchain.filter.BoolExprType;
import ecnu.db.generator.constraintchain.filter.Parameter;
import ecnu.db.schema.ColumnManager;
import ecnu.db.schema.TableManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static ecnu.db.generator.constraintchain.filter.operation.CompareOperator.*;


/**
 * @author wangqingshuai
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class UniVarFilterOperation extends AbstractFilterOperation {
    protected String canonicalColumnName;

    public UniVarFilterOperation() {
        super(null);
    }

    public UniVarFilterOperation(String canonicalColumnName, CompareOperator operator, List<Parameter> parameters) {
        super(operator);
        this.canonicalColumnName = canonicalColumnName;
        this.parameters = parameters;
    }

    public void amendParameters() {
        if (operator == GE || operator == LT) {
            for (Parameter parameter : parameters) {
                parameter.setData(parameter.getData() + 1);
                parameter.setDataValue(ColumnManager.getInstance().getColumn(canonicalColumnName).transferDataToValue(parameter.getData()));
            }
        }
        for (Parameter parameter : parameters) {
            if (parameter.isSubString()) {
                ColumnManager.getInstance().getColumn(canonicalColumnName).addSubStringIndex(parameter.getData());
            }
            String value = ColumnManager.getInstance().getColumn(canonicalColumnName).transferDataToValue(parameter.getData());
            parameter.setDataValue(value);
        }
    }

    @Override
    public boolean hasKeyColumn() {
        return TableManager.getInstance().isPrimaryKey(canonicalColumnName) || TableManager.getInstance().isForeignKey(canonicalColumnName);
    }

    @Override
    public void getColumn2ParameterBucket(Map<String, Map<String, List<Integer>>> column2Value2ParameterList) {
        if (operator != IN && operator != EQ) {
            return;
        }
        for (Parameter parameter : parameters) {
            String dataValue = parameter.getDataValue();
            if (column2Value2ParameterList.containsKey(canonicalColumnName)) {
                Map<String, List<Integer>> dataValue2ID = column2Value2ParameterList.get(canonicalColumnName);
                if (dataValue2ID.containsKey(dataValue)) {
                    List<Integer> idList = dataValue2ID.get(dataValue);
                    idList.add(parameter.getId());
                } else {
                    List<Integer> idList = new ArrayList<>();
                    idList.add(parameter.getId());
                    dataValue2ID.put(dataValue, idList);
                }
            } else {
                Map<String, List<Integer>> dataValue2ID = new HashMap<>();
                List<Integer> idList = new ArrayList<>();
                idList.add(parameter.getId());
                dataValue2ID.put(dataValue, idList);
                column2Value2ParameterList.put(canonicalColumnName, dataValue2ID);
            }
        }
    }

    @Override
    public BoolExprType getType() {
        return BoolExprType.UNI_FILTER_OPERATION;
    }

    public String getCanonicalColumnName() {
        return canonicalColumnName;
    }

    public void setCanonicalColumnName(String canonicalColumnName) {
        this.canonicalColumnName = canonicalColumnName;
    }

    @Override
    public String toString() {
        String parametersSQL;
        if (parameters.size() == 1) {
            parametersSQL = "'" + parameters.get(0).getDataValue() + "'";
        } else {
            parametersSQL = "('" + parameters.stream().map(Parameter::getDataValue).collect(Collectors.joining("','")) + "')";
        }
        return canonicalColumnName.split("\\.")[2] + CompareOperator.toSQL(operator) + parametersSQL;
    }

    /**
     * 初始化等值filter的参数
     */
    public void applyConstraint() {
        ColumnManager.getInstance().applyUniVarConstraint(canonicalColumnName, probability, operator, parameters);
    }

    @Override
    public boolean[] evaluate() {
        return ColumnManager.getInstance().evaluate(canonicalColumnName, operator, parameters);
    }

    @Override
    public List<String> getColumns() {
        return new ArrayList<>(List.of(canonicalColumnName));
    }

    @JsonIgnore
    @Override
    public boolean isDifferentTable(String tableName) {
        return !canonicalColumnName.contains(tableName);
    }

    @Override
    public BigDecimal getNullProbability() {
        return ColumnManager.getInstance().getNullPercentage(canonicalColumnName);
    }
}
