package ecnu.db.schema;

import ecnu.db.utils.CommonUtils;
import picocli.CommandLine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "create", description = "generate the ddl sql for the new database")
public class DDLGenerator implements Callable<Integer> {
    @CommandLine.Option(names = {"-c", "--config_path"}, required = true, description = "the config path for creating ddl")
    private String configPath;
    @CommandLine.Option(names = {"-d", "--database"}, defaultValue = "demo", description = "the database name")
    private String dataBase;
    @CommandLine.Option(names = {"-o", "--output"}, defaultValue = "./ddl", description = "the output path for dll")
    private String outputPath;

    public void init() throws IOException {
        TableManager.getInstance().setResultDir(configPath);
        TableManager.getInstance().loadSchemaInfo();
        ColumnManager.getInstance().setResultDir(configPath);
        ColumnManager.getInstance().loadColumnMetaData();
        createSchemaPath = outputPath + "/CreateSchema.sql";
        createIndexPath = outputPath + "/CreateIndex.sql";
        importData = outputPath + "/importData.sql";
    }

    private String createSchemaPath;
    private String createIndexPath;
    private String importData;

    @Override
    public Integer call() throws IOException {
        init();
        //构建createschema语句
        createSchema();
        //构建数据导入语句
        importData();
        //构建createindex语句
        createIndex();
        return 0;
    }

    public void createSchema() throws IOException {
        String deleteDatabase = "DROP DATABASE IF EXISTS " + dataBase + ";\n";
        String createDatabase = "CREATE DATABASE " + dataBase + ";\n";
        String connectDatabase = "\\c " + dataBase + ";\n";
        StringBuilder createSchema = new StringBuilder().append(deleteDatabase).append(createDatabase).append(connectDatabase);
        for (Map.Entry<String, Table> tableName2Schema : TableManager.getInstance().getSchemas().entrySet()) {
            createSchema.append(tableName2Schema.getValue().getSql(tableName2Schema.getKey())).append("\n");
        }
        CommonUtils.writeFile(createSchemaPath, createSchema.toString());
    }

    public void importData() throws IOException {
        StringBuilder importData = new StringBuilder("\\c " + dataBase + ";\n");
        for (Map.Entry<String, Table> tableName2Schema : TableManager.getInstance().getSchemas().entrySet()) {
            String tableName = tableName2Schema.getKey();
            String inData = "\\Copy " + tableName.split("\\.")[1] + " FROM PROGRAM" + "'" + "cat ./data/public." + tableName.split("\\.")[1] + "-0-*" + "' DELIMITER ',' " + "NULL '\\N';\n";
            importData.append(inData);
        }
        CommonUtils.writeFile(this.importData, importData.toString());
    }

    public void createIndex() throws IOException {
        List<String> addFks = new ArrayList<>();
        StringBuilder createIndex = new StringBuilder("\\c " + dataBase + "\n");
        for (Map.Entry<String, Table> tableName2Schema : TableManager.getInstance().getSchemas().entrySet()) {
            String tableName = tableName2Schema.getKey();
            Table table = tableName2Schema.getValue();
            Map<String, String> foreignKeys = table.getForeignKeys();
            List<String> pks = new ArrayList<>(table.getPrimaryKeysList());
            if (!foreignKeys.isEmpty()) {
                int indexIndex = 0;
                for (Map.Entry<String, String> foreignKey : foreignKeys.entrySet()) {
                    pks.removeIf(pk -> pk.equals(foreignKey.getKey()));
                    String key = foreignKey.getKey().split("\\.")[2].toUpperCase();
                    String tableRef = foreignKey.getValue().split("\\.")[1].toUpperCase();
                    String indexName = tableName.split("\\.")[1] + "_" + key.split("_")[1].toLowerCase() + indexIndex++;
                    String simpleTableName = tableName.split("\\.")[1].toUpperCase();
                    addFks.add(String.format("ALTER TABLE %s ADD FOREIGN KEY (%s) references %s;%nCREATE INDEX %s on %s(%s);%n", simpleTableName, key, tableRef, indexName, simpleTableName, key));
                }
            }
            List<String> addPks = new ArrayList<>();
            if (!pks.isEmpty()) {
                for (String pk : pks) {
                    pk = pk.split(",")[0].split("\\.")[2].toUpperCase();
                    String simpleTableName = tableName.split("\\.")[1].toUpperCase();
                    addPks.add(String.format("ALTER TABLE %s ADD PRIMARY KEY (%s);%n", simpleTableName, pk));
                }
            }
            if (!addPks.isEmpty()) {
                for (String addPk : addPks) {
                    createIndex.append(addPk).append("\n");
                }
            }
        }
        for (String addFk : addFks) {
            createIndex.append(addFk).append("\n");
        }
        createIndex.append("analyse;");
        CommonUtils.writeFile(createIndexPath, createIndex.toString());
    }
}
