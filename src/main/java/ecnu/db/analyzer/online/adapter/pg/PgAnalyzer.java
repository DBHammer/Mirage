package ecnu.db.analyzer.online.adapter.pg;

import ecnu.db.LanguageManager;
import ecnu.db.analyzer.online.AbstractAnalyzer;
import ecnu.db.analyzer.online.adapter.pg.parser.PgSelectOperatorInfoLexer;
import ecnu.db.analyzer.online.adapter.pg.parser.PgSelectOperatorInfoParser;
import ecnu.db.analyzer.online.node.*;
import ecnu.db.generator.constraintchain.filter.LogicNode;
import ecnu.db.schema.ColumnManager;
import ecnu.db.schema.TableManager;
import ecnu.db.utils.CommonUtils;
import ecnu.db.utils.exception.TouchstoneException;
import ecnu.db.utils.exception.schema.CannotFindSchemaException;
import java_cup.runtime.ComplexSymbolFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static ecnu.db.utils.CommonUtils.DECIMAL_DIVIDE_SCALE;
import static ecnu.db.utils.CommonUtils.matchPattern;

public class PgAnalyzer extends AbstractAnalyzer {

    protected static final Logger logger = LoggerFactory.getLogger(PgAnalyzer.class);
    private static final String NUMERIC = "'[0-9]+'::numeric";
    private static final String INTEGER = "'(0|[1-9][0-9]*|-[1-9][0-9]*)'::integer";
    private static final String DATE1 = "[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}";
    private static final String DATE2 = "[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}";
    private static final String DATE3 = "[0-9]{4}-[0-9]{2}-[0-9]{2}";
    private static final String DATE = String.format("'(%s|%s|%s)'::date", DATE1, DATE2, DATE3);
    private static final String TIMESTAMP1 = "[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}";
    private static final String TIMESTAMP2 = "[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}";
    private static final String TIMESTAMP3 = "[0-9]{4}-[0-9]{2}-[0-9]{2}";
    public static final String TIME_OR_DATE = String.format("(%s|%s|%s|%s|%s|%s)", DATE1, DATE2, DATE3, TIMESTAMP1, TIMESTAMP2, TIMESTAMP3);
    private static final String TIMESTAMP = String.format("'(%s|%s|%s)'::timestamp(\\([0-9]+\\))? without time zone", TIMESTAMP1, TIMESTAMP2, TIMESTAMP3);
    private static final Pattern REDUNDANCY = Pattern.compile(INTEGER + "|" + NUMERIC + "|" + DATE + "|" + TIMESTAMP);
    private static final Pattern CanonicalColumnName = Pattern.compile("(([a-zA-Z][a-zA-Z0-9$_]*)|(\"[a-zA-Z][a-zA-Z0-9$_]*\"))\\.((\\w+)|(\"\\w+\"))");
    private static final Pattern FullCanonicalColumnName = Pattern.compile("([a-zA-Z][a-zA-Z0-9$_]*)\\.(([a-zA-Z][a-zA-Z0-9$_]*)|(\"[a-zA-Z][a-zA-Z0-9$_]*\"))\\.\\w+");
    private static final Pattern JOIN_EQ_OPERATOR = Pattern.compile("Cond: \\(.*\\)");
    private static final Pattern EQ_OPERATOR = Pattern.compile("\\(([a-zA-Z0-9_$]+\\.[a-zA-Z0-9_$]+\\.[a-zA-Z0-9_$]+) = ([a-zA-Z0-9_$]+\\.[a-zA-Z0-9_$]+\\.[a-zA-Z0-9_$]+)\\)");
    private static final Pattern HASH_SUB_PLAN = Pattern.compile("\\(NOT \\(hashed SubPlan \\d+\\)\\)");
    private static final Pattern SUB_QUERY = Pattern.compile("(\\()(\\s)*(SELECT)(.+)(FROM)(.+)(\\))");
    private final PgSelectOperatorInfoParser parser = new PgSelectOperatorInfoParser(new PgSelectOperatorInfoLexer(new StringReader("")), new ComplexSymbolFactory());
    public StringBuilder pathForSplit = null;
    private final ResourceBundle rb = LanguageManager.getInstance().getRb();

    public PgAnalyzer() {
        super();
        this.nodeTypeRef = new PgNodeTypeInfo();
    }

    @Override
    public ExecutionNode getExecutionTree(List<String[]> queryPlans) throws TouchstoneException, IOException, SQLException {
        String queryPlan = queryPlans.stream().map(queryPlanLine -> queryPlanLine[0]).collect(Collectors.joining());
        PgJsonReader.setReadContext(queryPlan);
        if (queryPlan.contains("= subquery")) {
            transformHashJoin2AggForOpenGauss(queryPlan);
        }
        return getExecutionTreeRes(PgJsonReader.skipNodes(PgJsonReader.getRootPath()));
    }

    public void transformHashJoin2AggForOpenGauss(String queryPlan) {
        StringBuilder subQueryJoinNodePath = getJoinNodeWithSubQuery(PgJsonReader.skipNodes(PgJsonReader.getRootPath()));
        StringBuilder leftChildNode = PgJsonReader.skipNodes(PgJsonReader.move2LeftChild(subQueryJoinNodePath));
        PgJsonReader.deleteOutPut();
        String leftPlan = PgJsonReader.readPlan(leftChildNode, 0) + "," + PgJsonReader.readPlan(leftChildNode, 1);
        String rightPlan = PgJsonReader.readPlan(subQueryJoinNodePath, 1);
        PgJsonReader.setReadContext(queryPlan);
        if (rightPlan.contains(leftPlan)) {
            PgJsonReader.deleteTree(leftChildNode);
        }
    }

    public StringBuilder getJoinNodeWithSubQuery(StringBuilder currentNode) {
        if (PgJsonReader.readNodeType(currentNode).equals("Hash Join") &&
                PgJsonReader.readJoinCond(currentNode).contains("= subquery")) {
            return currentNode;
        }
        int plansCount = PgJsonReader.readPlansCount(currentNode);
        if (plansCount == 0) {
            return null;
        } else {
            StringBuilder leftChildPath = PgJsonReader.skipNodes(PgJsonReader.move2LeftChild(currentNode));
            StringBuilder leftNodePath = getJoinNodeWithSubQuery(leftChildPath);
            if (leftNodePath == null && plansCount > 1) {
                StringBuilder rightChildPath = PgJsonReader.skipNodes(PgJsonReader.move2RightChild(currentNode));
                leftNodePath = getJoinNodeWithSubQuery(rightChildPath);
            }
            return leftNodePath;
        }
    }

    public ExecutionNode getExecutionTreeRes(StringBuilder currentNodePath) throws TouchstoneException, IOException, SQLException {
        ExecutionNode leftNode = null;
        ExecutionNode rightNode = null;
        int plansCount = PgJsonReader.readPlansCount(currentNodePath);
        if (plansCount >= 2) {
            StringBuilder leftChildPath = PgJsonReader.skipNodes(PgJsonReader.move2LeftChild(currentNodePath));
            leftNode = getExecutionTreeRes(leftChildPath);
            StringBuilder rightChildPath = PgJsonReader.skipNodes(PgJsonReader.move2RightChild(currentNodePath));
            rightNode = getExecutionTreeRes(rightChildPath);
        } else if (plansCount == 1) {
            //todo fix only for query 20
            if (canNotDeal(currentNodePath)) {
                pathForSplit = currentNodePath;
                String tableName = PgJsonReader.readTableName(currentNodePath.toString());
                long tableSize = TableManager.getInstance().getTableSize(tableName);
                aliasDic.put(PgJsonReader.readAlias(currentNodePath.toString()), tableName);
                ExecutionNode subNode = new FilterNode(currentNodePath.toString(), tableSize, null);
                subNode.setTableName(tableName);
                return subNode;
            }
            StringBuilder leftChildPath = PgJsonReader.skipNodes(PgJsonReader.move2LeftChild(currentNodePath));
            leftNode = getExecutionTreeRes(leftChildPath);
            rightNode = transferSubPlan2AntiJoin(currentNodePath);
        }
        ExecutionNode node = getExecutionNode(currentNodePath);
        if (node == null) {
            return null;
        }
        node.setLeftNode(leftNode);
        node.setRightNode(rightNode);
        if (node.getType() == ExecutionNodeType.JOIN) {
            if (rightNode == null) {
                // fix for opengauss
                logger.info("generate agg from hash join for opengauss");
                node = leftNode;
            } else if (rightNode.getType() == ExecutionNodeType.FILTER && rightNode.getInfo() != null && ((FilterNode) rightNode).isIndexScan()) {
                long rowsRemoveByFilterAfterJoin = PgJsonReader.readRowsRemoved(PgJsonReader.skipNodes(PgJsonReader.move2RightChild(currentNodePath)));
                ((JoinNode) node).setRowsRemoveByFilterAfterJoin(rowsRemoveByFilterAfterJoin);
                String indexJoinFilter = PgJsonReader.readFilterInfo(PgJsonReader.skipNodes(PgJsonReader.move2RightChild(currentNodePath)));
                ((JoinNode) node).setIndexJoinFilter(removeRedundancy(indexJoinFilter, true));
            }
        }
        //create agg node
        if (plansCount == 3) {
            StringBuilder thirdChildPath = PgJsonReader.skipNodes(PgJsonReader.move3ThirdChild(currentNodePath));
            ExecutionNode parentAggNode = createParentAggNode(currentNodePath, thirdChildPath);
            int rowCount = PgJsonReader.readRowCount(currentNodePath) + PgJsonReader.readRowsRemovedByJoinFilter(currentNodePath);
            if (node != null) {
                node.setOutputRows(rowCount);
            }
            parentAggNode.setLeftNode(node);
            node = parentAggNode;
        }
        //todo fix only for query 20
        if (pathForSplit != null) {
            if (PgJsonReader.move2LeftChild(currentNodePath).toString().contentEquals(pathForSplit) ||
                    PgJsonReader.move2RightChild(currentNodePath).toString().contentEquals(pathForSplit)) {
                node.setOutputRows(PgJsonReader.readActualLoops(PgJsonReader.move2LeftChild(pathForSplit)));
            }
        }
        return node;
    }


    private ExecutionNode transferSubPlan2AntiJoin(StringBuilder path) {
        //todo multiple subPlans
        String filterInfo = PgJsonReader.readFilterInfo(path);
        if (nodeTypeRef.isFilterNode(PgJsonReader.readNodeType(path)) && filterInfo != null) {
            Matcher notHashSubPlan = HASH_SUB_PLAN.matcher(filterInfo);
            if (notHashSubPlan.find()) {
                int count = 1;
                while (notHashSubPlan.find()) {
                    count++;
                }
                if (count == 1) {
                    String tableName = PgJsonReader.readTableName(path.toString());
                    aliasDic.put(PgJsonReader.readAlias(path.toString()), tableName);
                    int outPutCount = PgJsonReader.readRowCount(path);
                    int removedCount = PgJsonReader.readRowsRemoved(path);
                    int rowCount = outPutCount + removedCount;
                    StringBuilder rightPath = PgJsonReader.move2RightChild(path);
                    FilterNode currentRightNode = new FilterNode(rightPath.toString(), rowCount, null);
                    currentRightNode.setTableName(tableName);
                    currentRightNode.setAdd();
                    return currentRightNode;
                } else {
                    throw new UnsupportedOperationException();
                }
            } else {
                return null;
            }
        }
        return null;
    }


    private ExecutionNode getFilterNode(StringBuilder path, long rowCount) throws CannotFindSchemaException {
        String planId = path.toString();
        String filterInfo = PgJsonReader.readFilterInfo(path);
        if (filterInfo != null && filterInfo.contains("(NOT (hashed SubPlan")) {
            Matcher hashSubPlan = HASH_SUB_PLAN.matcher(filterInfo);
            int count = 0;
            while (hashSubPlan.find()) {
                count++;
            }
            if (count == 1) {
                return transferFilter2AntiJoin(path, rowCount);
            } else {
                throw new UnsupportedOperationException();
            }
        } else {
            String tableName = PgJsonReader.readTableName(path.toString());
            aliasDic.put(PgJsonReader.readAlias(path.toString()), tableName);
            FilterNode node = new FilterNode(planId, rowCount, transColumnName(filterInfo));
            node.setTableName(tableName);
            if (nodeTypeRef.isIndexScanNode(PgJsonReader.readNodeType(path))) {
                if (filterInfo == null) {
                    node.setOutputRows(TableManager.getInstance().getTableSize(tableName));
                } else {
                    node.setIndexScan(true);
                    node.setFilterInfoWithQuote(transColumnName(removeRedundancy(filterInfo, true)));
                }
            }
            return node;
        }
    }

    private ExecutionNode transferFilter2AntiJoin(StringBuilder path, long rowCount) {
        StringBuilder leftNodePath = PgJsonReader.move2LeftChild(path);
        List<String> leftNodeResult = PgJsonReader.readOutput(leftNodePath);
        List<String> outPut = PgJsonReader.readOutput(path);
        String joinInfo = "";
        for (String s : leftNodeResult) {
            String antiJoinTable1 = s.split("\\.")[0];
            String antiJoinKey1 = s.split("\\.")[1];
            String joinColumn1 = antiJoinKey1.split("_")[1];
            for (String value : outPut) {
                String antiJoinKey2 = value.split("\\.")[1];
                String antiJoinTable2 = value.split("\\.")[0];
                String joinColumn2 = antiJoinKey2.split("_")[1];
                if (joinColumn1.equals(joinColumn2)) {
                    joinInfo = antiJoinTable2 + "." + antiJoinKey2 + " = " + antiJoinTable1 + "." + antiJoinKey1;
                }
            }
        }
        joinInfo = "Hash Cond: " + "(" + joinInfo + ")";
        return new JoinNode(path.toString(), rowCount, joinInfo, true, false, BigDecimal.ZERO);
    }

    private ExecutionNode getJoinNode(StringBuilder path, int rowCount) {
        String joinInfo = switch (PgJsonReader.readNodeType(path)) {
            case "Hash Join" -> PgJsonReader.readHashJoin(path);
            case "Nested Loop" -> PgJsonReader.readIndexJoin(path);
            case "Merge Join" -> PgJsonReader.readMergeJoin(path);
            default -> throw new UnsupportedOperationException();
        };
        if (joinInfo.equals("needReadDeep")) {
            joinInfo = readDeep(path);
            System.out.println(joinInfo);
        }
        BigDecimal pkDistinctProbability = BigDecimal.ZERO;
        if (PgJsonReader.isOutJoin(path)) {
            StringBuilder leftChildPath = PgJsonReader.skipNodes(PgJsonReader.move2LeftChild(path));
            StringBuilder rightChildPath = PgJsonReader.skipNodes(PgJsonReader.move2RightChild(path));
            int pkRowCount, fkRowCount;
            if (PgJsonReader.isRightOuterJoin(path)) {
                pkRowCount = PgJsonReader.readRowCount(rightChildPath);
                fkRowCount = PgJsonReader.readRowCount(leftChildPath);
            } else if (PgJsonReader.isLeftOuterJoin(path)) {
                fkRowCount = PgJsonReader.readRowCount(rightChildPath);
                pkRowCount = PgJsonReader.readRowCount(leftChildPath);
            } else {
                throw new UnsupportedOperationException();
            }
            pkDistinctProbability = BigDecimal.valueOf(pkRowCount + fkRowCount - rowCount)
                    .divide(BigDecimal.valueOf(fkRowCount), DECIMAL_DIVIDE_SCALE, RoundingMode.HALF_UP);
            rowCount = fkRowCount;
        } else if (PgJsonReader.isAntiJoin(path)) {
            StringBuilder leftChildPath = PgJsonReader.skipNodes(PgJsonReader.move2LeftChild(path));
            rowCount = PgJsonReader.readRowCount(leftChildPath) - rowCount;
        }
        boolean isSemiJoin = PgJsonReader.isAntiJoin(path) || PgJsonReader.isSemiJoin(path);
        return new JoinNode(path.toString(), rowCount, joinInfo, PgJsonReader.isAntiJoin(path), isSemiJoin, pkDistinctProbability);
    }

    String readDeep(StringBuilder path) {
        String currentJoinCond = null;
        StringBuilder leftChildPath = PgJsonReader.skipNodes(PgJsonReader.move2LeftChild(path));
        StringBuilder rightChildPath = PgJsonReader.skipNodes(PgJsonReader.move2RightChild(path));
        String leftType = PgJsonReader.readNodeType(leftChildPath);
        String rightType = PgJsonReader.readNodeType(rightChildPath);
        if (rightType.equals("Nested Loop")) {
            String joinCond = PgJsonReader.readIndexJoin(rightChildPath);
            if (joinCond.equals("needReadDeep")) {
                currentJoinCond = readDeep(rightChildPath);
            } else {
                joinCond = joinCond.replace("Index Cond: (", "");
                joinCond = joinCond.substring(0, (joinCond.length() - 1));
                String table1 = PgJsonReader.readTableName(PgJsonReader.skipNodes(PgJsonReader.move2LeftChild(rightChildPath)).toString()).split("\\.")[1];
                String table2 = PgJsonReader.readTableName(PgJsonReader.skipNodes(PgJsonReader.move2RightChild(rightChildPath)).toString()).split("\\.")[1];
                List<String> joinCondList = List.of(joinCond.split("AND"));
                for (String eachCond : joinCondList) {
                    if (!eachCond.contains(table1) || !eachCond.contains(table2)) {
                        currentJoinCond = eachCond;
                    }
                }
            }
        } else if (leftType.equals("Nested Loop")) {
            String joinCond = PgJsonReader.readIndexJoin(leftChildPath);
            if (joinCond.equals("needReadDeep")) {
                currentJoinCond = readDeep(leftChildPath);
            } else {
                String table1 = PgJsonReader.readTableName(PgJsonReader.skipNodes(PgJsonReader.move2LeftChild(leftChildPath)).toString()).split("\\.")[1];
                String table2 = PgJsonReader.readTableName(PgJsonReader.skipNodes(PgJsonReader.move2RightChild(leftChildPath)).toString()).split("\\.")[1];
                List<String> joinCondList = List.of(joinCond.split("AND"));
                for (String eachCond : joinCondList) {
                    if (!eachCond.contains(table1) || !eachCond.contains(table2)) {
                        currentJoinCond = eachCond;
                    }
                }
            }
        } else {
            throw new UnsupportedOperationException();
        }
        assert currentJoinCond != null;
        if (!currentJoinCond.contains("Index Cond:")) {
            currentJoinCond = "Index Cond:" + currentJoinCond;
        }
        return currentJoinCond;
    }

    private ExecutionNode getAggregationNode(StringBuilder path, int rowCount) {
        List<String> groupKey = PgJsonReader.readGroupKey(path);
        String groupKeyInfo = null;
        String tableName = null;
        String aggFilterInfo = PgJsonReader.readFilterInfo(path);
        FilterNode aggFilter = null;
        if (groupKey != null) {
            //todo multiple table name
            groupKeyInfo = groupKey.stream().map(this::transColumnName).collect(Collectors.joining(";"));
            String[] splitColumns = groupKey.get(0).split("\\.");
            if (splitColumns.length == 2) {
                tableName = aliasDic.get(splitColumns[0]);
            } else {
                tableName = splitColumns[0] + "." + splitColumns[1];
            }
            if (aggFilterInfo != null) {
                aggFilter = new FilterNode(path.toString(), rowCount, transColumnName(aggFilterInfo));
                rowCount += PgJsonReader.readRowsRemoved(path);
            }
        } else {
            String subPlanIndex = PgJsonReader.readSubPlanIndex(path);
            if (aggFilterInfo == null && subPlanIndex != null) {
                aggFilterInfo = "(" + removeRedundancy(PgJsonReader.readOutput(path).get(0), false) + "=" + subPlanIndex + ")";
                aggFilter = new FilterNode(path.toString(), 1, transColumnName(aggFilterInfo));
                tableName = getTableNameFromOutput(path);
            }
        }
        AggNode node = new AggNode(path.toString(), rowCount, groupKeyInfo);
        node.setTableName(tableName);
        node.setAggFilter(aggFilter);
        return node;
    }

    private ExecutionNode createParentAggNode(StringBuilder parentPath, StringBuilder aggPath) throws TouchstoneException, IOException, SQLException {
        int rowCount;
        int rowsAfterFilter;
        String joinCond = PgJsonReader.readJoinCond(parentPath);
        String leftJoinCond = joinCond.split("=")[0];
        List<String> groupKey = new ArrayList<>();
        groupKey.add(leftJoinCond.substring(1));
        String[] outPut = PgJsonReader.readOutput(aggPath).get(0).split("\\.");
        String tableName = outPut[outPut.length - 2];
        tableName = tableName.replaceAll(".*\\(", "");
        tableName = tableName.split("_")[0];
        tableName = aliasDic.get(tableName);
        getExecutionTreeRes(aggPath);
        String aggFilterInfo = PgJsonReader.readJoinFilter(parentPath);
        if (aggFilterInfo != null) {
            String aggOutPut = PgJsonReader.readOutput(aggPath).get(0);
            aggFilterInfo = aggFilterInfo.replace("(SubPlan 1)", aggOutPut);
            rowsAfterFilter = PgJsonReader.readRowCount(parentPath);
            if (!aggOutPut.toLowerCase(Locale.ROOT).contains("min") && !aggOutPut.toLowerCase(Locale.ROOT).contains("max")) {
                rowCount = PgJsonReader.readAggGroup(aggPath);
            } else {
                rowCount = rowsAfterFilter;
            }
        } else {
            throw new UnsupportedOperationException();
        }
        groupKey = groupKey.stream().map(this::transColumnName).toList();
        AggNode node = new AggNode(aggPath.toString(), rowCount, String.join(";", groupKey));
        node.setAggFilter(new FilterNode(aggPath.toString(), rowsAfterFilter, transColumnName(aggFilterInfo)));
        node.setTableName(tableName);
        return node;
    }

    private ExecutionNode getExecutionNode(StringBuilder path) throws TouchstoneException {
        String nodeType = PgJsonReader.readNodeType(path);
        if (nodeType == null) {
            return null;
        }
        int rowCount = PgJsonReader.readRowCount(path);
        if (nodeTypeRef.isFilterNode(nodeType)) {
            return getFilterNode(path, rowCount);
        } else if (nodeTypeRef.isJoinNode(nodeType)) {
            return getJoinNode(path, rowCount);
        } else if (nodeTypeRef.isAggregateNode(nodeType)) {
            return getAggregationNode(path, rowCount);
        } else {
            throw new UnsupportedOperationException();
        }
    }

    public String transColumnName(String filterInfo) {
        if (filterInfo == null) {
            return null;
        }
        // todo: delete if open-gauss fix the output bug
        StringBuilder tmpStr = new StringBuilder();
        Matcher matcherOnlyForOpenGauss = FullCanonicalColumnName.matcher(filterInfo);
        while (matcherOnlyForOpenGauss.find()) {
            String[] tableNameAndColName = matcherOnlyForOpenGauss.group().split("\\.");
            aliasDic.put(tableNameAndColName[1], tableNameAndColName[0] + "." + tableNameAndColName[1]);
            matcherOnlyForOpenGauss.appendReplacement(tmpStr, tableNameAndColName[1] + "." + tableNameAndColName[2]);
        }
        matcherOnlyForOpenGauss.appendTail(tmpStr);

        String[] splitResults = tmpStr.toString().split("'");
        for (int i = 0; i < splitResults.length; i++) {
            if (i % 2 == 0) {
                StringBuilder filter = new StringBuilder();
                Matcher m = CanonicalColumnName.matcher(splitResults[i]);
                while (m.find()) {
                    String[] tableNameAndColName = m.group().split("\\.");
                    m.appendReplacement(filter, aliasDic.get(tableNameAndColName[0].replace("\"", "")) + "." + tableNameAndColName[1].replace("\"", ""));
                }
                m.appendTail(filter);
                splitResults[i] = filter.toString();
            }
        }
        return removeRedundancy(String.join("'", splitResults), false);
    }

    public String removeRedundancy(String filterInfo, boolean keepQuotes) {
        int filterLocation = keepQuotes ? 0 : 1;
        Matcher m = REDUNDANCY.matcher(filterInfo);
        StringBuilder filter = new StringBuilder();
        while (m.find()) {
            String date = m.group().split("::")[0];
            m.appendReplacement(filter, date.substring(filterLocation, date.length() - filterLocation));
        }
        m.appendTail(filter);
        return filter.toString();
    }

    @Override
    public double analyzeJoinInfo(String joinInfo, String[] result) {
        joinInfo = transColumnName(joinInfo);
        Matcher eqCondition = JOIN_EQ_OPERATOR.matcher(joinInfo);
        double filterProbability = 1;
        if (eqCondition.find()) {
            if (eqCondition.groupCount() > 1) {
                throw new UnsupportedOperationException();
            }
            List<List<String>> matches = matchPattern(EQ_OPERATOR, joinInfo);
            String[] leftJoinInfos = matches.getFirst().get(1).split("\\.");
            String[] rightJoinInfos = matches.getFirst().get(2).split("\\.");
            String leftTable = leftJoinInfos[0] + CommonUtils.CANONICAL_NAME_CONTACT_SYMBOL + leftJoinInfos[1];
            String rightTable = rightJoinInfos[0] + CommonUtils.CANONICAL_NAME_CONTACT_SYMBOL + rightJoinInfos[1];
            List<String> leftCols = new ArrayList<>();
            List<String> rightCols = new ArrayList<>();
            for (List<String> match : matches) {
                leftJoinInfos = match.get(1).split("\\.");
                rightJoinInfos = match.get(2).split("\\.");
                String currLeftTable = String.format("%s.%s", leftJoinInfos[0], leftJoinInfos[1]);
                String currLeftCol = leftJoinInfos[2];
                String currRightTable = String.format("%s.%s", rightJoinInfos[0], rightJoinInfos[1]);
                String currRightCol = rightJoinInfos[2];
                if (!leftTable.equals(currLeftTable) || !rightTable.equals(currRightTable)) {
                    double tmpFilterProbability = computeMatchProbability(String.join(".", leftJoinInfos), String.join(".", rightJoinInfos));
                    logger.info("deal with multiple fks {}", tmpFilterProbability);
                    filterProbability *= tmpFilterProbability;
                    break;
                }
                leftCols.add(currLeftCol);
                rightCols.add(currRightCol);
            }
            String leftCol = String.join(",", leftCols);
            String rightCol = String.join(",", rightCols);
            result[0] = leftTable;
            result[1] = leftCol;
            result[2] = rightTable;
            result[3] = rightCol;
        }
        return filterProbability;
    }


    public double computeMatchProbability(String leftColName, String rightColName) {
        boolean withTheSameColumnType = ColumnManager.getInstance().getColumnType(leftColName)
                .equals(ColumnManager.getInstance().getColumnType(rightColName));
        boolean withTheSameSpecialValue = ColumnManager.getInstance().getColumn(leftColName).getSpecialValue() ==
                ColumnManager.getInstance().getColumn(rightColName).getSpecialValue();
        boolean withTheSameStart = ColumnManager.getInstance().getMin(leftColName) ==
                ColumnManager.getInstance().getMin(rightColName);
        boolean withTheSameRange = ColumnManager.getInstance().getNdv(leftColName) ==
                ColumnManager.getInstance().getNdv(rightColName);
        if (withTheSameColumnType && withTheSameRange && withTheSameSpecialValue && withTheSameStart) {
            logger.warn("infer {} and {} reference the same primary key column", leftColName, rightColName);
            return 1.0 / ColumnManager.getInstance().getNdv(leftColName);
        } else {
            logger.error("infer {} and {} may not reference the same primary key column", leftColName, rightColName);
            return 1.0;
        }
    }

    @Override
    public List<List<String[]>> splitQueryPlan(List<String[]> queryPlan) {
        String queryPlanString = queryPlan.stream().map(queryPlanLine -> queryPlanLine[0]).collect(Collectors.joining());
        PgJsonReader.setReadContext(queryPlanString);
        String queryPlanMainTree = PgJsonReader.readTheWholePlan();
        StringBuilder path = PgJsonReader.getRootPath();
        if (PgJsonReader.hasInitPlan(path)) {
            List<List<String[]>> queryPlans = new LinkedList<>();
            for (int i = 0; i < PgJsonReader.readPlansCount(path); i++) {
                String subPlanName = PgJsonReader.readSubPlanIndex(path, i);
                if (subPlanName != null) {
                    String subQueryPlan = PgJsonReader.readPlan(path, i);
                    if (i == PgJsonReader.readPlansCount(path) - 1) {
                        queryPlanMainTree = queryPlanMainTree.replace(subQueryPlan, "");
                    } else {
                        queryPlanMainTree = queryPlanMainTree.replace(subQueryPlan + ",", "");
                    }
                    queryPlans.add(Collections.singletonList(new String[]{PgJsonReader.formatPlan(subQueryPlan)}));
                }
            }
            queryPlans.add(Collections.singletonList(new String[]{queryPlanMainTree}));
            return queryPlans;
        } else {
            return Collections.singletonList(queryPlan);
        }
    }

    @Override
    public List<Map.Entry<String, String>> splitQueryPlanForMultipleAggregate() {
        if (pathForSplit == null) {
            return null;
        } else {
            List<Map.Entry<String, String>> tableNameAndFilterInfo = new LinkedList<>();
            StringBuilder path = PgJsonReader.move2LeftChild(PgJsonReader.move2LeftChild(pathForSplit));
            String tableName = PgJsonReader.readTableName(path.toString()).split("\\.")[1];
            String filterInfo = removeRedundancy(PgJsonReader.readFilterInfo(path), true);
            tableNameAndFilterInfo.add(new AbstractMap.SimpleEntry<>(tableName, filterInfo));
            pathForSplit = null;
            return tableNameAndFilterInfo;
        }
    }

    public boolean canNotDeal(StringBuilder path) throws SQLException, TouchstoneException, IOException {
        String nodeType = PgJsonReader.readNodeType(path);
        StringBuilder leftPath = PgJsonReader.move2LeftChild(path);
        String leftNodeType = PgJsonReader.readNodeType(leftPath);
        String tableName = PgJsonReader.readTableName(path.toString()).split("\\.")[1];
        if (nodeTypeRef.isAggregateNode(leftNodeType) && nodeTypeRef.isIndexScanNode(nodeType)) {
            logger.error("cannot deal with {}", path);
            getExecutionTreeRes(PgJsonReader.move2LeftChild(leftPath));
            return !tableName.equals(getTableNameFromOutput(leftPath));
        } else {
            return false;
        }
    }

    private String getTableNameFromOutput(StringBuilder path) {
        String outPut = PgJsonReader.readOutput(path).getFirst();
        Set<String> tableNames = aliasDic.keySet().stream().filter(outPut::contains)
                .map(alias -> aliasDic.get(alias)).collect(Collectors.toSet());
        if (tableNames.size() > 1) {
            logger.error(rb.getString("CannotRecognizeMultipleTables"));
            return null;
        } else {
            return tableNames.iterator().next();
        }
    }

    @Override
    public LogicNode analyzeSelectOperator(String operatorInfo) throws Exception {
        return parser.parseSelectOperatorInfo(operatorInfo);
    }
}
