package ecnu.db.dbconnector.adapter;

import ecnu.db.dbconnector.DbConnector;
import ecnu.db.utils.DatabaseConnectorConfig;
import ecnu.db.utils.exception.TouchstoneException;

import java.sql.SQLException;


/**
 * @author wangqingshuai
 */
public class Tidb4Connector extends DbConnector {
    private static final String DB_DRIVER_TYPE = "mysql";
    private static final String JDBC_PROPERTY = "useSSL=false&allowPublicKeyRetrieval=true";


    public Tidb4Connector(DatabaseConnectorConfig config) throws TouchstoneException, SQLException {
        super(config, DB_DRIVER_TYPE, JDBC_PROPERTY);
    }

    @Override
    protected int[] getSqlInfoColumns() {
        return new int[]{1, 7, 3, 5};
    }

    @Override
    protected String getExplainFormat() {
        return "EXPLAIN ANALYZE %s";
    }

    /**
     * 获取节点上查询计划的信息
     *
     * @param data 需要处理的数据
     * @return 返回plan_id, operator_info, execution_info
     */
    @Override
    protected String[] formatQueryPlan(String[] data) {
        String[] ret = new String[3];
        ret[0] = data[0];
        ret[1] = data[3].isEmpty() ? data[1] : String.format("%s,%s", data[3], data[1]);
        ret[2] = "rows:" + data[2];
        return ret;
    }

    @Override
    protected String[] preExecutionCommands() {
        return new String[0];
    }
}
