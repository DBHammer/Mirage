package ecnu.db.analyzer;

import ecnu.db.LanguageManager;
import ecnu.db.generator.constraintchain.ConstraintChain;
import ecnu.db.generator.constraintchain.ConstraintChainManager;
import ecnu.db.generator.constraintchain.filter.ConstraintChainFilterNode;
import ecnu.db.generator.constraintchain.filter.Parameter;
import ecnu.db.generator.constraintchain.filter.operation.AbstractFilterOperation;
import ecnu.db.generator.constraintchain.filter.operation.MultiVarFilterOperation;
import ecnu.db.generator.constraintchain.filter.operation.UniVarFilterOperation;
import ecnu.db.schema.ColumnManager;
import ecnu.db.schema.Distribution;
import ecnu.db.utils.CommonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static ecnu.db.utils.CommonUtils.matchPattern;

@CommandLine.Command(name = "instantiate", description = "instantiate the query", mixinStandardHelpOptions = true)
public class QueryInstantiate implements Callable<Integer> {

    private static final Pattern PATTERN = Pattern.compile("'Mirage#(\\d+)'");
    private static final String WORKLOAD_DIR = "/workload";
    private static final String QUERIES = "/queries";
    private final Logger logger = LoggerFactory.getLogger(QueryInstantiate.class);
    private final ResourceBundle rb = LanguageManager.getInstance().getRb();
    @CommandLine.Option(names = {"-c", "--config_path"}, required = true, description = "the config path for instantiating query ")
    private String configPath;
    @CommandLine.Option(names = {"-s", "--sampling_size"}, defaultValue = "4000000", description = "samplingSize")
    private String samplingSize;
    private Map<String, List<ConstraintChain>> query2constraintChains;

    @Override
    public Integer call() throws IOException {
        init();
        Map<String, String> queryName2QueryTemplates = getQueryName2QueryTemplates();
        logger.info(rb.getString("StartInstantiatingTheQueryPlan"));
        List<ConstraintChain> allConstraintChains = query2constraintChains.values().stream().flatMap(Collection::stream).toList();
        Map<Integer, Parameter> id2Parameter = queryInstantiation(allConstraintChains, Integer.parseInt(samplingSize));
        logger.info(rb.getString("TheInstantiatedQueryPlanSucceed"), id2Parameter.values());
        logger.info(rb.getString("StartPersistentQueryPlanWithNewDataDistribution"));
        ConstraintChainManager.getInstance().storeConstraintChain(query2constraintChains);
        ColumnManager.getInstance().storeColumnDistribution();
        logger.info(rb.getString("PersistentQueryPlanCompleted"));
        logger.info(rb.getString("StartPopulatingTheQueryTemplate"));
        writeQuery(queryName2QueryTemplates, id2Parameter);
        logger.info(rb.getString("FillInTheQueryTemplateComplete"));
        if (!id2Parameter.isEmpty()) {
            logger.info(rb.getString("TheParametersThatWereNotSuccessfullyReplaced"), id2Parameter.values());
        }
        return null;
    }

    private static List<List<AbstractFilterOperation>> pushDownProbability(List<ConstraintChain> constraintChains) {
        return constraintChains.stream()
                .map(ConstraintChain::getNodes)
                .flatMap(Collection::stream)
                .filter(ConstraintChainFilterNode.class::isInstance)
                .map(ConstraintChainFilterNode.class::cast)
                .map(ConstraintChainFilterNode::pushDownProbability).toList();
    }

    private static void applyUniVarConstraints(List<AbstractFilterOperation> filterOperations) {
        List<UniVarFilterOperation> uniFilters = filterOperations.stream()
                .filter(UniVarFilterOperation.class::isInstance)
                .sorted(Comparator.comparing(AbstractFilterOperation::getProbability))
                .map(UniVarFilterOperation.class::cast).toList();
        uniFilters.stream()
                .filter(uniFilter -> !uniFilter.getOperator().isEqual())
                .forEach(UniVarFilterOperation::applyConstraint);
        uniFilters.stream()
                .filter(uniFilter -> uniFilter.getOperator().isEqual())
                .filter(uniFilter -> !uniFilter.getOperator().isMultiEqual())
                .forEach(UniVarFilterOperation::applyConstraint);
        uniFilters.stream()
                .filter(uniFilter -> uniFilter.getOperator().isEqual())
                .filter(uniFilter -> uniFilter.getOperator().isMultiEqual())
                .forEach(UniVarFilterOperation::applyConstraint);
        ColumnManager.getInstance().initAllParameters();

        // 修正>=和<的参数边界，对其+1，因为数据生成为左开右闭
        uniFilters.forEach(UniVarFilterOperation::amendParameters);
    }

    private static List<List<AbstractFilterOperation>> getBoundOperations(List<List<AbstractFilterOperation>> allFilterOperations) {
        List<List<AbstractFilterOperation>> boundOperations = new ArrayList<>();
        for (List<AbstractFilterOperation> filterOperation : allFilterOperations) {
            var validOperations = new ArrayList<>(filterOperation.stream()
                    .filter(node -> node.getProbability().compareTo(BigDecimal.ONE) != 0)
                    .filter(node -> node.getProbability().compareTo(BigDecimal.ZERO) != 0).toList());
            if (validOperations.size() > 1) {
                // 禁止bound operation的in算子内的参数重用
                for (AbstractFilterOperation validOperation : validOperations) {
                    if (validOperation.getOperator().isMultiEqual()) {
                        for (Parameter parameter : validOperation.getParameters()) {
                            parameter.setCanMerge(false);
                        }
                    }
                }
                boundOperations.add(validOperations);
            }
        }
        return boundOperations;
    }

    private static void applyMultiVarConstraints(List<AbstractFilterOperation> filterOperations, int samplingSize) {
        // multi-var non-eq sampling
        Set<String> prepareSamplingColumnName = filterOperations.parallelStream()
                .filter(MultiVarFilterOperation.class::isInstance)
                .map(MultiVarFilterOperation.class::cast)
                .map(MultiVarFilterOperation::getAllCanonicalColumnNames)
                .flatMap(Collection::stream).collect(Collectors.toSet());

        ColumnManager.getInstance().cacheAttributeColumn(prepareSamplingColumnName);
        ColumnManager.getInstance().prepareGeneration(samplingSize);

        filterOperations.parallelStream()
                .filter(MultiVarFilterOperation.class::isInstance)
                .map(MultiVarFilterOperation.class::cast)
                .forEach(MultiVarFilterOperation::instantiateMultiVarParameter);
    }

    private static void boundParas(List<List<AbstractFilterOperation>> boundFilterOperations) {
        Set<TreeMap<String, Long>> allColumn2Bounds = new HashSet<>();
        for (List<AbstractFilterOperation> boundFilterOperation : boundFilterOperations) {
            TreeMap<String, Long> column2Bound = new TreeMap<>();
            for (AbstractFilterOperation validOperation : boundFilterOperation) {
                String columnName = ((UniVarFilterOperation) validOperation).getCanonicalColumnName();
                var dataIndexes = validOperation.getParameters().stream()
                        .map(Parameter::getData).collect(Collectors.toSet());
                if (dataIndexes.size() > 1) {
                    throw new UnsupportedOperationException("暂时不支持多参数的bound");
                }
                column2Bound.put(columnName, new ArrayList<>(dataIndexes).get(0));
            }
            allColumn2Bounds.add(column2Bound);
        }

        for (TreeMap<String, Long> allColumn2Bound : allColumn2Bounds) {
            BigDecimal offset = BigDecimal.ZERO;
            for (Map.Entry<String, Long> column2Bound : allColumn2Bound.entrySet()) {
                Distribution distribution = ColumnManager.getInstance().getColumn(column2Bound.getKey()).getDistribution();
                offset = offset.max(distribution.getOffset(column2Bound.getValue()));
            }
            for (Map.Entry<String, Long> column2Bound : allColumn2Bound.entrySet()) {
                Distribution distribution = ColumnManager.getInstance().getColumn(column2Bound.getKey()).getDistribution();
                distribution.getOffset2Pv().put(offset, column2Bound.getValue());
            }
        }
    }

    /**
     * 1. 对于数值型的filter, 首先计算单元的filter, 然后计算多值的filter，对于bet操作，先记录阈值，然后选择合适的区间插入，
     * 等值约束也需选择合适的区间每个filter operation内部保存自己实例化后的结果
     * 2. 对于字符型的filter, 只有like和eq的运算，直接计算即可
     *
     * @param constraintChains 待计算的约束链
     * @param samplingSize     采样大小
     */
    public static Map<Integer, Parameter> queryInstantiation(List<ConstraintChain> constraintChains, int samplingSize) {
        var allFilterOperations = pushDownProbability(constraintChains);
        var boundFilterOperations = getBoundOperations(allFilterOperations);

        List<AbstractFilterOperation> filterOperations = allFilterOperations.stream().flatMap(Collection::stream).toList();

        applyUniVarConstraints(filterOperations);

        boundParas(boundFilterOperations);

        applyMultiVarConstraints(filterOperations, samplingSize);

        Map<Integer, Parameter> id2Parameter = new HashMap<>();
        filterOperations.stream().map(AbstractFilterOperation::getParameters).flatMap(Collection::stream)
                .forEach(parameter -> id2Parameter.put(parameter.getId(), parameter));
        return id2Parameter;
    }

    private void init() throws IOException {
        ColumnManager.getInstance().setResultDir(configPath);
        ColumnManager.getInstance().loadColumnMetaData();
        //载入约束链，并进行transform
        ConstraintChainManager.getInstance().setResultDir(configPath);
        ColumnManager.getInstance().loadColumnName2IdList();
        query2constraintChains = ConstraintChainManager.loadConstrainChainResult(configPath);
    }

    public void writeQuery(Map<String, String> queryName2QueryTemplates, Map<Integer, Parameter> id2Parameter) throws IOException {
        for (Map.Entry<String, String> queryName2QueryTemplate : queryName2QueryTemplates.entrySet()) {
            String query = queryName2QueryTemplate.getValue();
            File queryPath = new File(configPath + QUERIES);
            if (!queryPath.exists()) {
                queryPath.mkdir();
            }
            String path = configPath + QUERIES + '/' + queryName2QueryTemplate.getKey();
            List<List<String>> matches = matchPattern(PATTERN, query);
            if (matches.isEmpty()) {
                CommonUtils.writeFile(path, query);
            } else {
                for (List<String> group : matches) {
                    int parameterId = Integer.parseInt(group.get(1));
                    Parameter parameter = id2Parameter.remove(parameterId);
                    if (parameter != null) {
                        String parameterData = parameter.getDataValue();
                        try {
                            if (parameterData.contains("interval")) {
                                query = query.replaceAll(group.get(0), String.format("%s", parameterData));
                            } else {
                                query = query.replaceAll(group.get(0), String.format("'%s'", parameterData));
                            }
                        } catch (IllegalArgumentException e) {
                            logger.error("query is " + query + "; group is " + group + "; parameter data is " + parameterData, e);
                        }
                    }
                    CommonUtils.writeFile(path, query);
                }
            }
        }
    }

    private Map<String, String> getQueryName2QueryTemplates() throws IOException {
        String path = configPath + WORKLOAD_DIR;
        File sqlDic = new File(path);
        File[] sqlArray = sqlDic.listFiles();
        assert sqlArray != null;
        Map<String, String> queryName2QueryTemplates = new HashMap<>();
        for (File file : sqlArray) {
            if (!file.isDirectory()) {
                continue;
            }
            File[] eachFile = file.listFiles();
            assert eachFile != null;
            for (File sqlTemplate : eachFile) {
                if (sqlTemplate.getName().contains("Template")) {
                    String key = sqlTemplate.getName().replace("Template", "");
                    StringBuilder buffer = new StringBuilder();
                    try (BufferedReader bf = new BufferedReader(new FileReader(sqlTemplate.getPath()))) {
                        String s;
                        while ((s = bf.readLine()) != null) {//使用readLine方法，一次读一行
                            buffer.append(s.trim()).append("\n");
                        }
                    }
                    String value = buffer.toString();
                    queryName2QueryTemplates.put(key, value);
                }
            }
        }
        return queryName2QueryTemplates;
    }
}
