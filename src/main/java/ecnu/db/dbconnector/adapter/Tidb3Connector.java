package ecnu.db.dbconnector.adapter;

import ecnu.db.dbconnector.DbConnector;
import ecnu.db.utils.DatabaseConnectorConfig;
import ecnu.db.utils.exception.TouchstoneException;

import java.sql.SQLException;

public class Tidb3Connector extends DbConnector {
    private static final String DB_DRIVER_TYPE = "mysql";
    private static final String JDBC_PROPERTY = "useSSL=false&allowPublicKeyRetrieval=true";

    public Tidb3Connector(DatabaseConnectorConfig config) throws TouchstoneException, SQLException {
        super(config, DB_DRIVER_TYPE, JDBC_PROPERTY);
    }

    @Override
    public int[] getSqlInfoColumns() {
        return new int[]{1, 4, 5};
    }

    @Override
    protected String[] formatQueryPlan(String[] data) {
        return data;
    }

    @Override
    protected String[] preExecutionCommands() {
        return new String[0];
    }

    @Override
    protected String getExplainFormat() {
        return "EXPLAIN ANALYZE %s";
    }
}
