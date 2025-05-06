package ecnu.db.dbconnector.adapter;

import ecnu.db.dbconnector.DbConnector;
import ecnu.db.utils.DatabaseConnectorConfig;
import ecnu.db.utils.exception.TouchstoneException;

import java.sql.SQLException;

public class PgConnector extends DbConnector {

    private static final String DB_DRIVER_TYPE = "postgresql";
    private static final String JDBC_PROPERTY = "";

    public PgConnector(DatabaseConnectorConfig config) throws TouchstoneException, SQLException {
        super(config, DB_DRIVER_TYPE, JDBC_PROPERTY);
    }

    @Override
    protected int[] getSqlInfoColumns() {
        return new int[]{1};
    }

    @Override
    protected String[] formatQueryPlan(String[] queryPlan) {
        return queryPlan;
    }

    @Override
    protected String[] preExecutionCommands() {
        return new String[]{"SET max_parallel_workers_per_gather = 0;", "SET join_collapse_limit = 1;"};
    }

    @Override
    protected String getExplainFormat() {
        return "EXPLAIN (ANALYZE, VERBOSE, FORMAT JSON, COSTS FALSE, TIMING FALSE) %s";
    }


}
