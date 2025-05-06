package ecnu.db.generator.constraintchain.filter.operation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import ecnu.db.generator.constraintchain.filter.BoolExprType;
import ecnu.db.generator.constraintchain.filter.Parameter;
import ecnu.db.generator.constraintchain.filter.arithmetic.ArithmeticNode;
import ecnu.db.generator.constraintchain.filter.arithmetic.ArithmeticNodeType;
import ecnu.db.generator.constraintchain.filter.arithmetic.ColumnNode;
import ecnu.db.schema.ColumnManager;
import ecnu.db.schema.TableManager;
import ecnu.db.utils.CommonUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * @author wangqingshuai
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MultiVarFilterOperation extends AbstractFilterOperation {
    private ArithmeticNode arithmeticTree;

    public MultiVarFilterOperation() {
        super(null);
    }

    public MultiVarFilterOperation(CompareOperator operator, ArithmeticNode arithmeticTree, List<Parameter> parameters) {
        super(operator);
        this.arithmeticTree = arithmeticTree;
        this.parameters = parameters;
    }


    public Set<String> getAllCanonicalColumnNames() {
        HashSet<String> allTables = new HashSet<>();
        getCanonicalColumnNamesColNames(arithmeticTree, allTables);
        return allTables;
    }

    private void getCanonicalColumnNamesColNames(ArithmeticNode node, HashSet<String> colNames) {
        if (node == null) {
            return;
        }
        if (node.getType() == ArithmeticNodeType.COLUMN) {
            colNames.add(((ColumnNode) node).getCanonicalColumnName());
        }
        getCanonicalColumnNamesColNames(node.getLeftNode(), colNames);
        getCanonicalColumnNamesColNames(node.getRightNode(), colNames);
    }

    @Override
    public boolean hasKeyColumn() {
        return hasKeyColumn(arithmeticTree);
    }

    @Override
    public void getColumn2ParameterBucket(Map<String, Map<String, List<Integer>>> column2Value2ParameterList) {
        throw new UnsupportedOperationException();
    }

    private boolean hasKeyColumn(ArithmeticNode node) {
        boolean hasKeyColumn = false;
        if (node != null) {
            hasKeyColumn = hasKeyColumn(node.getLeftNode()) || hasKeyColumn(node.getRightNode());
            if (node.getType() == ArithmeticNodeType.COLUMN) {
                ColumnNode columnNode = (ColumnNode) node;
                hasKeyColumn = hasKeyColumn ||
                        TableManager.getInstance().isPrimaryKey(columnNode.getCanonicalColumnName()) ||
                        TableManager.getInstance().isForeignKey(columnNode.getCanonicalColumnName());
            }
        }
        return hasKeyColumn;
    }

    @Override
    public BoolExprType getType() {
        return BoolExprType.MULTI_FILTER_OPERATION;
    }

    /**
     * todo 暂时不考虑NULL
     *
     * @return 多值表达式的计算结果
     */
    @Override
    public boolean[] evaluate() {
        double[] data = arithmeticTree.calculate();
        boolean[] ret = new boolean[data.length];
        double parameterValue = (double) parameters.getFirst().getData() / CommonUtils.SAMPLE_DOUBLE_PRECISION;
        switch (operator) {
            case LT -> {
                for (int i = 0; i < ret.length; i++) {
                    ret[i] = data[i] < parameterValue;
                }
            }
            case LE -> {
                for (int i = 0; i < ret.length; i++) {
                    ret[i] = data[i] <= parameterValue;
                }
            }
            case GT -> {
                for (int i = 0; i < ret.length; i++) {
                    ret[i] = data[i] > parameterValue;
                }
            }
            case GE -> {
                for (int i = 0; i < ret.length; i++) {
                    ret[i] = data[i] >= parameterValue;
                }
            }
            default -> throw new UnsupportedOperationException();
        }
        return ret;
    }

    @Override
    public List<String> getColumns() {
        return arithmeticTree.getColumns();
    }

    @JsonIgnore
    @Override
    public boolean isDifferentTable(String tableName) {
        return arithmeticTree.isDifferentTable(tableName);
    }

    @Override
    public String toString() {
        return arithmeticTree.toString() + CompareOperator.toSQL(operator) + parameters.get(0).getDataValue();
    }

    public ArithmeticNode getArithmeticTree() {
        return arithmeticTree;
    }

    public void setArithmeticTree(ArithmeticNode arithmeticTree) {
        this.arithmeticTree = arithmeticTree;
    }

    /**
     * todo 暂时不考虑null
     */
    public void instantiateMultiVarParameter() {
        switch (operator) {
            case GE, GT:
                probability = BigDecimal.ONE.subtract(probability);
                break;
            case LE, LT:
                break;
            default:
                throw new UnsupportedOperationException("多变量计算节点仅接受非等值约束");
        }
        double[] vector = arithmeticTree.calculate();
        int pos;
        if (probability.equals(BigDecimal.ONE)) {
            pos = vector.length - 1;
        } else {
            pos = probability.multiply(BigDecimal.valueOf(vector.length)).setScale(0, RoundingMode.HALF_UP).intValue();
        }
        Arrays.sort(vector);
        double postSmallestNumber = vector[pos];
        long internalValue = (long) (postSmallestNumber * CommonUtils.SAMPLE_DOUBLE_PRECISION);
        parameters.forEach(param -> param.setData(internalValue));
        var columns = arithmeticTree.getColumns();
        boolean isDate = ColumnManager.getInstance().isDateColumn(columns.getFirst());
        for (int i = 1; i < columns.size(); i++) {
            if (isDate != ColumnManager.getInstance().isDateColumn(columns.get(i))) {
                throw new UnsupportedOperationException("不支持混合计算date和数值类型");
            }
        }
        if (isDate) {
            parameters.forEach(param -> param.setDataValue("interval '" + internalValue / CommonUtils.SAMPLE_DOUBLE_PRECISION + "' day"));
        } else {
            parameters.forEach(param -> param.setDataValue(" '" + (double) internalValue / CommonUtils.SAMPLE_DOUBLE_PRECISION + "' "));
        }
    }

    @Override
    public BigDecimal getNullProbability() {
        //todo deal with null
        return BigDecimal.ZERO;
    }
}
