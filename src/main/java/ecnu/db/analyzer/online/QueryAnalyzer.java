package ecnu.db.analyzer.online;

import ecnu.db.LanguageManager;
import ecnu.db.analyzer.online.node.*;
import ecnu.db.dbconnector.DbConnector;
import ecnu.db.generator.constraintchain.ConstraintChain;
import ecnu.db.generator.constraintchain.agg.ConstraintChainAggregateNode;
import ecnu.db.generator.constraintchain.filter.ConstraintChainFilterNode;
import ecnu.db.generator.constraintchain.filter.LogicNode;
import ecnu.db.generator.constraintchain.filter.Parameter;
import ecnu.db.generator.constraintchain.join.ConstraintChainFkJoinNode;
import ecnu.db.generator.constraintchain.join.ConstraintChainPkJoinNode;
import ecnu.db.generator.constraintchain.join.ConstraintNodeJoinType;
import ecnu.db.schema.ColumnManager;
import ecnu.db.schema.TableManager;
import ecnu.db.utils.exception.TouchstoneException;
import ecnu.db.utils.exception.analyze.UnsupportedSelect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

import static ecnu.db.utils.CommonUtils.CANONICAL_NAME_CONTACT_SYMBOL;
import static ecnu.db.utils.CommonUtils.DECIMAL_DIVIDE_SCALE;

public class QueryAnalyzer {

    protected static final Logger logger = LoggerFactory.getLogger(QueryAnalyzer.class);
    private static final int SKIP_JOIN_TAG = -1;
    private static final int STOP_CONSTRUCT = -2;
    private static final int SKIP_SELF_JOIN = -3;
    private final AbstractAnalyzer abstractAnalyzer;
    private final DbConnector dbConnector;
    protected double skipNodeThreshold = 0.01;
    private final ResourceBundle rb = LanguageManager.getInstance().getRb();

    private static final boolean OPEN_SKIP_JOIN_FEATURE = true;


    public QueryAnalyzer(AbstractAnalyzer abstractAnalyzer, DbConnector dbConnector) {
        this.abstractAnalyzer = abstractAnalyzer;
        this.dbConnector = dbConnector;
    }

    public void setAliasDic(Map<String, String> aliasDic) {
        abstractAnalyzer.setAliasDic(aliasDic);
    }


    /**
     * 根据输入的列名统计非重复值的个数，进而给出该列是否为主键
     *
     * @param pkTable 需要测试的主表
     * @param pkCol   主键
     * @param fkTable 外表
     * @param fkCol   外键
     * @return 该列是否为主键
     * @throws TouchstoneException 由于逻辑错误无法判断是否为主键的异常
     * @throws SQLException        无法通过数据库SQL查询获得多列属性的ndv
     */
    private boolean isPrimaryKey(String pkTable, String pkCol, String fkTable, String fkCol) throws TouchstoneException, SQLException {
        if (TableManager.getInstance().isRefTable(fkTable, fkCol, pkTable + "." + pkCol)) {
            return true;
        }
        if (TableManager.getInstance().isRefTable(pkTable, pkCol, fkTable + "." + fkCol)) {
            return false;
        }
        int leftTableNdv;
        int rightTableNdv;
        if (pkCol.contains(",")) {
            leftTableNdv = dbConnector.getMultiColNdv(pkTable, pkCol);
            rightTableNdv = dbConnector.getMultiColNdv(fkTable, fkCol);
        } else {
            leftTableNdv = ColumnManager.getInstance().getNdv(pkTable + CANONICAL_NAME_CONTACT_SYMBOL + pkCol);
            rightTableNdv = ColumnManager.getInstance().getNdv(fkTable + CANONICAL_NAME_CONTACT_SYMBOL + fkCol);
        }
        long leftTableSize = TableManager.getInstance().getTableSize(pkTable);
        long rightTableSize = TableManager.getInstance().getTableSize(fkTable);
        boolean isPrimaryKey;
        if (leftTableNdv == rightTableNdv) {
            if (leftTableSize == rightTableSize) {
                throw new TouchstoneException("两个表无法区分主外键");
            }
            isPrimaryKey = leftTableSize < rightTableSize;
        } else {
            isPrimaryKey = leftTableNdv > rightTableNdv;
        }
        if ((isPrimaryKey && leftTableSize > leftTableNdv) || (!isPrimaryKey && rightTableSize > rightTableNdv)) {
            throw new TouchstoneException("主键表的主键信息非unique");
        }
        return isPrimaryKey;
    }

    /**
     * 分析一个节点，提取约束链信息
     *
     * @param node            需要分析的节点
     * @param constraintChain 约束链
     * @return 节点行数，小于零代表停止继续向上分析
     * @throws TouchstoneException 节点分析出错
     * @throws SQLException        无法收集多列主键的ndv
     */
    private long analyzeNode(ExecutionNode node, ConstraintChain constraintChain, long lastNodeLineCount) throws TouchstoneException, SQLException {
        return switch (node.getType()) {
            case JOIN -> analyzeJoinNode((JoinNode) node, constraintChain, lastNodeLineCount);
            case FILTER -> analyzeSelectNode(node, constraintChain, lastNodeLineCount);
            case AGGREGATE -> analyzeAggregateNode((AggNode) node, constraintChain, lastNodeLineCount);
        };
    }

    private long analyzeSelectNode(ExecutionNode node, ConstraintChain constraintChain, long lastNodeLineCount) throws TouchstoneException {
        LogicNode root = analyzeSelectInfo(node.getInfo());
        BigDecimal ratio = computeFilterProbability(node.getOutputRows(), lastNodeLineCount);
        ConstraintChainFilterNode filterNode = new ConstraintChainFilterNode(ratio, root);
        constraintChain.addNode(filterNode);
        return node.getOutputRows();
    }

    private long analyzeAggregateNode(AggNode node, ConstraintChain constraintChain, long lastNodeLineCount) throws TouchstoneException {
        // multiple aggregation
        if (!constraintChain.getTableName().equals(node.getTableName())) {
            return STOP_CONSTRUCT;
        }

        List<String> groupKeys = null;
        if (node.getInfo() != null) {
            groupKeys = new ArrayList<>(Arrays.stream(node.getInfo().trim().split(";")).toList());
        }
        BigDecimal aggProbability = computeFilterProbability(node.getOutputRows(), lastNodeLineCount);
        ConstraintChainAggregateNode aggregateNode = new ConstraintChainAggregateNode(groupKeys, aggProbability);

        if (node.getAggFilter() != null) {
            ExecutionNode aggNode = node.getAggFilter();
            LogicNode root = analyzeSelectInfo(aggNode.getInfo());
            BigDecimal filterProbability = computeFilterProbability(aggNode.getOutputRows(), lastNodeLineCount);
            aggregateNode.setAggFilter(new ConstraintChainFilterNode(filterProbability, root));
        }
        constraintChain.addNode(aggregateNode);
        return node.getOutputRows();
    }

    private BigDecimal computeFilterProbability(long outputRowCount, long inputRowCount) {
        if (inputRowCount == 0) {
            return BigDecimal.ZERO;
        } else {
            return BigDecimal.valueOf(outputRowCount).divide(BigDecimal.valueOf(inputRowCount), DECIMAL_DIVIDE_SCALE, RoundingMode.DOWN);
        }
    }

    private long analyzeJoinNode(JoinNode node, ConstraintChain constraintChain, long lastNodeLineCount) throws TouchstoneException, SQLException {
        String[] joinColumnInfos = new String[4];
        double fkJoinProbability = abstractAnalyzer.analyzeJoinInfo(node.getInfo(), joinColumnInfos);
        String localTable = joinColumnInfos[0];
        String localCol = joinColumnInfos[1];
        String externalTable = joinColumnInfos[2];
        String externalCol = joinColumnInfos[3];
        if (localTable.equals(externalTable)) {
            node.setJoinStatus(SKIP_SELF_JOIN);
            logger.error(rb.getString("SkipSelfJoinNode"), node.getInfo());
            return STOP_CONSTRUCT;
        }
        // 如果当前的join节点，不属于之前遍历的节点
        if (!constraintChain.getTableName().equals(localTable) && !constraintChain.getTableName().equals(externalTable)) {
            if (node.getJoinStatus() == SKIP_JOIN_TAG)
                return node.getOutputRows();
            else
                return STOP_CONSTRUCT;
        }
        //将本表的信息放在前面，交换位置
        if (constraintChain.getTableName().equals(externalTable)) {
            localTable = joinColumnInfos[2];
            localCol = joinColumnInfos[3];
            externalTable = joinColumnInfos[0];
            externalCol = joinColumnInfos[1];
        }
        //根据主外键分别设置约束链输出信息
        if (isPrimaryKey(localTable, localCol, externalTable, externalCol)) {
            //设置主键
            if (constraintChain.getJoinTables().contains(externalTable)) {
                logger.error(rb.getString("skipSelfJoin"), node.getInfo());
                node.setJoinStatus(SKIP_SELF_JOIN);
                return STOP_CONSTRUCT;
            } else {
                constraintChain.addJoinTable(externalTable);
            }
            boolean pkAllRowsInput = TableManager.getInstance().getTableSize(localTable) == lastNodeLineCount;
            boolean fkColIsNotNull = true;
            if (externalCol.contains(",")) {
                for (String col : externalCol.split(",")) {
                    fkColIsNotNull &= ColumnManager.getInstance().getNullPercentage(externalTable + "." + col)
                            .compareTo(BigDecimal.ZERO) == 0;
                }
            }
            boolean joinIsNotOuterJoin = node.getPkDistinctSize().compareTo(BigDecimal.ZERO) == 0;
            boolean skipTheJoinNode = false;
            if (pkAllRowsInput && fkColIsNotNull && joinIsNotOuterJoin) {
                logger.debug(rb.getString("SkipNodeDueToFullTableScan"), node.getInfo());
                node.setJoinStatus(SKIP_JOIN_TAG);
                skipTheJoinNode = OPEN_SKIP_JOIN_FEATURE;
            }
            if (!skipTheJoinNode) {
                node.setJoinTag(TableManager.getInstance().getJoinTag(localTable));
                ConstraintChainPkJoinNode pkJoinNode = new ConstraintChainPkJoinNode(node.getJoinTag(), localCol.split(","));
                constraintChain.addNode(pkJoinNode);
                TableManager.getInstance().setPrimaryKeys(localTable, localCol);
            }
            return lastNodeLineCount;
        } else {
            constraintChain.addJoinTable(externalTable);
            logger.debug("{} wait join tag", node.getInfo());
            int joinStatus = node.getJoinStatus();
            logger.debug("{} get join tag", node.getInfo());
            TableManager.getInstance().setTmpForeignKeys(localTable, localCol, externalTable, externalCol);
            if (joinStatus == SKIP_JOIN_TAG && OPEN_SKIP_JOIN_FEATURE) {
                logger.debug(rb.getString("SkipNodeDueToFullPk"), node.getInfo());
                return node.getOutputRows();
            } else if (joinStatus == SKIP_SELF_JOIN) {
                logger.error(rb.getString("SkipSelfJoinNode"), node.getInfo());
                return STOP_CONSTRUCT;
            } else if (constraintChain.hasAggNode()) {
                logger.error("cannot support join {} after aggregation currently", node.getInfo());
                return STOP_CONSTRUCT;
            }
            TableManager.getInstance().setForeignKeys(localTable, localCol, externalTable, externalCol);
            BigDecimal probability = computeFilterProbability(node.getOutputRows(), lastNodeLineCount);
            probability = probability.divide(BigDecimal.valueOf(fkJoinProbability), DECIMAL_DIVIDE_SCALE, RoundingMode.HALF_UP);
            if (probability.compareTo(BigDecimal.ONE) > 0 || joinStatus == SKIP_JOIN_TAG) {
                probability = BigDecimal.ONE;
            }
            ConstraintChainFkJoinNode fkJoinNode = new ConstraintChainFkJoinNode(localTable + "." + localCol, externalTable + "." + externalCol, node.getJoinTag(), probability);
            // deal with index join
            String leftTable = null;
            String rightTable = null;
            ExecutionNode childNode = null;
            if (node.getLeftNode().getType() == ExecutionNodeType.FILTER && node.getLeftNode().getInfo() != null &&
                    ((FilterNode) node.getLeftNode()).isIndexScan()) {
                leftTable = node.getLeftNode().getTableName();
                childNode = node.getLeftNode();
            }
            if (node.getRightNode().getType() == ExecutionNodeType.FILTER && node.getRightNode().getInfo() != null &&
                    ((FilterNode) node.getRightNode()).isIndexScan()) {
                rightTable = node.getRightNode().getTableName();
                childNode = node.getRightNode();
            }
            if ((leftTable != null && leftTable.equals(localTable)) || (rightTable != null && rightTable.equals(localTable))) {
                long tableSize = TableManager.getInstance().getTableSize(childNode.getTableName());
                long rowsRemovedByScanFilter = tableSize - childNode.getOutputRows();
                BigDecimal probabilityWithFailFilter = computeFilterProbability(node.getRowsRemoveByFilterAfterJoin(), rowsRemovedByScanFilter);
                fkJoinNode.setProbabilityWithFailFilter(probabilityWithFailFilter);
            }
            if (node.isSemiJoin()) {
                if (node.isAntiJoin()) {
                    fkJoinNode.setType(ConstraintNodeJoinType.ANTI_SEMI_JOIN);
                } else {
                    fkJoinNode.setType(ConstraintNodeJoinType.SEMI_JOIN);
                }
                fkJoinNode.setPkDistinctProbability(fkJoinNode.getProbability());
            } else {
                if (node.getPkDistinctSize() != null && node.getPkDistinctSize().compareTo(BigDecimal.ZERO) > 0) {
                    fkJoinNode.setType(ConstraintNodeJoinType.OUTER_JOIN);
                    fkJoinNode.setPkDistinctProbability(node.getPkDistinctSize());
                } else if (node.isAntiJoin()) {
                    fkJoinNode.setType(ConstraintNodeJoinType.ANTI_JOIN);
                }
            }
            constraintChain.addNode(fkJoinNode);
            return node.getOutputRows();
        }
    }

    /**
     * 获取一条路径上的约束链
     *
     * @param path 需要处理的路径
     * @return 获取的约束链
     */
    private ConstraintChain extractConstraintChain(List<ExecutionNode> path, Set<ExecutionNode> inputNodes) throws TouchstoneException, SQLException {
        if (path == null || path.isEmpty()) {
            throw new TouchstoneException(String.format("invalid path input '%s'", path));
        }
        ExecutionNode headNode = path.getFirst();
        ConstraintChain constraintChain;
        long lastNodeLineCount;
        //分析约束链的第一个node
        if (headNode.getType() == ExecutionNodeType.FILTER) {
            constraintChain = new ConstraintChain(headNode.getTableName());
            FilterNode filterNode = (FilterNode) headNode;
            if (filterNode.getInfo() != null) {
                LogicNode result = analyzeSelectInfo(filterNode.getInfo());
                if (filterNode.isIndexScan()) {
                    result.removeOtherTablesOperation(filterNode.getTableName());
                    int rowsAfterFilter = dbConnector.getRowsAfterFilter(filterNode.getTableName(), result.toString());
                    filterNode.setOutputRows(rowsAfterFilter);
                }
                BigDecimal ratio = computeFilterProbability(filterNode.getOutputRows(), TableManager.getInstance().getTableSize(filterNode.getTableName()));
                constraintChain.addNode(new ConstraintChainFilterNode(ratio, result));
            }
            lastNodeLineCount = filterNode.getOutputRows();
        } else {
            throw new TouchstoneException(String.format(rb.getString("InvalidUnderlyingNode"), headNode.getId()));
        }
        inputNodes.add(headNode);
        for (ExecutionNode executionNode : path.subList(1, path.size())) {
            try {
                lastNodeLineCount = analyzeNode(executionNode, constraintChain, lastNodeLineCount);
                inputNodes.add(executionNode);
                if (lastNodeLineCount == STOP_CONSTRUCT) {
                    if (constraintChain.getNodes().isEmpty()) {
                        return null;
                    } else {
                        break;
                    }
                } else if (lastNodeLineCount < 0) {
                    throw new UnsupportedOperationException();
                }
            } catch (TouchstoneException e) {
                logger.error("extract constraint chain fail", e);
                // 小于设置的阈值以后略去后续的节点
                if (executionNode.getOutputRows() * 1.0 / TableManager.getInstance().getTableSize(executionNode.getTableName()) < skipNodeThreshold) {
                    logger.error(rb.getString("FailToExtractConstraintChain"), e);
                    logger.info(String.format(rb.getString("SkipNodeDueToRatio"), e.getMessage(), executionNode));
                    return constraintChain;
                }
            }
        }
        return constraintChain.getNodes().isEmpty() ? null : constraintChain;
    }


    /**
     * 将树结构根据叶子节点分割为不同的path
     *
     * @param currentNode 需要处理的查询树节点
     * @param paths       需要返回的路径
     */
    private void getPathsIterate(ExecutionNode currentNode, List<List<ExecutionNode>> paths, List<ExecutionNode> currentPath) {
        currentPath.addFirst(currentNode);
        if (currentNode.getLeftNode() == null && currentNode.getRightNode() == null) {
            paths.add(new ArrayList<>(currentPath));
        }
        if (currentNode.getLeftNode() != null) {
            getPathsIterate(currentNode.getLeftNode(), paths, currentPath);
        }
        if (currentNode.getRightNode() != null) {
            getPathsIterate(currentNode.getRightNode(), paths, currentPath);
        }
        currentPath.removeFirst();
    }

    /**
     * 获取查询树的约束链信息和表信息
     *
     * @param query 查询语句
     * @return 该查询树结构出的约束链信息和表信息
     */
    public List<List<ConstraintChain>> extractQuery(String query) throws SQLException {
        List<String[]> queryPlan = dbConnector.explainQuery(query);
        List<List<String[]>> queryPlans = abstractAnalyzer.splitQueryPlan(queryPlan);
        List<ExecutionNode> executionTrees = new LinkedList<>();
        try {
            for (List<String[]> plan : queryPlans) {
                executionTrees.add(abstractAnalyzer.getExecutionTree(plan));
                List<Map.Entry<String, String>> tableNameAndFilterInfos = abstractAnalyzer.splitQueryPlanForMultipleAggregate();
                if (tableNameAndFilterInfos != null) {
                    for (Map.Entry<String, String> tableNameAndFilterInfo : tableNameAndFilterInfos) {
                        executionTrees.add(abstractAnalyzer.getExecutionTree(dbConnector.explainQuery(tableNameAndFilterInfo)));
                    }
                }
            }
        } catch (TouchstoneException | IOException e) {
            if (queryPlan != null && !queryPlan.isEmpty()) {
                String queryPlanContent = queryPlan.stream().map(plan -> String.join("\t", plan))
                        .collect(Collectors.joining(System.lineSeparator()));
                logger.error(rb.getString("FailToExtractQueryTree"));
                logger.error(queryPlanContent, e);
            }
        }
        List<List<ConstraintChain>> constraintChains = new ArrayList<>();
        for (ExecutionNode executionTree : executionTrees) {
            //获取查询树的所有路径
            List<List<ExecutionNode>> paths = new ArrayList<>();
            getPathsIterate(executionTree, paths, new LinkedList<>());
            // 并发处理约束链
            HashSet<ExecutionNode> allNodes = new HashSet<>();
            paths.forEach(allNodes::addAll);
            Set<ExecutionNode> inputNodes = ConcurrentHashMap.newKeySet();
            try (ForkJoinPool forkJoinPool = new ForkJoinPool(paths.size())) {
                constraintChains.add(new ArrayList<>(forkJoinPool.submit(() -> paths.parallelStream().map(path -> {
                    try {
                        return extractConstraintChain(path, inputNodes);
                    } catch (Exception e) {
                        logger.error(path.toString(), e);
                        return null;
                    }
                }).filter(Objects::nonNull).toList()).get()));
            } catch (InterruptedException | ExecutionException e) {
                logger.error(rb.getString("FailToConstructConstraintChain"), e);
                Thread.currentThread().interrupt();
            }
            allNodes.removeAll(inputNodes);
            if (!allNodes.isEmpty()) {
                for (ExecutionNode node : allNodes) {
                    logger.error("can not input {}", node);
                }
            }
        }
        if (constraintChains.size() > 1) {
            for (ConstraintChain constraintChain : constraintChains.getFirst()) {
                for (Parameter parameter : constraintChain.getParameters()) {
                    parameter.setSubPlan(true);
                }
            }
        }
        logger.info(rb.getString("GetComplete"));
        return constraintChains;
    }

    /**
     * 分析select信息
     *
     * @param operatorInfo 需要分析的operator_info
     * @return 分析查询的逻辑树
     * @throws TouchstoneException 分析失败
     */
    private synchronized LogicNode analyzeSelectInfo(String operatorInfo) throws TouchstoneException {
        try {
            return abstractAnalyzer.analyzeSelectOperator(operatorInfo);
        } catch (Exception e) {
            throw new UnsupportedSelect(operatorInfo, e);
        }
    }

}
