package ecnu.db.analyzer.online.adapter.tidb;

import ecnu.db.analyzer.online.AbstractAnalyzer;
import ecnu.db.analyzer.online.adapter.tidb.parser.TidbSelectOperatorInfoLexer;
import ecnu.db.analyzer.online.adapter.tidb.parser.TidbSelectOperatorInfoParser;
import ecnu.db.analyzer.online.node.ExecutionNode;
import ecnu.db.analyzer.online.node.FilterNode;
import ecnu.db.analyzer.online.node.JoinNode;
import ecnu.db.generator.constraintchain.filter.LogicNode;
import ecnu.db.schema.TableManager;
import ecnu.db.utils.exception.TouchstoneException;
import ecnu.db.utils.exception.analyze.UnsupportedJoin;
import ecnu.db.utils.exception.analyze.UnsupportedSelectionConditionException;
import java_cup.runtime.ComplexSymbolFactory;

import java.io.StringReader;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ecnu.db.utils.CommonUtils.CANONICAL_NAME_SPLIT_REGEX;
import static ecnu.db.utils.CommonUtils.matchPattern;

/**
 * @author qingshuai.wang
 */
public class TidbAnalyzer extends AbstractAnalyzer {


    private static final Pattern ROW_COUNTS = Pattern.compile("rows:[0-9]+");
    private static final Pattern ITER = Pattern.compile("iters:([0-9]+)");
    private static final Pattern INNER_JOIN_OUTER_KEY = Pattern.compile("outer key:(.+),");
    private static final Pattern INNER_JOIN_INNER_KEY = Pattern.compile("inner key:(.+)");
    private static final Pattern JOIN_EQ_OPERATOR = Pattern.compile("(equal:\\[.*]|equal cond:)");
    private static final Pattern PLAN_ID = Pattern.compile("([a-zA-Z]+_[0-9]+)");
    private static final Pattern EQ_OPERATOR = Pattern.compile("eq\\(([a-zA-Z0-9_$]+\\.[a-zA-Z0-9_$]+\\.[a-zA-Z0-9_$]+), ([a-zA-Z0-9_$]+\\.[a-zA-Z0-9_$]+\\.[a-zA-Z0-9_$]+)\\)");
    private static final Pattern INNER_JOIN = Pattern.compile("(inner join)|(semi join)");
    private static final Pattern RANGE = Pattern.compile("range.+(?=, keep order)");
    private static final Pattern LEFT_RANGE_BOUND = Pattern.compile("([\\[(])([0-9.]+|\\+inf|-inf)");
    private static final Pattern RIGHT_RANGE_BOUND = Pattern.compile("([0-9.]+|\\+inf|-inf)([])])");
    private static final Pattern INDEX_COLUMN = Pattern.compile("index:.+\\((.+)\\)");
    private static final Pattern RANGE_SCAN_JOIN_PUSHDOWN = Pattern.compile("range: decided by \\[[a-zA-Z0-9_$]+\\.[a-zA-Z0-9_$]+\\.[a-zA-Z0-9_$]+(, [a-zA-Z0-9_$]+\\.[a-zA-Z0-9_$]+\\.[a-zA-Z0-9_$]+)*]");
    private final TidbSelectOperatorInfoParser parser = new TidbSelectOperatorInfoParser(new TidbSelectOperatorInfoLexer(new StringReader("")), new ComplexSymbolFactory());

    public TidbAnalyzer() {
        super();
        this.nodeTypeRef = new TidbNodeTypeTool();
    }

    @Override
    public ExecutionNode getExecutionTree(List<String[]> queryPlan) throws TouchstoneException {
        RawNode rawNodeRoot = buildRawNodeTree(queryPlan);
        return buildExecutionTree(rawNodeRoot);
    }

    /**
     * TODO 支持 semi join, outer join
     * 合并节点，删除query plan中不需要或者不支持的节点，并根据节点类型提取对应信息
     * 关于join下推到tikv节点的处理:
     * 1. 有selection的下推
     * ***********************************************************************
     * IndexJoin                                       Filter      *
     * /       \                                         /         *
     * leftNode    IndexLookup              ===>>>          Join        *
     * /         \                           /   \        *
     * RangeScan     Selection              leftNode  Scan     *
     * /                                        *
     * Scan                                       *
     * ***********************************************************************
     * <p>
     * 2. 没有selection的下推(leftNode中有Selection节点)
     * ***********************************************************************
     * IndexJoin                                       Join        *
     * /       \                                      /    \       *
     * leftNode    IndexLookup              ===>>>   leftNode   Scan    *
     * /        \                                           *
     * RangeScan       Scan                                         *
     * ***********************************************************************
     * <p>
     * 3. 没有selection的下推(leftNode中没有Selection节点，但右边扫描节点上有索引)
     * ***********************************************************************
     * IndexJoin                                        Join        *
     * /       \                                       /    \       *
     * leftNode   IndexReader              ===>>>    leftNode   Scan    *
     * /                                                    *
     * RangeScan                                                  *
     * ***********************************************************************
     *
     * @param rawNode 需要处理的query plan树
     * @return 处理好的树
     * @throws TouchstoneException 构建查询树失败
     */
    private ExecutionNode buildExecutionTree(RawNode rawNode) throws TouchstoneException {
        if (rawNode == null) {
            return null;
        }
        String nodeType = rawNode.nodeType;
        if (nodeTypeRef.isPassNode(nodeType)) {
            return rawNode.left == null ? null : buildExecutionTree(rawNode.left);
        }
        ExecutionNode node;
        List<List<String>> matches = matchPattern(RANGE_SCAN_JOIN_PUSHDOWN, rawNode.operatorInfo);
        // 处理range scan
        if (nodeTypeRef.isRangeScanNode(nodeType) && matches.isEmpty()) {
            String canonicalTableName = rawNode.operatorInfo;
            long tableSize = TableManager.getInstance().getTableSize(canonicalTableName);
            // 含有decided by的operator info表示join的index range scan
            if (tableSize != rawNode.rowCount && !rawNode.operatorInfo.contains("decided by")) {
                String rangeInfo = matchPattern(RANGE, rawNode.operatorInfo).get(0).get(0);
                List<List<String>> leftRangeMatches = matchPattern(LEFT_RANGE_BOUND, rangeInfo);
                List<List<String>> rightRangeMatches = matchPattern(RIGHT_RANGE_BOUND, rangeInfo);
                // 目前只支持含有一个range的情况
                if (leftRangeMatches.size() != 1 || rightRangeMatches.size() != 1 || leftRangeMatches.get(0).size() != 3 || rightRangeMatches.get(0).size() != 3) {
                    throw new UnsupportedSelectionConditionException(rawNode.operatorInfo);
                }
                String leftOperator = "(".equals(leftRangeMatches.get(0).get(1)) ? "gt" : "ge", leftOperand = leftRangeMatches.get(0).get(2);
                String rightOperator = ")".equals(rightRangeMatches.get(0).get(2)) ? "lt" : "le", rightOperand = rightRangeMatches.get(0).get(1);
                List<List<String>> indexMatches = matchPattern(INDEX_COLUMN, rawNode.operatorInfo);
                String columnName = TableManager.getInstance().getPrimaryKeys(canonicalTableName);
                if (!indexMatches.isEmpty()) {
                    columnName = indexMatches.get(0).get(1);
                }
                if (leftOperand.contains("inf")) {
                    return new FilterNode(rawNode.id, rawNode.rowCount,
                            String.format("%s(%s.%s, %s)", rightOperator, canonicalTableName, columnName, rightOperand));
                } else if (rightOperand.contains("inf")) {
                    return new FilterNode(rawNode.id, rawNode.rowCount,
                            String.format("%s(%s.%s, %s)", leftOperator, canonicalTableName, columnName, leftOperand));
                } else {
                    return new FilterNode(rawNode.id, rawNode.rowCount,
                            String.format("and(%s(%s.%s, %s), %s(%s.%s, %s))",
                                    leftOperator, canonicalTableName, columnName, leftOperand,
                                    rightOperator, canonicalTableName, columnName, rightOperand));
                }
            } else {
                throw new UnsupportedSelectionConditionException(rawNode.operatorInfo);
            }
        } else if (nodeTypeRef.isRangeScanNode(nodeType) && !matches.isEmpty()) {
            String canonicalTableName = rawNode.operatorInfo;
            long rowCount = TableManager.getInstance().getTableSize(canonicalTableName);
            ExecutionNode scanNode = new FilterNode(rawNode.id, rowCount, null);
            scanNode.setTableName(canonicalTableName);
            return scanNode;
        }
        // 处理底层的TableScan
        else if (nodeTypeRef.isTableScanNode(nodeType)) {
            String canonicalTableName = rawNode.operatorInfo;
            ExecutionNode scanNode = new FilterNode(rawNode.id, rawNode.rowCount, null);
            scanNode.setTableName(canonicalTableName);
            return scanNode;
        } else if (nodeTypeRef.isFilterNode(nodeType)) {
            node = new FilterNode(rawNode.id, rawNode.rowCount, rawNode.operatorInfo);
            // 跳过底部的TableScan
            if (rawNode.left != null && nodeTypeRef.isTableScanNode(rawNode.left.nodeType)) {
                return node;
            } else if (rawNode.left != null && nodeTypeRef.isRangeScanNode(rawNode.left.nodeType)) {
                matches = matchPattern(RANGE_SCAN_JOIN_PUSHDOWN, rawNode.left.operatorInfo);
                if (!matches.isEmpty()) {
                    return node;
                }
            }
            node.setLeftNode(rawNode.left == null ? null : buildExecutionTree(rawNode.left));
            node.setRightNode(rawNode.right == null ? null : buildExecutionTree(rawNode.right));
        } else if (nodeTypeRef.isJoinNode(nodeType)) {
            // 处理IndexJoin有selection的下推到tikv情况
            if (nodeTypeRef.isReaderNode(rawNode.right.nodeType) && rawNode.right.right != null
                    && nodeTypeRef.isIndexScanNode(rawNode.right.left.nodeType)
                    && nodeTypeRef.isFilterNode(rawNode.right.right.nodeType)) {
                node = new FilterNode(rawNode.right.right.id, rawNode.rowCount, rawNode.right.right.operatorInfo);
                node.setLeftNode(new JoinNode(rawNode.right.left.id, rawNode.right.left.rowCount, rawNode.operatorInfo, false, false, BigDecimal.ZERO));
                String canonicalTblName = rawNode.right.right.left.operatorInfo;
                node.getLeftNode().setRightNode(new FilterNode(rawNode.right.right.left.id,
                        TableManager.getInstance().getTableSize(canonicalTblName), "table:" + canonicalTblName));
                node.getLeftNode().setLeftNode(buildExecutionTree(rawNode.left));
                return node;
            }
            node = new JoinNode(rawNode.id, rawNode.rowCount, rawNode.operatorInfo, false, false, BigDecimal.ZERO);
            node.setLeftNode(rawNode.left == null ? null : buildExecutionTree(rawNode.left));
            node.setRightNode(rawNode.right == null ? null : buildExecutionTree(rawNode.right));
        } else if (nodeTypeRef.isReaderNode(nodeType)) {
            if (rawNode.right != null) {
                matches = matchPattern(EQ_OPERATOR, rawNode.left.operatorInfo);
                String canonicalTblName = rawNode.right.operatorInfo;
                long tableSize = TableManager.getInstance().getTableSize(canonicalTblName);
                // 处理IndexJoin没有selection的下推到tikv情况
                if (!matches.isEmpty() && nodeTypeRef.isTableScanNode(rawNode.right.nodeType)) {
                    node = new FilterNode(rawNode.id, TableManager.getInstance().getTableSize(canonicalTblName), null);
                    node.setTableName(canonicalTblName);
                } else if (rawNode.rowCount == tableSize) { // normal range scan
                    node = buildExecutionTree(rawNode.right);
                } else if (nodeTypeRef.isTableScanNode(rawNode.right.nodeType)) {
                    node = buildExecutionTree(rawNode.left);
                } else {
                    throw new TouchstoneException("不支持的查询计划");
                }
            }
            // 处理IndexReader后接一个IndexScan的情况
            else if (nodeTypeRef.isIndexScanNode(rawNode.left.nodeType)) {
                String canonicalTblName = rawNode.left.operatorInfo;
                long tableSize = TableManager.getInstance().getTableSize(canonicalTblName);
                // 处理IndexJoin没有selection的下推到tikv情况
                if (rawNode.left.rowCount != tableSize) {
                    node = new FilterNode(rawNode.left.id, tableSize, null);
                    node.setTableName(canonicalTblName);
                    // 正常情况
                } else {
                    node = new FilterNode(rawNode.left.id, rawNode.left.rowCount, null);
                    node.setTableName(canonicalTblName);
                }
            } else {
                node = buildExecutionTree(rawNode.left);
            }
        } else {
            throw new TouchstoneException("未支持的查询树Node，类型为" + nodeType);
        }
        return node;
    }


    /**
     * 根据explain analyze的结果生成query plan树
     *
     * @param queryPlan explain analyze的结果
     * @return 生成好的树
     */
    private RawNode buildRawNodeTree(List<String[]> queryPlan) throws TouchstoneException {
        Deque<Map.Entry<Integer, RawNode>> pStack = new ArrayDeque<>();
        List<List<String>> matches = matchPattern(PLAN_ID, queryPlan.get(0)[0]);
        String nodeType = matches.get(0).get(0).split("_")[0];
        String[] subQueryPlanInfo = queryPlan.get(0);
        String planId = matches.get(0).get(0), operatorInfo = subQueryPlanInfo[1], executionInfo = subQueryPlanInfo[2];
        Matcher matcher;
        int rowCount = (matcher = ROW_COUNTS.matcher(executionInfo)).find() ?
                Integer.parseInt(matcher.group(0).split(":")[1]) : 0;
        List<List<String>> iterMatches = matchPattern(ITER, executionInfo);
        rowCount = quickFixRowCount(nodeType, rowCount, iterMatches);
        RawNode rawNodeRoot = new RawNode(planId, null, null, nodeType, operatorInfo, rowCount), rawNode;
        pStack.push(new AbstractMap.SimpleEntry<>(0, rawNodeRoot));
        for (String[] subQueryPlan : queryPlan.subList(1, queryPlan.size())) {
            subQueryPlanInfo = subQueryPlan;
            matches = matchPattern(PLAN_ID, subQueryPlanInfo[0]);
            planId = matches.get(0).get(0);
            operatorInfo = subQueryPlanInfo[1];
            executionInfo = subQueryPlanInfo[2];
            nodeType = matches.get(0).get(0).split("_")[0];
            rowCount = (matcher = ROW_COUNTS.matcher(executionInfo)).find() ?
                    Integer.parseInt(matcher.group(0).split(":")[1]) : 0;
            rowCount = quickFixRowCount(nodeType, rowCount, iterMatches);
            rawNode = new RawNode(planId, null, null, nodeType, operatorInfo, rowCount);
            int level = (subQueryPlan[0].split("─")[0].length() + 1) / 2;
            while (!pStack.isEmpty() && pStack.peek().getKey() > level) {
                pStack.pop(); // pop直到找到同一个层级的节点
            }
            if (pStack.isEmpty()) {
                throw new TouchstoneException("pStack不应为空");
            }
            if (pStack.peek().getKey().equals(level)) {
                pStack.pop();
                if (pStack.isEmpty()) {
                    throw new TouchstoneException("pStack不应为空");
                }
                pStack.peek().getValue().right = rawNode;
            } else {
                pStack.peek().getValue().left = rawNode;
            }
            pStack.push(new AbstractMap.SimpleEntry<>(level, rawNode));
        }
        return rawNodeRoot;
    }

    // todo quick fix for tidb bug, maybe remove in the future.
    private int quickFixRowCount(String nodeType, int rowCount, List<List<String>> iterMatches) {
        if (nodeTypeRef.isRangeScanNode(nodeType) && !iterMatches.isEmpty()) {
            int iters = Integer.parseInt(iterMatches.get(0).get(1));
            rowCount = rowCount / iters;
        }
        return rowCount;
    }


    /**
     * 分析join信息
     * TODO support other valid schema object names listed in https://dev.mysql.com/doc/refman/5.7/en/identifiers.html
     *
     * @param joinInfo join字符串
     * @return 长度为4的字符串数组，0，1为join info左侧的表名和列名，2，3为join右侧的表明和列名
     * @throws TouchstoneException 无法分析的join条件
     */
    @Override
    public double analyzeJoinInfo(String joinInfo, String[] result) throws TouchstoneException {
        if (joinInfo.contains("other cond:")) {
            throw new TouchstoneException("join中包含其他条件,暂不支持");
        }
        if (matchPattern(INNER_JOIN, joinInfo).isEmpty()) {
            throw new UnsupportedJoin(joinInfo);
        }
        String leftTable, leftCol, rightTable, rightCol;
        Matcher eqCondition = JOIN_EQ_OPERATOR.matcher(joinInfo);
        if (eqCondition.find()) {
            if (eqCondition.groupCount() > 1) {
                throw new UnsupportedOperationException();
            }
            List<List<String>> matches = matchPattern(EQ_OPERATOR, joinInfo);
            String[] leftJoinInfos = matches.get(0).get(1).split(CANONICAL_NAME_SPLIT_REGEX);
            String[] rightJoinInfos = matches.get(0).get(2).split(CANONICAL_NAME_SPLIT_REGEX);
            leftTable = String.format("%s.%s", leftJoinInfos[0], leftJoinInfos[1]);
            rightTable = String.format("%s.%s", rightJoinInfos[0], rightJoinInfos[1]);
            List<String> leftCols = new ArrayList<>(), rightCols = new ArrayList<>();
            for (List<String> match : matches) {
                leftJoinInfos = match.get(1).split(CANONICAL_NAME_SPLIT_REGEX);
                rightJoinInfos = match.get(2).split(CANONICAL_NAME_SPLIT_REGEX);
                String currLeftTable = String.format("%s.%s", leftJoinInfos[0], leftJoinInfos[1]),
                        currLeftCol = leftJoinInfos[2],
                        currRightTable = String.format("%s.%s", rightJoinInfos[0], rightJoinInfos[1]),
                        currRightCol = rightJoinInfos[2];
                if (!leftTable.equals(currLeftTable) || !rightTable.equals(currRightTable)) {
                    throw new TouchstoneException("join中包含多个表的约束,暂不支持");
                }
                leftCols.add(currLeftCol);
                rightCols.add(currRightCol);
            }
            leftCol = String.join(",", leftCols);
            rightCol = String.join(",", rightCols);
            result[0] = leftTable;
            result[1] = leftCol;
            result[2] = rightTable;
            result[3] = rightCol;
        } else {
            Matcher innerInfo = INNER_JOIN_INNER_KEY.matcher(joinInfo);
            if (innerInfo.find()) {
                String[] innerInfos = innerInfo.group(1).split(CANONICAL_NAME_SPLIT_REGEX);
                result[0] = String.join(".", Arrays.asList(innerInfos[0], innerInfos[1]));
                result[1] = innerInfos[2];
            } else {
                throw new TouchstoneException("无法匹配的join格式" + joinInfo);
            }
            Matcher outerInfo = INNER_JOIN_OUTER_KEY.matcher(joinInfo);
            if (outerInfo.find()) {
                String[] outerInfos = outerInfo.group(1).split(CANONICAL_NAME_SPLIT_REGEX);
                result[2] = String.join(".", Arrays.asList(outerInfos[0], outerInfos[1]));
                result[3] = outerInfos[2];
            } else {
                throw new TouchstoneException("无法匹配的join格式" + joinInfo);
            }
        }
        if (result[1].contains(")")) {
            result[1] = result[1].substring(0, result[1].indexOf(')'));
        }
        if (result[3].contains(")")) {
            result[3] = result[3].substring(0, result[3].indexOf(')'));
        }
        return 1;
    }

    @Override
    public List<List<String[]>> splitQueryPlan(List<String[]> queryPlan) {
        return null;
    }

    @Override
    public List<Map.Entry<String, String>> splitQueryPlanForMultipleAggregate() {
        return null;
    }

    @Override
    public LogicNode analyzeSelectOperator(String operatorInfo) throws Exception {
        return parser.parseSelectOperatorInfo(operatorInfo);
    }
}
