package ecnu.db.schema;

import com.fasterxml.jackson.core.type.TypeReference;
import ecnu.db.LanguageManager;
import ecnu.db.utils.CommonUtils;
import ecnu.db.utils.exception.TouchstoneException;
import ecnu.db.utils.exception.schema.CannotFindSchemaException;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.traverse.TopologicalOrderIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;

import static ecnu.db.utils.CommonUtils.CANONICAL_NAME_CONTACT_SYMBOL;
import static ecnu.db.utils.CommonUtils.CANONICAL_NAME_SPLIT_REGEX;

public class TableManager {
    public static final String SCHEMA_MANAGE_INFO = "/schema.json";
    protected static final Logger logger = LoggerFactory.getLogger(TableManager.class);
    private static final TableManager INSTANCE = new TableManager();
    private LinkedHashMap<String, Table> schemas = new LinkedHashMap<>();
    private File schemaInfoPath;
    private final ResourceBundle rb = LanguageManager.getInstance().getRb();

    public TableManager() {
        // only for json reader
    }

    public static TableManager getInstance() {
        return INSTANCE;
    }

    public SortedMap<String, Long> getFk2PkTableSize(String schemaName) {
        return schemas.get(schemaName).getFk2PkTableSize();
    }

    public Map<String, Table> getSchemas() {
        return schemas;
    }

    public void setResultDir(String resultDir) {
        this.schemaInfoPath = new File(resultDir + SCHEMA_MANAGE_INFO);
    }

    public void storeSchemaInfo() throws IOException {
        // 假设主键为单列，复合主键中其他列的值作为辅助列
        schemas.values().forEach(Table::cleanPrimaryKey);
        String content = CommonUtils.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(schemas);
        CommonUtils.writeFile(schemaInfoPath.getPath(), content);
    }

    public void loadSchemaInfo() throws IOException {
        schemas = CommonUtils.MAPPER.readValue(CommonUtils.readFile(schemaInfoPath.getPath()), new TypeReference<>() {
        });
    }

    public void addSchema(String tableName, Table schema) {
        schemas.put(tableName, schema);
    }

    public String getPrimaryKeys(String tableName) throws CannotFindSchemaException {
        return getSchema(tableName).getPrimaryKeys();
    }

    public String getRefKey(String localCol) {
        String[] nameArray = localCol.split("\\.");
        String tableName = nameArray[0] + "." + nameArray[1];
        Table table = schemas.get(tableName);
        if (table == null) {
            return null;
        }
        return table.getForeignKeys().get(localCol);
    }

    public boolean isPrimaryKey(String canonicalColumnName) {
        String[] nameArray = canonicalColumnName.split("\\.");
        String tableName = nameArray[0] + "." + nameArray[1];
        Table table = schemas.get(tableName);
        if (table == null) {
            return false;
        }
        return table.getPrimaryKeysList().contains(canonicalColumnName);
    }

    public boolean isForeignKey(String canonicalColumnName) {
        String[] nameArray = canonicalColumnName.split("\\.");
        String tableName = nameArray[0] + "." + nameArray[1];
        Table table = schemas.get(tableName);
        if (table == null) {
            return false;
        }
        return table.getForeignKeys().containsKey(canonicalColumnName);
    }

    public boolean containSchema(String tableName) {
        return schemas.containsKey(tableName);
    }

    public long getTableSize(String tableName) throws CannotFindSchemaException {
        return getSchema(tableName).getTableSize();
    }

    public long getTableSizeWithCol(String columnName) {
        String[] cols = columnName.split("\\.");
        String tableName = cols[0] + "." + cols[1];
        return schemas.get(tableName).getTableSize();
    }

    public int getJoinTag(String tableName) throws CannotFindSchemaException {
        return getSchema(tableName).getJoinTag();
    }

    public void setPrimaryKeys(String tableName, String primaryKeys) throws TouchstoneException {
        getSchema(tableName).setPrimaryKeys(tableName + "." + primaryKeys);
    }


    public void setForeignKeys(String localTable, String localColumns, String refTable, String refColumns) throws TouchstoneException {
        String addReferenceDependencies = rb.getString("AddReferenceDependencies");
        logger.debug(addReferenceDependencies, localTable, localColumns, refTable, refColumns);
        getSchema(localTable).addForeignKey(localTable, localColumns, refTable, refColumns);
    }

    public void setTmpForeignKeys(String localTable, String localColumns, String refTable, String refColumns) throws TouchstoneException {
        getSchema(localTable).addTmpForeignKey(localTable, localColumns, refTable, refColumns);
    }

    public boolean isRefTable(String locTable, String locColumn, String remoteColumn) throws CannotFindSchemaException {
        return getSchema(locTable).isRefTable(locTable + "." + locColumn, remoteColumn);
    }

    public boolean isRefTable(String locTable, String remoteTable) {
        return schemas.get(locTable).isRefTable(remoteTable);
    }


    /**
     * 根据join的连接顺序，排列表名。顺序从被参照表到参照表。
     *
     * @return 从被参照表到参照表排序的表名。
     */
    public List<String> createTopologicalOrder() {
        Graph<String, DefaultEdge> schemaGraph = new DefaultDirectedGraph<>(DefaultEdge.class);
        schemas.keySet().forEach(schemaGraph::addVertex);
        for (Map.Entry<String, Table> schemaName2Schema : schemas.entrySet()) {
            for (String refColumn : schemaName2Schema.getValue().getForeignKeys().values()) {
                String[] refInfo = refColumn.split(CANONICAL_NAME_SPLIT_REGEX);
                schemaGraph.addEdge(refInfo[0] + CANONICAL_NAME_CONTACT_SYMBOL + refInfo[1], schemaName2Schema.getKey());
            }
        }
        TopologicalOrderIterator<String, DefaultEdge> topologicalOrderIterator = new TopologicalOrderIterator<>(schemaGraph);
        List<String> orderedSchemas = new LinkedList<>();
        while (topologicalOrderIterator.hasNext()) {
            orderedSchemas.add(topologicalOrderIterator.next());
        }
        return orderedSchemas;
    }

    public List<String> getAttributeColumnNames(String schemaName) throws CannotFindSchemaException {
        return getSchema(schemaName).getAttributeColumnNames();
    }

    public Table getSchema(String tableName) throws CannotFindSchemaException {
        Table schema = schemas.get(tableName);
        if (schema == null) {
            throw new CannotFindSchemaException(tableName);
        }
        return schema;
    }

    public void adjustFks(){
        schemas.values().forEach(Table::adjustFks);
    }
}
