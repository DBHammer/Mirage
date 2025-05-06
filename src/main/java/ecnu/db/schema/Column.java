package ecnu.db.schema;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import ecnu.db.generator.constraintchain.filter.Parameter;
import ecnu.db.generator.constraintchain.filter.operation.CompareOperator;
import ecnu.db.utils.CommonUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;

import static ecnu.db.utils.CommonUtils.DECIMAL_DIVIDE_SCALE;

/**
 * @author wangqingshuai
 */
@JsonPropertyOrder({"columnType", "nullPercentage", "specialValue", "min", "range", "minLength", "rangeLength", "originalType"})
public class Column {
    private ColumnType columnType;
    private long min;
    private String originalType;
    private long range = 1;
    private long specialValue;
    private BigDecimal nullPercentage = BigDecimal.ZERO;
    private int avgLength;
    private int maxLength;
    @JsonIgnore
    private BigDecimal decimalPre;
    @JsonIgnore
    private StringTemplate stringTemplate;
    @JsonIgnore
    private long[] columnData;
    @JsonIgnore
    private Distribution distribution;

    public Distribution getDistribution() {
        return distribution;
    }

    public Column() {
    }

    public Column(ColumnType columnType) {
        this.columnType = columnType;
    }

    public String getOriginalType() {
        return originalType;
    }

    public void setOriginalType(String originalType) {
        this.originalType = originalType;
    }


    public void init() {
        distribution = new Distribution(nullPercentage, range);
        if (columnType == ColumnType.VARCHAR) {
            stringTemplate = new StringTemplate(avgLength, maxLength, specialValue, range + 20);
        }
    }

    public ColumnType getColumnType() {
        return columnType;
    }

    public long getRange() {
        return range;
    }

    public void setRange(long range) {
        this.range = range;
    }

    public BigDecimal getNullPercentage() {
        return nullPercentage;
    }

    public void setNullPercentage(BigDecimal nullPercentage) {
        this.nullPercentage = nullPercentage;
    }


    public void prepareTupleData(int size) {
        columnData = distribution.prepareTupleData(size);
    }


    /**
     * 无运算比较，针对传入的参数，对于单操作符进行比较
     *
     * @param operator   运算操作符
     * @param parameters 待比较的参数
     * @return 运算结果
     */
    public boolean[] evaluate(CompareOperator operator, List<Parameter> parameters) {
        long value;
        if (operator == CompareOperator.ISNULL) {
            value = Long.MIN_VALUE;
        } else {
            value = parameters.get(0).getData();
        }
        boolean[] ret = new boolean[columnData.length];
        switch (operator) {
            case ISNULL -> {
                for (int i = 0; i < columnData.length; i++) {
                    ret[i] = columnData[i] == Long.MIN_VALUE;
                }
            }
            case IS_NOT_NULL -> {
                for (int i = 0; i < columnData.length; i++) {
                    ret[i] = columnData[i] != Long.MIN_VALUE;
                }
            }
            case EQ, LIKE -> {
                for (int i = 0; i < columnData.length; i++) {
                    ret[i] = columnData[i] != Long.MIN_VALUE && columnData[i] == value;
                }
            }
            case NE, NOT_LIKE -> {
                for (int i = 0; i < columnData.length; i++) {
                    ret[i] = columnData[i] != Long.MIN_VALUE && columnData[i] != value;
                }
            }
            case LT -> {
                for (int i = 0; i < columnData.length; i++) {
                    ret[i] = columnData[i] != Long.MIN_VALUE && columnData[i] < value;
                }
            }
            case LE -> {
                for (int i = 0; i < columnData.length; i++) {
                    ret[i] = columnData[i] != Long.MIN_VALUE && columnData[i] <= value;
                }
            }
            case GT -> {
                for (int i = 0; i < columnData.length; i++) {
                    ret[i] = columnData[i] != Long.MIN_VALUE && columnData[i] > value;
                }
            }
            case GE -> {
                for (int i = 0; i < columnData.length; i++) {
                    ret[i] = columnData[i] != Long.MIN_VALUE && columnData[i] >= value;
                }
            }
            case IN -> {
                HashSet<Long> parameterData = new HashSet<>();
                for (Parameter parameter : parameters) {
                    parameterData.add(parameter.getData());
                }
                for (int i = 0; i < columnData.length; i++) {
                    ret[i] = columnData[i] != Long.MIN_VALUE && parameterData.contains(columnData[i]);
                }
            }
            case NOT_IN -> {
                HashSet<Long> parameterData = new HashSet<>();
                for (Parameter parameter : parameters) {
                    parameterData.add(parameter.getData());
                }
                for (int i = 0; i < columnData.length; i++) {
                    ret[i] = columnData[i] != Long.MIN_VALUE && !parameterData.contains(columnData[i]);
                }
            }
            default -> throw new UnsupportedOperationException();
        }
        return ret;
    }

    /**
     * @return 返回用于multi-var计算的一个double数组
     */
    public double[] calculate() {
        //lazy生成computeData
        double[] ret = new double[columnData.length];
        switch (columnType) {
            case DATE, DATETIME -> {
                for (int i = 0; i < columnData.length; i++) {
                    ret[i] = (columnData[i] + min);
                }
            }
            case DECIMAL -> {
                for (int i = 0; i < columnData.length; i++) {
                    ret[i] = ((double) (columnData[i] + min)) / specialValue;
                }
            }
            case INTEGER -> {
                for (int i = 0; i < columnData.length; i++) {
                    ret[i] = (double) (specialValue * columnData[i]) + min;
                }
            }
            default -> throw new IllegalStateException("Unexpected value: " + columnType);
        }
        return ret;
    }

    public int getAvgLength() {
        return avgLength;
    }

    public void setAvgLength(int avgLength) {
        this.avgLength = avgLength;
    }

    public int getMaxLength() {
        return maxLength;
    }

    public void setMaxLength(int maxLength) {
        this.maxLength = maxLength;
    }

    public String transferDataToValue(long data) {
        if (data == Long.MIN_VALUE) {
            return "\\N";
        }
        return switch (columnType) {
            case INTEGER -> Long.toString((specialValue * data) + min);
            case DECIMAL -> BigDecimal.valueOf(data + min).multiply(decimalPre).toString();
            case VARCHAR -> stringTemplate.getParameterValue(data);
            case DATE -> CommonUtils.dateFormatter.format(Instant.ofEpochSecond((data + min) * 24 * 60 * 60));
            case DATETIME -> CommonUtils.dateTimeFormatter.format(Instant.ofEpochSecond(data + min));
            default -> throw new UnsupportedOperationException();
        };
    }

    public void addSubStringIndex(long dataId) {
        stringTemplate.addSubStringIndex(dataId);
    }

    public void setColumnData(long[] columnData) {
        this.columnData = columnData;
    }

    public String output(int index) {
        return transferDataToValue(columnData[index]);
    }

    public long getMin() {
        return min;
    }

    public void setMin(long min) {
        this.min = min;
    }

    public long getSpecialValue() {
        return specialValue;
    }

    public void setSpecialValue(long specialValue) {
        if (columnType == ColumnType.DECIMAL) {
            decimalPre = BigDecimal.ONE.divide(BigDecimal.valueOf(specialValue), DECIMAL_DIVIDE_SCALE, RoundingMode.HALF_UP);
        }
        this.specialValue = specialValue;
    }


    public StringTemplate getStringTemplate() {
        return stringTemplate;
    }

}
