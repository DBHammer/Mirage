package ecnu.db.generator;


import ecnu.db.generator.constraintchain.ConstraintChain;
import ecnu.db.generator.constraintchain.ConstraintChainManager;
import ecnu.db.generator.constraintchain.ConstraintChainNode;
import ecnu.db.generator.constraintchain.join.ConstraintChainFkJoinNode;
import ecnu.db.generator.constraintchain.join.ConstraintChainPkJoinNode;
import ecnu.db.generator.joininfo.JoinStatus;
import ecnu.db.generator.joininfo.RuleTableManager;
import ecnu.db.schema.ColumnManager;
import ecnu.db.schema.TableManager;
import org.jgrapht.Graph;
import org.jgrapht.alg.connectivity.KosarajuStrongConnectivityInspector;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;


@CommandLine.Command(name = "generate", description = "generate database according to gathered information",
        mixinStandardHelpOptions = true, sortOptions = false)
public class DataGenerator implements Callable<Integer> {
    private static final Logger logger = LoggerFactory.getLogger(DataGenerator.class);
    @CommandLine.Option(names = {"-c", "--config_path"}, required = true, description = "the config path for data generation")
    private String configPath;
    @CommandLine.Option(names = {"-o", "--output_path"}, description = "output path for data and join info")
    private String outputPath;
    @CommandLine.Option(names = {"-i", "--generator_id"}, description = "the id of current generator")
    private int generatorId;
    @CommandLine.Option(names = {"-n", "--num"}, description = "size of generators")
    private int generatorNum;
    @CommandLine.Option(names = {"-l", "--step_size"}, description = "the size of each batch", defaultValue = "7000000")
    private int stepSize;
    @CommandLine.Option(names = {"-t", "--thread_num"}, description = "the thread num of generator", defaultValue = "16")
    private int threadNum;

    private Map<String, List<ConstraintChain>> schema2chains;

    private DataWriter dataWriter;


    // batch生成的起始位置
    private long batchStart;

    // batch生成的大小
    private long batchSize;

    // 下一次batch需要推进的range
    private long stepRange;

    private static Map<String, List<ConstraintChain>> getSchema2Chains(Map<String, List<ConstraintChain>> query2chains) {
        Map<String, List<ConstraintChain>> schema2chains = new HashMap<>();
        for (List<ConstraintChain> chains : query2chains.values()) {
            for (ConstraintChain chain : chains) {
                if (!schema2chains.containsKey(chain.getTableName())) {
                    schema2chains.put(chain.getTableName(), new ArrayList<>());
                }
                schema2chains.get(chain.getTableName()).add(chain);
            }
        }
        return schema2chains;
    }

    public static long populateKey = 0;

    public static long solveCP = 0;

    public static long generate = 0;

    public static long transferTime = 0;

    public static long populatePk = 0;
    public static long generateView = 0;

    public static long constructCP = 0;

    private void init() throws IOException {
        //载入schema配置文件
        TableManager.getInstance().setResultDir(configPath);
        TableManager.getInstance().loadSchemaInfo();
        //载入分布配置文件
        ColumnManager.getInstance().setResultDir(configPath);
        ColumnManager.getInstance().loadColumnMetaData();
        ColumnManager.getInstance().loadColumnDistribution();
        //载入约束链，并进行transform
        ConstraintChainManager.getInstance().setResultDir(configPath);
        Map<String, List<ConstraintChain>> query2chains = ConstraintChainManager.loadConstrainChainResult(configPath);
        ConstraintChainManager.getInstance().cleanConstrainChains(query2chains);
        schema2chains = getSchema2Chains(query2chains);
        // 删除上次生成的数据
        File dataDir = new File(outputPath);
        if (dataDir.isDirectory() && dataDir.listFiles() != null) {
            Arrays.stream(Objects.requireNonNull(dataDir.listFiles()))
                    .filter(File::delete)
                    .forEach(file -> logger.info("删除{}", file.getName()));
        }
        // 初始化数据生成器
        dataWriter = new DataWriter(outputPath, generatorId);

        stepRange = (long) stepSize * (generatorNum - 1);
    }

    private List<List<String>> classifyFkDependency(List<ConstraintChain> haveFkConstrainChains) {
        Graph<String, DefaultEdge> graph = new DefaultDirectedGraph<>(DefaultEdge.class);
        HashSet<String> allFkCols = new HashSet<>();
        for (ConstraintChain haveFkConstrainChain : haveFkConstrainChains) {
            for (ConstraintChainFkJoinNode fkJoinNode : haveFkConstrainChain.getFkNodes()) {
                allFkCols.add(fkJoinNode.getLocalCols());
            }
        }
        for (String fkCol : allFkCols) {
            graph.addVertex(fkCol);
        }
        for (ConstraintChain haveFkConstrainChain : haveFkConstrainChains) {
            List<ConstraintChainFkJoinNode> fkJoinNodes = haveFkConstrainChain.getFkNodes();
            String lastColName = fkJoinNodes.get(0).getLocalCols();
            for (int i = 1; i < fkJoinNodes.size(); i++) {
                String currentColName = fkJoinNodes.get(i).getLocalCols();
                graph.addEdge(lastColName, currentColName);
                lastColName = currentColName;
            }
        }
        List<Set<String>> fkSets = new KosarajuStrongConnectivityInspector<>(graph).stronglyConnectedSets();
        return fkSets.stream().map(fkSet -> fkSet.stream().toList()).toList();
    }

    private void computeStepRange(long tableSize) {
        if ((long) stepSize * generatorNum > tableSize) {
            batchSize = tableSize / generatorNum;
        } else {
            batchSize = stepSize;
        }
        batchStart = batchSize * generatorId;
    }


    private boolean[][] generateStatusViewOfEachRow(List<ConstraintChain> constraintChains, int range) {
        // 计算外键的filter status
        boolean[][] statusVectorOfEachRow = new boolean[range][constraintChains.size()];
        constraintChains.stream().parallel().forEach(chain -> {
            boolean[] statusVector = chain.evaluateFilterStatus(range);
            int chainIndex = chain.getChainIndex();
            for (int rowId = 0; rowId < statusVector.length; rowId++) {
                statusVectorOfEachRow[rowId][chainIndex] = statusVector[rowId];
            }
        });
        return statusVectorOfEachRow;
    }

    private StringBuilder[] generatePks(boolean[][] statusVectorOfEachRow, int[] pkStatusChainIndexes, int range, String pkName) {
        //todo 处理多列主键
        StringBuilder[] rowData = new StringBuilder[range];
        if (pkStatusChainIndexes.length > 0) {
            //创建主键状态矩阵
            JoinStatus[] allStatuses = Arrays.stream(statusVectorOfEachRow).parallel()
                    .map(arr -> FkGenerator.chooseCorrespondingStatus(arr, pkStatusChainIndexes)).toArray(JoinStatus[]::new);
            Map<JoinStatus, Long> pkHistogram = FkGenerator.generateStatusHistogram(allStatuses);
            logger.info("{}的状态表为", pkName);
            for (Map.Entry<JoinStatus, Long> joinStatusLongEntry : pkHistogram.entrySet()) {
                logger.info("size:{}, status:{}", joinStatusLongEntry.getValue(), joinStatusLongEntry.getKey().status());
            }
            var pkStatus2Location = RuleTableManager.getInstance().addRuleTable(pkName, pkHistogram, batchStart);
            IntStream.range(0, range).parallel().forEach(i -> {
                JoinStatus status = FkGenerator.chooseCorrespondingStatus(statusVectorOfEachRow[i], pkStatusChainIndexes);
                rowData[i] = new StringBuilder().append(pkStatus2Location.get(status).getAndIncrement()).append(',');
            });
        }
        //处理不需要外键填充的主键状态
        else if (!pkName.isEmpty()) {
            IntStream.range(0, range).parallel().forEach(i -> rowData[i] = new StringBuilder().append(batchStart + i).append(','));
        } else {
            IntStream.range(0, range).parallel().forEach(i -> rowData[i] = new StringBuilder());
        }
        return rowData;
    }

    private Map<String, long[]> generateFks(boolean[][] statusVectorOfEachRow, FkGenerator[] fkGenerators,
                                            List<List<String>> fkGroups) {
        Map<String, long[]> fkCol2Values = new TreeMap<>();
        for (int groupIndex = 0; groupIndex < fkGenerators.length; groupIndex++) {
            long[][] fkValues = fkGenerators[groupIndex].generateFK(statusVectorOfEachRow);
            List<String> fkGroup = fkGroups.get(groupIndex);
            for (int fkColIndex = 0; fkColIndex < fkGroup.size(); fkColIndex++) {
                fkCol2Values.put(fkGroup.get(fkColIndex), fkValues[fkColIndex]);
            }
        }
        return fkCol2Values;
    }

    private void generateFksNoConstraints(Map<String, long[]> fkCol2Values, SortedMap<String, Long> allFk2TableSize, int range) {
        for (Map.Entry<String, Long> fk2TableSize : allFk2TableSize.entrySet()) {
            if (!fkCol2Values.containsKey(fk2TableSize.getKey())) {
                long[] fks = ThreadLocalRandom.current().longs(range, 1, fk2TableSize.getValue() + 1).toArray();
                fkCol2Values.put(fk2TableSize.getKey(), fks);
            }
        }
    }

    private int[] getPkStatusChainIndexes(List<ConstraintChain> allChains) {
        TreeMap<Integer, Integer> pkJoinTag2ChainIndex = new TreeMap<>();
        for (ConstraintChain constraintChain : allChains) {
            for (ConstraintChainNode node : constraintChain.getNodes()) {
                if (node instanceof ConstraintChainPkJoinNode pkJoinNode) {
                    pkJoinTag2ChainIndex.put(pkJoinNode.getPkTag(), constraintChain.getChainIndex());
                }
            }
        }
        return pkJoinTag2ChainIndex.values().stream().mapToInt(Integer::intValue).toArray();
    }

    private void generateTableWithoutChains(long pkStart, long tableSize, String schemaName) {
        while (batchStart < tableSize) {
            int range = (int) (Math.min(batchStart + batchSize, tableSize) - batchStart);
            //生成属性列数据
            ColumnManager.getInstance().prepareGeneration(range);
            String[] attRows = ColumnManager.getInstance().generateAttRows(range);
            StringBuilder[] rowData = new StringBuilder[range];
            IntStream.range(0, range).parallel().forEach(i -> rowData[i] = new StringBuilder().append(batchStart + i + pkStart).append(","));
            dataWriter.addWriteTask(schemaName, rowData, attRows);
            batchStart += range + stepRange;
        }
    }

    @Override
    public Integer call() throws Exception {
        System.setProperty("java.util.concurrent.ForkJoinPool.common.parallelism", String.valueOf(threadNum));
        init();
        long start = System.currentTimeMillis();
        for (String schemaName : TableManager.getInstance().createTopologicalOrder()) {
            long tableSize = TableManager.getInstance().getTableSize(schemaName);
            String pkName = TableManager.getInstance().getPrimaryKeys(schemaName);
            computeStepRange(tableSize);
            logger.info("开始输出表数据{}, 数据总量为{}", schemaName, tableSize);
            // 准备生成的属性列生成器
            List<String> attColumnNames = TableManager.getInstance().getAttributeColumnNames(schemaName);
            ColumnManager.getInstance().cacheAttributeColumn(attColumnNames);
            // 获得所有约束链
            List<ConstraintChain> allChains = schema2chains.get(schemaName);
            if (allChains == null) {
                // todo 当前假设主键是连续的
                generateTableWithoutChains(ColumnManager.getInstance().getMin(pkName), tableSize, schemaName);
                continue;
            }
            // 设置chain的索引
            for (int i = 0; i < allChains.size(); i++) {
                allChains.get(i).setChainIndex(i);
            }
            // 获取外键约束链
            List<ConstraintChain> haveFkConstrainChains = allChains.stream().filter(ConstraintChain::hasFkNode).toList();
            // 根据外键列的连接依赖性划外键列生成组
            List<List<String>> fkGroups = classifyFkDependency(haveFkConstrainChains);
            SortedMap<String, Long> allFk2TableSize = TableManager.getInstance().getFk2PkTableSize(schemaName);
            FkGenerator[] fkGenerators = new FkGenerator[fkGroups.size()];
            for (int i = 0; i < fkGenerators.length; i++) {
                fkGenerators[i] = new FkGenerator(allChains, fkGroups.get(i), tableSize);
            }
            int[] pkStatusChainIndexes = getPkStatusChainIndexes(allChains);
            // 开始生成
            while (batchStart < tableSize) {
                int range = (int) (Math.min(batchStart + batchSize, tableSize) - batchStart);
                //生成属性列数据
                long start1 = System.currentTimeMillis();
                ColumnManager.getInstance().prepareGeneration(range);//generate统计
                generate += (System.currentTimeMillis() - start1);
                // 生成每一行的视图
                long start5 = System.currentTimeMillis();
                boolean[][] statusVectorOfEachRow = generateStatusViewOfEachRow(allChains, range);
                generateView += System.currentTimeMillis() - start5;
                Map<String, long[]> fkCol2Values = generateFks(statusVectorOfEachRow, fkGenerators, fkGroups);
                generateFksNoConstraints(fkCol2Values, allFk2TableSize, range);
                long startNew = System.currentTimeMillis();
                StringBuilder[] keyData = generatePks(statusVectorOfEachRow, pkStatusChainIndexes, range, pkName);
                populatePk += System.currentTimeMillis() - startNew;
                IntStream.range(0, keyData.length).parallel().forEach(index -> {
                    StringBuilder row = keyData[index];
                    for (long[] fks : fkCol2Values.values()) {
                        long fk = fks[index];
                        if (fk == Long.MIN_VALUE) {
                            row.append("\\N,");
                        } else {
                            row.append(fk).append(",");
                        }
                    }
                });
                //转换为字符串准备输出
                long start2 = System.currentTimeMillis();
                String[] data = ColumnManager.getInstance().generateAttRows(range);
                transferTime += (System.currentTimeMillis() - start2);
                dataWriter.addWriteTask(schemaName, keyData, data);
                batchStart += range + stepRange;
            }
        }

        logger.info("ge:{}", generate);
        logger.info("tr:{}", transferTime);
        logger.info("gv:{}", generateView);
        logger.info("cc:{}", constructCP);
        logger.info("sc:{}", solveCP);
        logger.info("pk:{}", populateKey);
        logger.info("pp:{}", populatePk);
        logger.info("总用时:{}", System.currentTimeMillis() - start);
        if (dataWriter.waitWriteFinish()) {
            logger.info("输出表数据完成");
        }
        return 0;
    }
}
