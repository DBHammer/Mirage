package ecnu.db.schema;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import ecnu.db.dbconnector.DbConnector;
import ecnu.db.utils.exception.TouchstoneException;

import java.sql.SQLException;
import java.util.*;

/**
 * @author wangqingshuai
 */
public class Table {
    private long tableSize;
    private List<String> primaryKeys;
    private List<String> canonicalColumnNames;
    private Map<String, String> foreignKeys = new TreeMap<>();
    @JsonIgnore
    private final Map<String, String> tmpForeignKeys = new TreeMap<>();
    @JsonIgnore
    private int joinTag = 0;

    @JsonCreator
    public Table() {
        this.primaryKeys = new ArrayList<>();
    }

    public Table(List<String> canonicalColumnNames, long tableSize) {
        this.canonicalColumnNames = canonicalColumnNames;
        this.tableSize = tableSize;
        this.primaryKeys = new ArrayList<>();
    }

    public List<String> getCanonicalColumnNames() {
        return canonicalColumnNames;
    }

    @JsonIgnore
    public List<String> getAttributeColumnNames() {
        List<String> canonicalColumnNamesNotKey = new ArrayList<>(canonicalColumnNames);
        canonicalColumnNamesNotKey.removeAll(primaryKeys);
        canonicalColumnNamesNotKey.removeAll(foreignKeys.keySet());
        return canonicalColumnNamesNotKey;
    }

    public synchronized int getJoinTag() {
        return joinTag++;
    }

    public void cleanPrimaryKey() {
        while (primaryKeys.size() > 1) {
            primaryKeys.removeFirst();
        }
    }

    public boolean containColumn(String columnName) {
        return primaryKeys.contains(columnName) || foreignKeys.containsKey(columnName) || canonicalColumnNames.contains(columnName);
    }


    /**
     * 本表的列是否参照目标列
     *
     * @param localColumn 本表列
     * @param refColumn   目标列
     * @return 参照时返回true，否则返回false
     */
    public boolean isRefTable(String localColumn, String refColumn) {
        if (foreignKeys.containsKey(localColumn)) {
            return refColumn.equals(foreignKeys.get(localColumn));
        } else {
            return false;
        }
    }

    @JsonIgnore
    public SortedMap<String, Long> getFk2PkTableSize() {
        SortedMap<String, Long> fk2PkTableSize = new TreeMap<>();
        for (Map.Entry<String, String> foreignKey : foreignKeys.entrySet()) {
            fk2PkTableSize.put(foreignKey.getKey(), TableManager.getInstance().getTableSizeWithCol(foreignKey.getValue()));
        }
        return fk2PkTableSize;
    }

    public boolean isRefTable(String refTable) {
        return foreignKeys.values().stream().anyMatch(remoteColumn -> remoteColumn.contains(refTable));
    }

    public void addForeignKey(String localTable, String localColumnName,
                              String referencingTable, String referencingInfo) throws TouchstoneException {
        addForeignKey(foreignKeys, localTable, localColumnName, referencingTable, referencingInfo);
    }

    public void addTmpForeignKey(String localTable, String localColumnName,
                                 String referencingTable, String referencingInfo) throws TouchstoneException {
        addForeignKey(tmpForeignKeys, localTable, localColumnName, referencingTable, referencingInfo);
    }

    private void addForeignKey(Map<String, String> foreignKeyMap, String localTable, String localColumnName,
                               String referencingTable, String referencingInfo) throws TouchstoneException {
        String[] columnNames = localColumnName.split(",");
        String[] refColumnNames = referencingInfo.split(",");
        for (int i = 0; i < columnNames.length; i++) {
            if (foreignKeyMap.containsKey(columnNames[i])) {
                if (!(referencingTable + "." + refColumnNames[i]).equals(foreignKeyMap.get(columnNames[i]))) {
                    throw new TouchstoneException("冲突的主外键连接");
                } else {
                    return;
                }
            }
            foreignKeyMap.put(localTable + "." + columnNames[i], referencingTable + "." + refColumnNames[i]);
            primaryKeys.remove(localTable + "." + columnNames[i]);
        }
    }


    public long getTableSize() {
        return tableSize;
    }

    public void setTableSize(long tableSize) {
        this.tableSize = tableSize;
    }

    public synchronized Map<String, String> getForeignKeys() {
        return foreignKeys;
    }

    @JsonSetter
    @SuppressWarnings("unused")
    public synchronized void setForeignKeys(Map<String, String> foreignKeys) {
        this.foreignKeys = foreignKeys;
    }

    @JsonGetter
    @SuppressWarnings("unused")
    public synchronized String getPrimaryKeys() {
        return String.join(",", primaryKeys);
    }

    public synchronized void setPrimaryKeys(List<String> primaryKeys) {
        this.primaryKeys = primaryKeys;
    }

    public synchronized void setPrimaryKeys(String primaryKeys) throws TouchstoneException {
        if (this.primaryKeys.isEmpty()) {
            this.primaryKeys.addAll(List.of(primaryKeys.split(",")));
        } else {
            Set<String> newKeys = new HashSet<>(Arrays.asList(primaryKeys.split(",")));
            Set<String> keys = new HashSet<>(this.primaryKeys);
            if (keys.size() == newKeys.size()) {
                keys.removeAll(newKeys);
                if (!keys.isEmpty()) {
                    throw new TouchstoneException("query中使用了多列主键的部分主键");
                }
            } else {
                throw new TouchstoneException("query中使用了多列主键的部分主键");
            }
        }
    }

    @JsonIgnore
    public List<String> getPrimaryKeysList() {
        return primaryKeys;
    }

    public void toSQL(DbConnector dbConnector, String tableName) throws SQLException {
        StringBuilder head = new StringBuilder("CREATE TABLE ");
        head.append(tableName).append(" (");
        List<String> allColumns = produceKeySQL();
        for (String canonicalColumnName : allColumns) {
            String columnName = canonicalColumnName.split("\\.")[2];
            Column column = ColumnManager.getInstance().getColumn(canonicalColumnName);
            String addColumn = columnName + " " + column.getOriginalType() + ",";
            head.append(addColumn);
        }
        head = new StringBuilder(head.substring(0, head.length() - 1));
        head.append(");");
        dbConnector.executeSql(head.toString());
    }

    public String getSql(String tableName) {
        StringBuilder head = new StringBuilder("CREATE TABLE ");
        head.append(tableName).append(" (\n");
        List<String> allColumns = produceKeySQL();
        for (String canonicalColumnName : allColumns) {
            String columnName = canonicalColumnName.split("\\.")[2];
            Column column = ColumnManager.getInstance().getColumn(canonicalColumnName);
            String columnType = column.getOriginalType();
            if (columnType.contains("TIME ")) {
                columnType = columnType.replace("TIME ", "TIMESTAMP ");
            }
            String addColumn = "\"" + columnName + "\" " + columnType + ",";
            head.append("  ").append(addColumn).append("\n");
        }
        head = new StringBuilder(head.substring(0, head.length() - 2));
        head.append("\n);\n");
        return head.toString();
    }

    private List<String> produceKeySQL() {
        List<String> allColumns = new ArrayList<>(canonicalColumnNames);
        List<String> tempPrimayKeys = new ArrayList<>(primaryKeys);
        tempPrimayKeys.removeAll(foreignKeys.keySet());
        allColumns.removeAll(tempPrimayKeys);
        allColumns.removeAll(foreignKeys.keySet());
        List<String> foreignKeysList = new ArrayList<>(foreignKeys.keySet());
        Collections.sort(foreignKeysList);
        allColumns.addAll(0, foreignKeysList);
        allColumns.addAll(0, tempPrimayKeys);
        return allColumns;
    }

    @Override
    public String toString() {
        return "Schema{tableSize=" + tableSize + '}';
    }


    public void adjustFks() {
        for (Map.Entry<String, String> tmpFK : tmpForeignKeys.entrySet()) {
            if (!foreignKeys.containsKey(tmpFK.getKey())) {
                foreignKeys.put(tmpFK.getKey(), tmpFK.getValue());
                primaryKeys.remove(tmpFK.getKey());
            }
        }
    }
}
