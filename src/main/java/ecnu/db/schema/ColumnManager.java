package ecnu.db.schema;

import com.fasterxml.jackson.core.type.TypeReference;
import ecnu.db.constraintchain.filter.Parameter;
import ecnu.db.constraintchain.filter.operation.CompareOperator;
import ecnu.db.utils.CommonUtils;
import ecnu.db.utils.exception.TouchstoneException;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static ecnu.db.utils.CommonUtils.CANONICAL_NAME_SPLIT_REGEX;
import static java.nio.charset.StandardCharsets.UTF_8;

public class ColumnManager {
    private static final ColumnManager INSTANCE = new ColumnManager();
    private LinkedHashMap<String, Column> columns = new LinkedHashMap<>();


    private File distributionInfoPath;

    // Private constructor suppresses
    // default public constructor
    private ColumnManager() {
    }

    public static ColumnManager getInstance() {
        return INSTANCE;
    }

    public void setSpecialValue(String columnName, int specialValue) {
        getColumn(columnName).setSpecialValue(specialValue);
    }

    public void insertUniVarProbability(String columnName, BigDecimal probability, CompareOperator operator, List<Parameter> parameters) {
        getColumn(columnName).insertUniVarProbability(probability, operator, parameters);
    }

    public void setResultDir(String resultDir) {
        this.distributionInfoPath = new File(resultDir + CommonUtils.COLUMN_MANAGE_INFO);
    }

    private Column getColumn(String columnName) {
        return columns.get(columnName);
    }

    public boolean[] evaluate(String columnName, CompareOperator operator, List<Parameter> parameters, boolean hasNot) {
        return columns.get(columnName).evaluate(operator, parameters, hasNot);
    }

    public float getNullPercentage(String columnName) {
        return columns.get(columnName).getNullPercentage();
    }

    public double[] calculate(String columnName) {
        return getColumn(columnName).calculate();
    }

    public ColumnType getColumnType(String columnName) {
        return columns.get(columnName).getColumnType();
    }

    public int getNdv(String columnName) {
        return getColumn(columnName).getNdv();
    }

    public void addColumn(String columnName, Column column) throws TouchstoneException {
        if (columnName.split(CANONICAL_NAME_SPLIT_REGEX).length != 3) {
            throw new TouchstoneException("非canonicalColumnName格式");
        }
        columns.put(columnName, column);
    }

    public void removeColumn(String columnName) throws TouchstoneException {
        columns.remove(columnName);
    }

    public void storeColumnDistribution() throws IOException {
        String content = CommonUtils.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(columns);
        FileUtils.writeStringToFile(distributionInfoPath, content, UTF_8);
    }


    public void loadColumnDistribution() throws IOException {
        columns = CommonUtils.MAPPER.readValue(FileUtils.readFileToString(distributionInfoPath, UTF_8),
                new TypeReference<LinkedHashMap<String, Column>>() {
                });
    }

    public void prepareGenerationAll(Set<String> tableNames, int size) {
        tableNames.stream().parallel().forEach(tableName -> columns.get(tableName).prepareTupleData(size));
    }

    public void initAllEqParameter() {
        columns.values().parallelStream().forEach(Column::initEqParameter);
    }

    /**
     * 提取col的range信息(最大值，最小值)
     *
     * @param canonicalColumnNames 需要设置的col
     * @param sqlResult            有关的SQL结果(由AbstractDbConnector.getDataRange返回)
     * @throws TouchstoneException 设置失败
     */
    public void setDataRangeBySqlResult(List<String> canonicalColumnNames, String[] sqlResult) throws TouchstoneException {
        int index = 0;
        for (String canonicalColumnName : canonicalColumnNames) {
            Column column = columns.get(canonicalColumnName);
            switch (column.getColumnType()) {
                case INTEGER:
                    column.setMin(Long.parseLong(sqlResult[index++]));
                    long maxBound = Long.parseLong(sqlResult[index++]);
                    column.setRange(Long.parseLong(sqlResult[index++]));
                    column.setSpecialValue((int) ((maxBound - column.getMin()) / column.getRange()));
                    break;
                case VARCHAR:
                    StringTemplate stringTemplate = new StringTemplate();
                    stringTemplate.minLength = Integer.parseInt(sqlResult[index++]);
                    stringTemplate.rangeLength = Integer.parseInt(sqlResult[index++]) - stringTemplate.minLength;
                    column.setStringTemplate(stringTemplate);
                    column.setMin(0);
                    column.setRange(Integer.parseInt(sqlResult[index++]));
                    column.setSpecialValue(ThreadLocalRandom.current().nextInt());
                    break;
                case DECIMAL:
                    int precision = CommonUtils.SampleDoublePrecision;
                    column.setMin((long) (Double.parseDouble(sqlResult[index++]) * precision));
                    column.setRange((long) (Double.parseDouble(sqlResult[index++]) * precision) - column.getMin());
                    column.setSpecialValue(precision);
                    break;
                case DATE:
                case DATETIME:
                    column.setMin(CommonUtils.getUnixTimeStamp(sqlResult[index++]));
                    column.setRange(CommonUtils.getUnixTimeStamp(sqlResult[index++]) - column.getMin());
                    break;
                case BOOL:
                default:
                    throw new TouchstoneException("未匹配到的类型");
            }
            column.setNullPercentage(Float.parseFloat(sqlResult[index++]));
        }
    }

    public void prepareGeneration(List<String> columnNames, int size) {
        columnNames.stream().parallel().forEach(columnName -> getColumn(columnName).prepareTupleData(size));
    }

//    public List<String> getData(String columnName) {
//        Column column = getColumn(columnName);
//        switch (column.getColumnType()) {
//            case DATE:
//            case DATETIME:
//                return Arrays.stream(((DateTimeColumn) column).getTupleData())
//                        .parallel()
//                        .map((d) -> String.format("'%s'", DateTimeColumn.FMT.format(d)))
//                        .collect(Collectors.toList());
//            case INTEGER:
//                return Arrays.stream(((IntColumn) column).getTupleData())
//                        .parallel()
//                        .mapToObj(Integer::toString)
//                        .collect(Collectors.toList());
//            case DECIMAL:
//                return Arrays.stream(((DecimalColumn) column).getTupleData())
//                        .parallel()
//                        .mapToObj((d) -> BigDecimal.valueOf(d).toString())
//                        .collect(Collectors.toList());
//            case VARCHAR:
//                return Arrays.stream(((StringColumn) column).getTupleData())
//                        .parallel()
//                        .map((d) -> String.format("'%s'", d))
//                        .collect(Collectors.toList());
//            case BOOL:
//            default:
//                throw new UnsupportedOperationException();
//        }
//    }

    public void insertBetweenProbability(String columnName, BigDecimal probability,
                                         CompareOperator lessOperator, List<Parameter> lessParameters,
                                         CompareOperator greaterOperator, List<Parameter> greaterParameters) {
        columns.get(columnName).insertBetweenProbability(probability, lessOperator, lessParameters, greaterOperator, greaterParameters);
    }
}