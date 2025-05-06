package ecnu.db.analyzer.statical;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.dialect.postgresql.visitor.PGASTVisitorAdapter;
import com.alibaba.druid.sql.parser.ParserException;
import com.alibaba.druid.sql.visitor.SchemaStatVisitor;
import com.alibaba.druid.stat.TableStat;
import ecnu.db.utils.exception.TouchstoneException;
import ecnu.db.utils.exception.analyze.IllegalQueryTableNameException;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

import static ecnu.db.utils.CommonUtils.matchPattern;

/**
 * @author qingshuai.wang
 */
public class QueryReader {
    private static final Pattern CANONICAL_TBL_NAME = Pattern.compile("[a-zA-Z0-9_$]+\\.[a-zA-Z0-9_$]+");
    private final ExportTableAliasVisitor aliasVisitor;
    private final String queriesDir;
    private DbType dbType;

    private static String defaultDatabaseName;

    public QueryReader(String defaultDatabaseName, String queriesDir) {
        QueryReader.defaultDatabaseName = defaultDatabaseName;
        aliasVisitor = new ExportTableAliasVisitor();
        this.queriesDir = queriesDir;
    }

    public void setDbType(DbType dbType) {
        this.dbType = dbType;
    }

    public List<File> loadQueryFiles() {
        return Optional.ofNullable(new File(queriesDir).listFiles())
                .map(Arrays::asList)
                .orElse(new ArrayList<>())
                .stream()
                .filter(file -> file.isFile() && file.getName().endsWith(".sql"))
                .toList();
    }

    public List<String> getQueriesFromFile(String file) throws IOException {
        StringBuilder fileContents = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("--")) {
                    fileContents.append(line).append(System.lineSeparator());
                }
            }
        }
        List<SQLStatement> statementList = null;
        try {
            statementList = SQLUtils.parseStatements(fileContents.toString(), dbType, true);
        } catch (ParserException e) {
            LoggerFactory.getLogger(QueryReader.class).info("Parse SQL failed: {}", file, e);
            System.exit(-1);
        }

        List<String> sqls = new ArrayList<>();
        // temp fix for druid parse, the bug is that
        // input query : select * from (t1 cross join t2) cross join (t3 cross join t4)
        // output query : select * from t1 cross join t2 cross join t3 cross join t4
        if (statementList.size() == 1) {
            sqls.add(fileContents.toString());
        } else {
            for (SQLStatement sqlStatement : statementList) {
                String sql = SQLUtils.format(sqlStatement.toString(), dbType);
                sql = sql.replace(System.lineSeparator(), " ");
                sql = sql.replace('\t', ' ');
                sql = sql.replaceAll(" +", " ");
                sqls.add(sql);
            }
        }
        return sqls;
    }

    public Set<String> getTableName(String sql) throws IllegalQueryTableNameException {
        List<SQLStatement> stmtList = SQLUtils.parseStatements(sql, dbType);
        SQLStatement stmt = stmtList.get(0);
        SchemaStatVisitor statVisitor = SQLUtils.createSchemaStatVisitor(dbType);
        stmt.accept(statVisitor);
        HashSet<String> tableName = new HashSet<>();
        for (TableStat.Name name : statVisitor.getTables().keySet()) {
            tableName.add(addDatabaseNamePrefix(name.getName()));
        }
        return tableName;
    }


    public Map<String, String> getTableAlias(String sql) throws TouchstoneException {
        SQLStatement sqlStatement = SQLUtils.parseStatements(sql, dbType).get(0);
        if (!(sqlStatement instanceof SQLSelectStatement statement)) {
            throw new TouchstoneException("Only support select statement");
        }
        statement.accept(aliasVisitor);
        return aliasVisitor.getAliasMap();
    }

    public Map<String, Set<String>> getInvolvedColumnName(String sql) throws TouchstoneException {
        SQLStatement sqlStatement = SQLUtils.parseStatements(sql, dbType).get(0);
        SchemaStatVisitor statVisitor = SQLUtils.createSchemaStatVisitor(dbType);
        sqlStatement.accept(statVisitor);
        Map<String, Set<String>> table2ColumnNames = new HashMap<>();
        boolean hasMultipleTable = statVisitor.getTables().size() > 1;
        for (TableStat.Condition condition : statVisitor.getConditions()) {
            String[] result = condition.getColumn().getFullName().split("\\.");
            if (hasMultipleTable && !sql.replace("\"", "").contains(condition.getColumn().getFullName())) {
                result[0] = "UNKNOWN";
            }
            if (result.length != 2) {
                throw new TouchstoneException("Druid Api Change");
            }
            String tableName = addDatabaseNamePrefix(result[0]);
            table2ColumnNames.putIfAbsent(tableName, new HashSet<>());
            table2ColumnNames.get(tableName).add(tableName + "." + result[1]);
        }
        return table2ColumnNames;
    }

    /**
     * @param files SQL文件
     * @return 所有查询中涉及到的表名
     * @throws IOException 从SQL文件中获取Query失败
     */
    public List<String> fetchTableNames(List<File> files) throws IOException, IllegalQueryTableNameException {
        List<String> tableNames = new ArrayList<>();
        for (File sqlFile : files) {
            List<String> queries = getQueriesFromFile(sqlFile.getPath());
            for (String query : queries) {
                tableNames.addAll(getTableName(query));
            }
        }
        tableNames = tableNames.stream().distinct().toList();
        return tableNames;
    }

    /**
     * @param files SQL文件
     * @return 所有查询中涉及到的表名
     * @throws IOException 从SQL文件中获取Query失败
     */
    public Map<String, Set<String>> fetchQueryColumnNames(List<File> files) throws IOException, TouchstoneException {
        Map<String, Set<String>> tableNames = new HashMap<>();
        for (File sqlFile : files) {
            List<String> queries = getQueriesFromFile(sqlFile.getPath());
            for (String query : queries) {
                for (Map.Entry<String, Set<String>> tableName2Columns : getInvolvedColumnName(query).entrySet()) {
                    tableNames.putIfAbsent(tableName2Columns.getKey(), new HashSet<>());
                    tableNames.get(tableName2Columns.getKey()).addAll(tableName2Columns.getValue());
                }
            }
        }
        return tableNames;
    }

    /**
     * 单个数据库时把表转换为<database>.<table>的形式
     *
     * @param tableName 表名
     * @return 转换后的表名
     */
    private static String addDatabaseNamePrefix(String tableName) throws IllegalQueryTableNameException {
        List<List<String>> matches = matchPattern(QueryReader.CANONICAL_TBL_NAME, tableName);
        if (matches.size() == 1 && matches.getFirst().getFirst().length() == tableName.length()) {
            return tableName;
        } else {
            if (defaultDatabaseName == null) {
                throw new IllegalQueryTableNameException();
            }
            return String.format("%s.%s", defaultDatabaseName, tableName);
        }
    }


    private static class ExportTableAliasVisitor extends PGASTVisitorAdapter {
        private final Map<String, String> aliasMap = new HashMap<>();

        @Override
        public boolean visit(SQLExprTableSource x) {
            if (x.getAlias() != null) {
                try {
                    aliasMap.put(x.getAlias(), addDatabaseNamePrefix(x.getName().toString()));
                } catch (IllegalQueryTableNameException e) {
                    e.printStackTrace();
                }
            }
            return true;
        }

        public Map<String, String> getAliasMap() {
            return aliasMap;
        }
    }


}
