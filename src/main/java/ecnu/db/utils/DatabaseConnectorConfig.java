package ecnu.db.utils;

/**
 * @author alan
 */
public class DatabaseConnectorConfig {
    private String databaseIp;
    private String databasePort;
    private String databaseUser;
    private String databasePwd;
    private String databaseName;

    public DatabaseConnectorConfig() {
    }

    public DatabaseConnectorConfig(String databaseIp, String databasePort, String databaseUser, String databasePwd, String databaseName) {
        this.databaseIp = databaseIp;
        this.databasePort = databasePort;
        this.databaseUser = databaseUser;
        this.databasePwd = databasePwd;
        this.databaseName = databaseName;
    }

    public String getDatabasePort() {
        return databasePort;
    }

    public void setDatabasePort(String databasePort) {
        this.databasePort = databasePort;
    }

    public String getDatabaseUser() {
        return databaseUser;
    }

    public void setDatabaseUser(String databaseUser) {
        this.databaseUser = databaseUser;
    }

    public String getDatabasePwd() {
        return databasePwd;
    }

    public void setDatabasePwd(String databasePwd) {
        this.databasePwd = databasePwd;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public String getDatabaseIp() {
        return databaseIp;
    }

    public void setDatabaseIp(String databaseIp) {
        this.databaseIp = databaseIp;
    }
}
