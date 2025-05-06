package ecnu.db.analyzer.online;

import ecnu.db.analyzer.online.node.ExecutionNode;
import ecnu.db.generator.constraintchain.filter.LogicNode;
import ecnu.db.utils.exception.TouchstoneException;

import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author wangqingshuai
 */
public abstract class AbstractAnalyzer {

    protected NodeTypeTool nodeTypeRef;
    protected Map<String, String> aliasDic = new HashMap<>();

    /**
     * 查询树的解析
     *
     * @param queryPlan query解析出的查询计划，带具体的行数
     * @return 查询树Node信息
     * @throws TouchstoneException 查询树无法解析
     */
    public abstract ExecutionNode getExecutionTree(List<String[]> queryPlan) throws TouchstoneException, IOException, SQLException;

    /**
     * 分析join信息
     *
     * @param joinInfo join字符串
     * @param result  长度为4的字符串数组，0，1为join info左侧的表名和列名，2，3为join右侧的表明和列名
     * @return 如果包含FK-FK的Join谓词，按照独立同分布计算其可能的概率
     * @throws TouchstoneException 无法分析的join条件
     */
    public abstract double analyzeJoinInfo(String joinInfo, String[] result) throws TouchstoneException;


    /**
     * 分割查询plan
     *
     * @param queryPlan 原始的查询plan
     * @return 分割后的多个查询plan
     */
    public abstract List<List<String[]>> splitQueryPlan(List<String[]> queryPlan);


    /**
     * 分割aggregate下不能识别的plan，返回识别的表和过滤条件，不再处理join和agg等算子
     *
     * @return 识别的表和过滤条件
     */
    public abstract List<Map.Entry<String, String>> splitQueryPlanForMultipleAggregate() throws IOException, TouchstoneException, SQLException;

    /**
     * 分析join信息
     *
     * @param operatorInfo 查询的条件
     * @return SelectResult 算数抽象语法树
     * @throws Exception 无法分析的Selection条件
     */
    public abstract LogicNode analyzeSelectOperator(String operatorInfo) throws Exception;


    public void setAliasDic(Map<String, String> aliasDic) {
        this.aliasDic = aliasDic;
    }

}
