package ecnu.db.dbconnector;

import ecnu.db.utils.exception.TouchstoneException;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * @author lianxuechao
 */
public interface DatabaseConnectorInterface {
    /**
     * explain analyze一个query
     *
     * @param queryCanonicalName 对应query的标准名称
     * @param sql                对应query的sql
     * @param sqlInfoColumns     需要提取的col
     * @return 查询计划
     * @throws SQLException        无法从数据库连接中获取查询计划
     * @throws TouchstoneException 无法从文件中获取查询计划
     */
    List<String[]> explainQuery(String queryCanonicalName, String sql, String[] sqlInfoColumns) throws SQLException, TouchstoneException;

    /**
     * 获取多个col组合的cardinality, 每次查询会被记录到multiColNdvMap
     *
     * @param canonicalTableName 需要查询的表的标准名
     * @param columns            需要查询的col组合(','组合)
     * @return 多个col组合的cardinality
     * @throws SQLException        无法从数据库连接中获取多列属性的ndv
     * @throws TouchstoneException 无法从文件中获取多列属性的ndv
     */
    int getMultiColNdv(String canonicalTableName, String columns) throws SQLException, TouchstoneException;

    /**
     * 获取多个col组合的cardinality的map，用于后续的dump操作
     *
     * @return 多个col组合的cardinality的map
     */
    Map<String, Integer> getMultiColNdvMap();
}
