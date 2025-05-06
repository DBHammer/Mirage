package ecnu.db.utils;

/**
 * @author qingshuai.wang
 */
public class TaskConfiguratorConfig {
    private DatabaseConnectorConfig databaseConnectorConfig;
    private String resultDirectory;
    private String queriesDirectory;
    private Double skipNodeThreshold = 0.01;
    private String defaultSchemaName;

    public String getDefaultSchemaName() {
        return defaultSchemaName;
    }

    public void setDefaultSchemaName(String defaultSchemaName) {
        this.defaultSchemaName = defaultSchemaName;
    }

    public DatabaseConnectorConfig getDatabaseConnectorConfig() {
        return databaseConnectorConfig;
    }

    public void setDatabaseConnectorConfig(DatabaseConnectorConfig databaseConnectorConfig) {
        this.databaseConnectorConfig = databaseConnectorConfig;
    }

    public String getResultDirectory() {
        return resultDirectory;
    }

    public void setResultDirectory(String resultDirectory) {
        this.resultDirectory = resultDirectory;
    }

    public String getQueriesDirectory() {
        return queriesDirectory;
    }

    public void setQueriesDirectory(String queriesDirectory) {
        this.queriesDirectory = queriesDirectory;
    }

    public Double getSkipNodeThreshold() {
        return skipNodeThreshold;
    }

    public void setSkipNodeThreshold(Double skipNodeThreshold) {
        this.skipNodeThreshold = skipNodeThreshold;
    }
}
