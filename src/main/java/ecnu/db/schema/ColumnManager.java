package ecnu.db.schema;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.SequenceWriter;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import ecnu.db.LanguageManager;
import ecnu.db.generator.constraintchain.filter.Parameter;
import ecnu.db.generator.constraintchain.filter.operation.CompareOperator;
import ecnu.db.utils.CommonUtils;
import ecnu.db.utils.exception.TouchstoneException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

import static ecnu.db.utils.CommonUtils.*;

public class ColumnManager {
    public static final String COLUMN_STRING_INFO = "/stringTemplate.json";
    public static final String COLUMN_DISTRIBUTION_INFO = "/distribution.json";
    public static final String COLUMN_BOUND_PARA_INFO = "/boundPara.json";
    public static final String COLUMN_METADATA_INFO = "/column.csv";
    private static final ColumnManager INSTANCE = new ColumnManager();
    private static final CsvSchema columnSchema = CSV_MAPPER.schemaFor(Column.class);
    private final LinkedHashMap<String, Column> columns = new LinkedHashMap<>();

    private final List<Column> attributeColumns = new LinkedList<>();

    private File distributionInfoPath;
    private final Logger logger = LoggerFactory.getLogger(ColumnManager.class);
    private final ResourceBundle rb = LanguageManager.getInstance().getRb();

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

    public void applyUniVarConstraint(String columnName, BigDecimal probability, CompareOperator operator, List<Parameter> parameters) {
        try {
            getColumn(columnName).getDistribution().applyUniVarConstraint(probability, operator, parameters);
        } catch (TouchstoneException e) {
            logger.error(columnName, e);

        }
    }

    public void setResultDir(String resultDir) {
        this.distributionInfoPath = new File(resultDir);
    }

    public Column getColumn(String columnName) {
        return columns.get(columnName);
    }

    public void initAllParameters() {
        for (Map.Entry<String, Column> columnName2Column : columns.entrySet()) {
            Distribution distribution = columnName2Column.getValue().getDistribution();
            long appendRow = distribution.initAllParameters();
            if (appendRow > 0) {
                logger.error(rb.getString("cardinalityNotEnough"), columnName2Column.getKey(), appendRow);
            }
        }
    }

    public String[] generateAttRows(int range) {
        String[] result = new String[range];
        IntStream.range(0, range).parallel().forEach(rowId -> {
            String[] buffers = new String[attributeColumns.size()];
            for (int i = 0; i < attributeColumns.size(); i++) {
                buffers[i] = attributeColumns.get(i).output(rowId);
            }
            result[rowId] = String.join(",", buffers);
        });
        return result;
    }

    public long getMin(String columnName) {
        if (!columns.containsKey(columnName)) {
            return 0;
        }
        return columns.get(columnName).getMin();
    }

    public boolean[] evaluate(String columnName, CompareOperator operator, List<Parameter> parameters) {
        return columns.get(columnName).evaluate(operator, parameters);
    }

    public BigDecimal getNullPercentage(String columnName) {
        return columns.get(columnName).getNullPercentage();
    }

    public double[] calculate(String columnName) {
        return getColumn(columnName).calculate();
    }

    public ColumnType getColumnType(String columnName) {
        return columns.get(columnName).getColumnType();
    }

    public boolean isDateColumn(String columnName) {
        return columns.containsKey(columnName) && columns.get(columnName).getColumnType() == ColumnType.DATE;
    }

    public int getNdv(String columnName) {
        return (int) getColumn(columnName).getRange();
    }

    public void addColumn(String columnName, Column column) throws TouchstoneException {
        if (columnName.split(CANONICAL_NAME_SPLIT_REGEX).length != 3) {
            throw new TouchstoneException("非canonicalColumnName格式");
        }
        columns.put(columnName, column);
    }

    public void storeColumnMetaData() throws IOException {
        try (StringWriter writer = new StringWriter()) {
            writer.write("ColumnName");
            for (int i = 0; i < columnSchema.size(); i++) {
                writer.write("," + columnSchema.columnName(i));
            }
            writer.write("\n");
            SequenceWriter seqW = CSV_MAPPER.writerFor(Column.class).with(columnSchema).writeValues(writer);
            for (var column : columns.entrySet()) {
                writer.write(column.getKey() + ",");
                seqW.write(column.getValue());
            }
            CommonUtils.writeFile(distributionInfoPath.getPath() + COLUMN_METADATA_INFO, writer.toString());
        }
    }

    public void storeColumnDistribution() throws IOException {
        File distribution = new File(distributionInfoPath + "/distribution");
        if (!distribution.exists()) {
            distribution.mkdir();
        }
        Map<String, Set<Long>> columName2StringTemplate = new HashMap<>();
        for (Map.Entry<String, Column> column : columns.entrySet()) {
            if (column.getValue().getColumnType() == ColumnType.VARCHAR &&
                    column.getValue().getStringTemplate().getLikeIndex2Status() != null) {
                columName2StringTemplate.put(column.getKey(), column.getValue().getStringTemplate().getLikeIndex2Status());
            }
        }
        String content = CommonUtils.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(columName2StringTemplate);
        CommonUtils.writeFile(distribution.getPath() + COLUMN_STRING_INFO, content);
        Map<String, Map<Long, BigDecimal>> paraData2Probability = new TreeMap<>();
        Map<String, SortedMap<BigDecimal, Long>> offset2Pvs = new TreeMap<>();
        for (Map.Entry<String, Column> column : columns.entrySet()) {
            Distribution columnDistribution = column.getValue().getDistribution();
            if (columnDistribution.hasConstraints()) {
                paraData2Probability.put(column.getKey(), columnDistribution.getParaData2Probability());
            }
            if (!columnDistribution.getOffset2Pv().isEmpty()) {
                offset2Pvs.put(column.getKey(), columnDistribution.getOffset2Pv());
            }
        }
        content = CommonUtils.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(paraData2Probability);
        CommonUtils.writeFile(distribution.getPath() + COLUMN_DISTRIBUTION_INFO, content);
        content = CommonUtils.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(offset2Pvs);
        CommonUtils.writeFile(distribution.getPath() + COLUMN_BOUND_PARA_INFO, content);
    }

    public void loadColumnMetaData() throws IOException {
        columns.clear();
        try (BufferedReader bufferedReader = new BufferedReader(new FileReader(distributionInfoPath.getPath() + COLUMN_METADATA_INFO))) {
            bufferedReader.readLine();
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                int commaIndex = line.indexOf(",");
                String columnData = line.substring(commaIndex + 1);
                Column column = CSV_MAPPER.readerFor(Column.class).with(columnSchema).readValue(columnData);
                column.init();
                columns.put(line.substring(0, commaIndex), column);
            }
        }
    }

    public void loadColumnDistribution() throws IOException {
        File distribution = new File(distributionInfoPath + "/distribution");
        String content = CommonUtils.readFile(distribution.getPath() + COLUMN_STRING_INFO);
        Map<String, TreeSet<Long>> columName2StringTemplate = CommonUtils.MAPPER.readValue(content, new TypeReference<>() {
        });
        for (Map.Entry<String, TreeSet<Long>> template : columName2StringTemplate.entrySet()) {
            columns.get(template.getKey()).getStringTemplate().setLikeIndex2Status(template.getValue());
        }
        content = CommonUtils.readFile(distribution.getPath() + COLUMN_DISTRIBUTION_INFO);
        Map<String, SortedMap<Long, BigDecimal>> paraData2Probability = CommonUtils.MAPPER.readValue(content, new TypeReference<>() {
        });
        for (Map.Entry<String, SortedMap<Long, BigDecimal>> paraData : paraData2Probability.entrySet()) {
            columns.get(paraData.getKey()).getDistribution().setParaData2Probability(paraData.getValue());
        }
        content = CommonUtils.readFile(distribution.getPath() + COLUMN_BOUND_PARA_INFO);
        Map<String, SortedMap<BigDecimal, Long>> boundPv2Offsets = CommonUtils.MAPPER.readValue(content, new TypeReference<>() {
        });
        for (var boundPara : boundPv2Offsets.entrySet()) {
            columns.get(boundPara.getKey()).getDistribution().setOffset2Pv(boundPara.getValue());
        }
    }

    public void storeColumnName2IdList(Map<String, List<List<Integer>>> columnName2IdList) throws IOException {
        String resultDir = distributionInfoPath.getPath();
        String content = CommonUtils.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(columnName2IdList);
        CommonUtils.writeFile(resultDir + "/column2IdList", content);
    }

    public void loadColumnName2IdList() throws IOException {
        String resultDir = distributionInfoPath.getPath();
        String content = CommonUtils.readFile(resultDir + "/column2IdList");
        Map<String, List<List<Integer>>> column2IdList = CommonUtils.MAPPER.readValue(content, new TypeReference<>() {
        });
        for (Map.Entry<String, List<List<Integer>>> stringListEntry : column2IdList.entrySet()) {
            String columnName = stringListEntry.getKey();
            List<List<Integer>> idList = stringListEntry.getValue();
            columns.get(columnName).getDistribution().setIdList(idList);
        }
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
            String minResult = sqlResult[index++];
            String maxResult = sqlResult[index++];
            long min;
            long range;
            long specialValue;
            if (minResult == null) {
                min = -1;
                range = -1;
                specialValue = 0;
                if (column.getColumnType().isHasCardinalityConstraint()) {
                    index++;
                }
            } else {
                switch (column.getColumnType()) {
                    case INTEGER -> {
                        min = Long.parseLong(minResult);
                        long maxBound = Long.parseLong(maxResult);
                        range = Long.parseLong(sqlResult[index++]);
                        specialValue = (int) ((maxBound - min + 1) / range);
                    }
                    case VARCHAR -> {
                        column.setAvgLength((int) Math.round(Double.parseDouble(minResult)));
                        column.setMaxLength(Integer.parseInt(maxResult));
                        min = 0;
                        range = Integer.parseInt(sqlResult[index++]);
                        specialValue = ThreadLocalRandom.current().nextInt();
                    }
                    case DECIMAL -> {
                        specialValue = column.getSpecialValue();
                        min = (long) (Double.parseDouble(minResult) * specialValue);
                        range = (long) (Double.parseDouble(maxResult) * specialValue) - min + 1;
                    }
                    case DATE -> {
                        min = LocalDateTime.parse(minResult, INPUT_FMT).toEpochSecond(ZoneOffset.UTC) / (24 * 60 * 60);
                        range = LocalDateTime.parse(maxResult, INPUT_FMT).toEpochSecond(ZoneOffset.UTC) / (24 * 60 * 60) - min + 1;
                        specialValue = 0;
                    }
                    case DATETIME -> {
                        min = LocalDateTime.parse(minResult, INPUT_FMT).toEpochSecond(ZoneOffset.UTC);
                        range = LocalDateTime.parse(maxResult, INPUT_FMT).toEpochSecond(ZoneOffset.UTC) - min + 1;
                        specialValue = 0;
                    }
                    default -> throw new TouchstoneException("未匹配到的类型");
                }
            }
            column.setMin(min);
            column.setRange(range);
            column.setSpecialValue(specialValue);
            String[] tags = canonicalColumnName.split("\\.");
            String tableName = tags[0] + "." + tags[1];
            BigDecimal tableSize = BigDecimal.valueOf(TableManager.getInstance().getTableSize(tableName));
            column.setNullPercentage(new BigDecimal(sqlResult[index++]).divide(tableSize, DECIMAL_DIVIDE_SCALE, RoundingMode.HALF_UP));
            column.init();
        }
    }

    public void cacheAttributeColumn(Collection<String> columnNames) {
        attributeColumns.clear();
        attributeColumns.addAll(columnNames.stream().map(this::getColumn).toList());
    }

    public void prepareGeneration(int size) {
        attributeColumns.stream().parallel().forEach(column -> column.prepareTupleData(size));
    }
}
