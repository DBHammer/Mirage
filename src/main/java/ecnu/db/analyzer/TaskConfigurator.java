package ecnu.db.analyzer;

import com.alibaba.druid.DbType;
import ecnu.db.LanguageManager;
import ecnu.db.analyzer.online.AbstractAnalyzer;
import ecnu.db.analyzer.online.QueryAnalyzer;
import ecnu.db.analyzer.online.adapter.pg.PgAnalyzer;
import ecnu.db.analyzer.online.adapter.pg.PgJsonReader;
import ecnu.db.analyzer.online.adapter.tidb.TidbAnalyzer;
import ecnu.db.analyzer.statical.QueryReader;
import ecnu.db.analyzer.statical.QueryWriter;
import ecnu.db.dbconnector.DbConnector;
import ecnu.db.dbconnector.adapter.GaussConnector;
import ecnu.db.dbconnector.adapter.PgConnector;
import ecnu.db.dbconnector.adapter.Tidb3Connector;
import ecnu.db.dbconnector.adapter.Tidb4Connector;
import ecnu.db.generator.constraintchain.ConstraintChain;
import ecnu.db.generator.constraintchain.ConstraintChainManager;
import ecnu.db.generator.constraintchain.ConstraintChainNode;
import ecnu.db.generator.constraintchain.agg.ConstraintChainAggregateNode;
import ecnu.db.generator.constraintchain.filter.ConstraintChainFilterNode;
import ecnu.db.generator.constraintchain.filter.Parameter;
import ecnu.db.schema.ColumnManager;
import ecnu.db.schema.Table;
import ecnu.db.schema.TableManager;
import ecnu.db.utils.CommonUtils;
import ecnu.db.utils.DatabaseConnectorConfig;
import ecnu.db.utils.exception.TouchstoneException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static ecnu.db.utils.CommonUtils.MAPPER;

/**
 * @author alan
 */

@CommandLine.Command(name = "prepare", description = "extract database information for data generation",
        mixinStandardHelpOptions = true, usageHelpAutoWidth = true)
public class TaskConfigurator implements Callable<Integer> {
    public static final String SQL_FILE_POSTFIX = ".sql";
    private final Logger logger = LoggerFactory.getLogger(TaskConfigurator.class);
    @CommandLine.ArgGroup(exclusive = false, multiplicity = "1")
    private TaskConfiguratorConfig taskConfiguratorConfig;
    private static final String WORKLOAD_DIR = "/workload";
    private final ResourceBundle rb = LanguageManager.getInstance().getRb();

    private static final HashMap<String, Map<String, List<Integer>>> columnName2ParameterID = new HashMap<>();

    @Override
    public Integer call() throws Exception {
        ecnu.db.utils.TaskConfiguratorConfig config;
        if (taskConfiguratorConfig.othersConfig.fileConfigInfo != null) {
            config = MAPPER.readValue(CommonUtils.readFile(taskConfiguratorConfig.othersConfig.fileConfigInfo.configPath),
                    ecnu.db.utils.TaskConfiguratorConfig.class);
        } else {
            CliConfigInfo cliConfigInfo = taskConfiguratorConfig.othersConfig.cliConfigInfo;
            config = new ecnu.db.utils.TaskConfiguratorConfig();
            config.setDatabaseConnectorConfig(new DatabaseConnectorConfig(cliConfigInfo.databaseIp, cliConfigInfo.databasePort,
                    cliConfigInfo.databaseUser, cliConfigInfo.databasePwd, cliConfigInfo.databaseName));
        }
        QueryReader queryReader = new QueryReader(config.getDefaultSchemaName(), config.getQueriesDirectory());
        QueryWriter queryWriter = new QueryWriter();
        TableManager.getInstance().setResultDir(config.getResultDirectory());
        ColumnManager.getInstance().setResultDir(config.getResultDirectory());
        File resultDir = new File(config.getResultDirectory());
        if (!resultDir.exists() || !resultDir.isDirectory()) {
            if (resultDir.mkdirs()) {
                logger.info(rb.getString("createResultDir"), config.getResultDirectory());
            } else {
                logger.error(rb.getString("createResultDirFail"), config.getResultDirectory());
                System.exit(-1);
            }
        }
        ConstraintChainManager.getInstance().setResultDir(config.getResultDirectory());
        if (taskConfiguratorConfig.isLoad) {
            TableManager.getInstance().loadSchemaInfo();
            ColumnManager.getInstance().loadColumnMetaData();
        }
        DbConnector dbConnector;
        AbstractAnalyzer abstractAnalyzer;
        switch (taskConfiguratorConfig.dbType) {
            case TIDB3 -> {
                dbConnector = new Tidb3Connector(config.getDatabaseConnectorConfig());
                abstractAnalyzer = new TidbAnalyzer();
                queryWriter.setDbType(DbType.mysql);
                queryReader.setDbType(DbType.mysql);
            }
            case TIDB4 -> {
                dbConnector = new Tidb4Connector(config.getDatabaseConnectorConfig());
                abstractAnalyzer = new TidbAnalyzer();
                queryWriter.setDbType(DbType.mysql);
                queryReader.setDbType(DbType.mysql);
            }
            case GAUSS -> {
                dbConnector = new GaussConnector(config.getDatabaseConnectorConfig());
                abstractAnalyzer = new PgAnalyzer();
                queryWriter.setDbType(DbType.mysql);
                queryReader.setDbType(DbType.mysql);
                PgJsonReader.setIsGauss();
            }
            case POSTGRESQL -> {
                dbConnector = new PgConnector(config.getDatabaseConnectorConfig());
                abstractAnalyzer = new PgAnalyzer();
                queryWriter.setDbType(DbType.postgresql);
                queryReader.setDbType(DbType.postgresql);
            }
            default -> throw new TouchstoneException(rb.getString("UnsupportedDatabaseType"));
        }
        QueryAnalyzer analyzer = new QueryAnalyzer(abstractAnalyzer, dbConnector);
        extract(dbConnector, analyzer, queryReader, queryWriter, config.getResultDirectory());
        return 0;
    }

    public void dealWithUnknownTable(Set<String> unKnownCols, Table table,
                                     String canonicalTableName,
                                     Map<String, Set<String>> tableName2Columns) {
        for (String unKnownCol : unKnownCols) {
            String completeCol = canonicalTableName + "." + unKnownCol.split("\\.")[2];
            if (table.containColumn(completeCol)) {
                tableName2Columns.putIfAbsent(canonicalTableName, new HashSet<>());
                tableName2Columns.get(canonicalTableName).add(completeCol);
            }
        }
    }


    private List<File> querySchemaMetadataAndColumnMetadata(QueryReader queryReader, DbConnector dbConnector)
            throws IOException, TouchstoneException, SQLException {
        List<File> queryFiles = queryReader.loadQueryFiles();
        List<String> tableNames = queryReader.fetchTableNames(queryFiles);
        Map<String, Set<String>> tableName2Columns = queryReader.fetchQueryColumnNames(queryFiles);
        Set<String> unKnownCols = new HashSet<>();
        for (String s : new HashSet<>(tableName2Columns.keySet())) {
            if (s.split("\\.")[1].equals("UNKNOWN")) {
                unKnownCols = tableName2Columns.remove(s);
            }
        }
        logger.info(rb.getString("GetTableNameSuccessfully"), tableNames);
        for (String canonicalTableName : tableNames) {
            logger.info(rb.getString("StartGettingColumnMetadata"), canonicalTableName);
            if (TableManager.getInstance().containSchema(canonicalTableName)) {
                logger.info(rb.getString("ColumnMetadataHasLoaded"), canonicalTableName);
            } else {
                Table table = new Table(dbConnector.getColumnMetadata(canonicalTableName),
                        dbConnector.getTableSize(canonicalTableName));
                table.setPrimaryKeys(dbConnector.getPrimaryKey(canonicalTableName));
                dealWithUnknownTable(unKnownCols, table, canonicalTableName, tableName2Columns);
                TableManager.getInstance().addSchema(canonicalTableName, table);
                logger.info(rb.getString("GetColumnMetadataSuccessfully"), canonicalTableName);
                logger.info(rb.getString("StartGettingTheDataDistributionOfTable"), canonicalTableName);
                List<String> allColumns = tableName2Columns.get(canonicalTableName).stream().toList();
                ColumnManager.getInstance().setDataRangeBySqlResult(allColumns,
                        dbConnector.getDataRange(canonicalTableName, allColumns));
                logger.info(rb.getString("GetTheDataDistributionOfTableSuccessfully"), canonicalTableName);
            }

        }
        logger.info(rb.getString("ObtainTableStructureAndDataDistributionSuccessfully"));
        logger.info(rb.getString("StartPersistingTableStructureInformation"));
        TableManager.getInstance().storeSchemaInfo();
        logger.info(rb.getString("PersistenceOfTableStructureInformationSucceeded"));
        logger.info(rb.getString("StartPersistingDataDistributionInformation"));
        ColumnManager.getInstance().storeColumnMetaData();
        logger.info(rb.getString("PersistentDataDistributionInformationSucceeded"));
        return queryFiles;
    }

    public Map<String, List<ConstraintChain>> checkQueryConstraintChains(Map<String, List<ConstraintChain>> query2constraintChains) {
        logger.info(rb.getString("StartCleaningUpTheQueryPlan"));
        for (Map.Entry<String, List<ConstraintChain>> query2constrainChain : query2constraintChains.entrySet()) {
            List<ConstraintChain> constraintChains = query2constrainChain.getValue();
            List<ConstraintChain> reduceConstraintChains = new LinkedList<>();
            for (ConstraintChain constraintChain : constraintChains) {
                // 移除带有键值的过滤条件
                if (constraintChain.getNodes().stream()
                        .filter(ConstraintChainFilterNode.class::isInstance)
                        .map(ConstraintChainFilterNode.class::cast)
                        .anyMatch(ConstraintChainFilterNode::hasKeyColumn)) {
                    reduceConstraintChains.add(constraintChain);
                } else {
                    List<ConstraintChainAggregateNode> aggregateNodes = constraintChain.getNodes().stream()
                            .filter(ConstraintChainAggregateNode.class::isInstance)
                            .map(ConstraintChainAggregateNode.class::cast)
                            .filter(ConstraintChainAggregateNode::removeAgg).toList();
                    if (!aggregateNodes.isEmpty()) {
                        logger.info(rb.getString("RemoveSomeAggregationNodeOnAttributes"), query2constrainChain.getKey(), aggregateNodes);
                        constraintChain.getNodes().removeAll(aggregateNodes);
                    }
                }
            }
            constraintChains.removeAll(reduceConstraintChains);
            if (!reduceConstraintChains.isEmpty()) {
                logger.error(rb.getString("RemoveSomeChainsWithFilterOnKeysFrom"), query2constrainChain.getKey());
                String reduceChains = reduceConstraintChains.stream()
                        .map(ConstraintChain::toString)
                        .collect(Collectors.joining(System.lineSeparator()));
                logger.error(reduceChains);
            }
            constraintChains.removeIf(constraintChain -> constraintChain.getNodes().isEmpty());
        }
        logger.info(rb.getString("CleanupQueryPlanCompleted"));
        return query2constraintChains;
    }

    public void extract(DbConnector dbConnector, QueryAnalyzer queryAnalyzer, QueryReader queryReader,
                        QueryWriter queryWriter, String resultDir) throws IOException, TouchstoneException, SQLException {
        List<File> queryFiles = querySchemaMetadataAndColumnMetadata(queryReader, dbConnector);
        Map<String, List<ConstraintChain>> query2constraintChains = new HashMap<>();
        Map<String, String> queryName2QueryTemplates = new HashMap<>();
        logger.info(rb.getString("StartGettingQueryPlans"));
        queryFiles = queryFiles.stream().filter(File::isFile)
                .filter(queryFile -> queryFile.getName().endsWith(SQL_FILE_POSTFIX)).toList();
        queryFiles = new LinkedList<>(queryFiles);
        queryFiles.sort(Comparator.comparing(File::getName));
        for (File queryFile : queryFiles) {
            List<String> queries = queryReader.getQueriesFromFile(queryFile.getPath());
            for (int i = 0; i < queries.size(); i++) {
                String query = queries.get(i);
                String queryCanonicalName = queryFile.getName().replace(SQL_FILE_POSTFIX, "_" + (i + 1) + SQL_FILE_POSTFIX);
                logger.info(rb.getString("StartGetting"), queryCanonicalName);
                queryAnalyzer.setAliasDic(queryReader.getTableAlias(query));
                List<Parameter> parameters = new ArrayList<>();
                List<List<ConstraintChain>> constraintChainsOfMultiplePlans = queryAnalyzer.extractQuery(query);
                int subPlanIndex = 0;
                for (List<ConstraintChain> constraintChains : constraintChainsOfMultiplePlans) {
                    if (subPlanIndex++ > 0) {
                        query2constraintChains.put(queryCanonicalName + "_" + subPlanIndex, constraintChains);
                    } else {
                        query2constraintChains.put(queryCanonicalName, constraintChains);
                    }
                    buildColumnName2ParameterID(constraintChains);
                    parameters.addAll(constraintChains.stream().flatMap((c -> c.getParameters().stream())).toList());
                }
                queryName2QueryTemplates.put(queryCanonicalName, queryWriter.templatizeSql(queryCanonicalName, query, parameters));
            }
        }
        writeWithoutParameterValue();
        logger.info(rb.getString("GetQueryPlanDone"));
        logger.info(rb.getString("StartPersistingTableReferenceInformation"));
        TableManager.getInstance().adjustFks();
        TableManager.getInstance().storeSchemaInfo();
        logger.info(rb.getString("PersistentTableReferenceInformationSucceeded"));
        query2constraintChains = checkQueryConstraintChains(query2constraintChains);
        logger.info(rb.getString("StartPersistentQueryPlan"));
        ConstraintChainManager.getInstance().storeConstraintChain(query2constraintChains);
        logger.info(rb.getString("PersistentQueryPlanCompleted"));
        logger.info(rb.getString("StartQueryTemplating"));
        writeTemplateQuery(queryName2QueryTemplates, resultDir);
        logger.info(rb.getString("FillInTheQueryTemplateComplete"));
    }

    private void writeWithoutParameterValue() throws IOException {
        Map<String, List<List<Integer>>> column2IdList = new HashMap<>();
        for (Map.Entry<String, Map<String, List<Integer>>> column2ID : columnName2ParameterID.entrySet()) {
            column2IdList.put(column2ID.getKey(), column2ID.getValue().values().stream().toList());
        }
        ColumnManager.getInstance().storeColumnName2IdList(column2IdList);
    }

    private void buildColumnName2ParameterID(List<ConstraintChain> constraintChains) {
        for (ConstraintChain constraintChain : constraintChains) {
            ConstraintChainNode node = constraintChain.getNodes().get(0);
            if (node instanceof ConstraintChainFilterNode filterNode) {
                filterNode.getRoot().getColumn2ParameterBucket(columnName2ParameterID);
            }
        }
    }

    public void writeTemplateQuery(Map<String, String> queryName2QueryTemplates, String resultDir) throws IOException {
        String path = resultDir + WORKLOAD_DIR;
        File file = new File(path);
        if (!file.exists()) {
            file.mkdir();
        }
        for (Map.Entry<String, String> queryName2QueryTemplate : queryName2QueryTemplates.entrySet()) {
            String currentPath = path + '/' + queryName2QueryTemplate.getKey().split("\\.")[0];
            File currentFile = new File(currentPath);
            if (!currentFile.exists()) {
                currentFile.mkdir();
            }
            String pathOfTemplate = currentPath + '/' + queryName2QueryTemplate.getKey().split("\\.")[0] + "Template.sql";
            CommonUtils.writeFile(pathOfTemplate, queryName2QueryTemplate.getValue());
        }
    }

    static class TaskConfiguratorConfig {
        @CommandLine.ArgGroup
        private OthersConfig othersConfig;
        @CommandLine.Option(names = {"-t", "--db_type"}, required = true, description = "database version: ${COMPLETION-CANDIDATES}")
        private TouchstoneDbType dbType;
        @CommandLine.Option(names = {"-l", "--load"}, description = "load the configuration from the previous result")
        private boolean isLoad;
    }

    static class OthersConfig {
        @CommandLine.ArgGroup(exclusive = false, heading = "Input configuration by file%n")
        FileConfigInfo fileConfigInfo;
        @CommandLine.ArgGroup(exclusive = false, heading = "Input configuration by cli%n")
        CliConfigInfo cliConfigInfo;
    }

    static class FileConfigInfo {
        @CommandLine.Option(names = {"-c", "--config_path"}, required = true, description = "file path to read query instantiation configuration, " +
                "other settings in command line will override the settings in the file")
        private String configPath;

    }

    static class CliConfigInfo {
        @CommandLine.Option(names = {"-H", "--host"}, required = true, defaultValue = "localhost", description = "database ip, default value: '${DEFAULT-VALUE}'")
        private String databaseIp;
        @CommandLine.Option(names = {"-P", "--port"}, required = true, defaultValue = "4000", description = "database port, default value: '${DEFAULT-VALUE}'")
        private String databasePort;
        @CommandLine.Option(names = {"-u", "--user"}, required = true, description = "database user name")
        private String databaseUser;
        @CommandLine.Option(names = {"-p", "--password"}, required = true, description = "database password", interactive = true)
        private String databasePwd;
        @CommandLine.Option(names = {"-D", "--database_name"}, description = "database name")
        private String databaseName;
        @CommandLine.Option(names = {"-q", "--query_input"}, required = true, description = "the dir path of queries")
        private String queriesDirectory;
        @CommandLine.Option(names = {"-o", "--output"}, required = true, description = "the dir path for output")
        private String resultDirectory;
        @CommandLine.Option(names = {"--skip_threshold"}, description = "skip threshold, if passsing this threshold, then we will skip the node")
        private Double skipNodeThreshold;
    }
}
