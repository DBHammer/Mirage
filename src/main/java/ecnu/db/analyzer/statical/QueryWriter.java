package ecnu.db.analyzer.statical;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.parser.Lexer;
import com.alibaba.druid.sql.parser.Token;
import ecnu.db.LanguageManager;
import ecnu.db.generator.constraintchain.filter.Parameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static ecnu.db.analyzer.online.adapter.pg.PgAnalyzer.TIME_OR_DATE;


/**
 * @author alan
 */
public class QueryWriter {
    private static final Logger logger = LoggerFactory.getLogger(QueryWriter.class);
    private static final Pattern DATECompute = Pattern.compile("(?i)'*" + TIME_OR_DATE + "'* ([+\\-]) interval '[0-9]+' (month|year|day)");
    private static final Pattern NumberCompute = Pattern.compile("[0-9]+\\.*[0-9]* (([+\\-]) [0-9]+\\.*[0-9]*)+");
    private DbType dbType;
    private final ResourceBundle rb = LanguageManager.getInstance().getRb();

    public void setDbType(DbType dbType) {
        this.dbType = dbType;
    }

    /**
     * 模板化SQL语句
     *
     * @param queryCanonicalName query标准名
     * @param query              需要处理的SQL语句
     * @param parameters         需要模板化的参数
     * @return 模板化的SQL语句
     */
    public String templatizeSql(String queryCanonicalName, String query, List<Parameter> parameters) throws SQLException {
        Matcher matcher = DATECompute.matcher(query);
        while (matcher.find()) {
            String dateCompute = matcher.group();
            query = query.replace(dateCompute, evaluate(dateCompute, true));
        }
        matcher = NumberCompute.matcher(query);
        while (matcher.find()) {
            String dateCompute = matcher.group();
            query = query.replaceFirst(dateCompute, evaluate(dateCompute, false));
        }
        for (Parameter parameter : parameters) {
            List<String> cols = parameter.hasOnlyOneColumn();
            if (cols.size() > 1) {
                String pattern = cols.stream().map(col -> col.split("\\.")[2]).collect(Collectors.joining(" .{1,2} "));
                matcher = Pattern.compile(pattern).matcher(query);
                if (matcher.find()) {
                    query = query.replace(matcher.group(), matcher.group() + " +'Mirage#" + parameter.getId() + "' ");
                }
            }
        }
        Lexer lexer = new Lexer(query, null, dbType);
        // paramter data, column name -> start location and end location
        Map<String, List<parameterColumnName2Location>> literalMap = new HashMap<>();
        int lastPos = 0;
        int pos;
        String lastColumn = "";
        boolean nextIntIsNegavive = false;
        while (!lexer.isEOF()) {
            lexer.nextToken();
            // 读进一个关键字
            Token token = lexer.token();
            // 更新到最新的位置
            pos = lexer.pos();
            // 如果可能是列名
            if (token == Token.IDENTIFIER) {
                while (query.length() > pos && query.charAt(pos) == '.') {
                    lexer.nextToken();
                    lexer.nextToken();
                    pos = lexer.pos();
                }
                String col = query.substring(lastPos, pos).trim();
                if (!col.equals("DATE")) {
                    lastColumn = col;
                }
            } else if (token == Token.LITERAL_INT || token == Token.LITERAL_FLOAT || token == Token.LITERAL_CHARS || token == Token.SUB) {
                if (token == Token.SUB) {
                    nextIntIsNegavive = true;
                }
                if (token == Token.LITERAL_INT && nextIntIsNegavive) {
                    lastPos--;
                    nextIntIsNegavive = false;
                }
                String str = query.substring(lastPos, pos).trim();
                if (!literalMap.containsKey(str)) {
                    literalMap.put(str, new ArrayList<>());
                }
                parameterColumnName2Location currentLocations = null;
                for (parameterColumnName2Location parameterColumnName2Location : literalMap.get(str)) {
                    if (parameterColumnName2Location.columnName.equals(lastColumn)) {
                        currentLocations = parameterColumnName2Location;
                        break;
                    }
                }
                if (currentLocations != null) {
                    currentLocations.range.add(new AbstractMap.SimpleEntry<>(pos - str.length(), pos));
                } else {
                    currentLocations = new parameterColumnName2Location();
                    currentLocations.columnName = lastColumn;
                    currentLocations.range.add(new AbstractMap.SimpleEntry<>(pos - str.length(), pos));
                    literalMap.get(str).add(currentLocations);
                }
            }
            lastPos = pos;
        }

        // replacement
        List<Parameter> cannotFindArgs = new ArrayList<>();
        List<Parameter> conflictArgs = new ArrayList<>();
        TreeMap<Integer, Map.Entry<Parameter, Map.Entry<Integer, Integer>>> replaceParams = new TreeMap<>();
        List<Parameter> subPlanParameters = parameters.stream().filter(Parameter::isSubPlan).toList();
        parameters.removeAll(subPlanParameters);
        parameters.addAll(subPlanParameters);
        for (Parameter parameter : parameters) {
            if (parameter.hasOnlyOneColumn().size() > 1) {
                continue;
            }

            String data = parameter.getRealDataValue();
            List<parameterColumnName2Location> matches = literalMap.getOrDefault(data, new ArrayList<>());
            if (literalMap.containsKey("'" + data + "'")) {
                List<parameterColumnName2Location> name2Locations = literalMap.get("'" + data + "'");
                for (parameterColumnName2Location name2Location : name2Locations) {
                    if (parameter.getOperand() == null || parameter.getOperand().endsWith(name2Location.columnName)) {
                        matches.add(name2Location);
                    }
                }
            }

            if (matches.isEmpty()) {
                cannotFindArgs.add(parameter);
            } else if (matches.size() > 1) {
                String col = parameter.getOperand();
                parameterColumnName2Location location = null;
                for (parameterColumnName2Location match : matches) {
                    if (col.contains(match.columnName)) {
                        location = match;
                        break;
                    }
                }
                if (location != null) {
                    var pair = location.range;
                    for (Map.Entry<Integer, Integer> range : pair) {
                        replaceParams.put(range.getKey(), new AbstractMap.SimpleEntry<>(parameter, range));
                    }
                } else {
                    conflictArgs.add(parameter);
                }
            } else {
                if (parameter.isSubPlan()) {
                    var subplanranges = matches.stream().findFirst().get().range;
                    var subplanrange = subplanranges.get(subplanranges.size() - 1);
                    replaceParams.put(subplanrange.getKey(), new AbstractMap.SimpleEntry<>(parameter, subplanrange));
                } else {
                    var pair = matches.stream().findFirst().get().range;
                    for (Map.Entry<Integer, Integer> range : pair) {
                        replaceParams.put(range.getKey(), new AbstractMap.SimpleEntry<>(parameter, range));
                    }
                }
            }
        }
        StringBuilder fragments = new StringBuilder();
        int currentPos = 0;
        while (!replaceParams.isEmpty()) {
            Map.Entry<Integer, Map.Entry<Parameter, Map.Entry<Integer, Integer>>> entry = replaceParams.pollFirstEntry();
            Map.Entry<Parameter, Map.Entry<Integer, Integer>> pair = entry.getValue();
            Parameter parameter = pair.getKey();
            int startPos = pair.getValue().getKey();
            int endPos = pair.getValue().getValue();
            fragments.append(query, currentPos, startPos).append(String.format("'Mirage#%s'", parameter.getId()));
            currentPos = endPos;
        }
        fragments.append(query.substring(currentPos));
        query = fragments.toString();
        if (!cannotFindArgs.isEmpty()) {
            logger.warn(rb.getString("CannotReplace1"), queryCanonicalName);
            query = appendArgs("cannotFindArgs", cannotFindArgs) + query;
        }
        if (!conflictArgs.isEmpty()) {
            logger.warn(rb.getString("CannotReplace2"), queryCanonicalName);
            query = appendArgs("conflictArgs", conflictArgs) + query;
        }
        return SQLUtils.format(query, dbType, SQLUtils.DEFAULT_LCASE_FORMAT_OPTION);
    }


    public String evaluate(String str, boolean isDate) throws SQLException {
        String h2ConnUrl = "jdbc:h2:mem:h2DB;MODE=MYSQL;";
        try (Connection conn = DriverManager.getConnection(h2ConnUrl, "root", "root")) {
            try (Statement statement = conn.createStatement()) {
                String date = isDate ? "DATE " : " ";
                ResultSet resultSet = statement.executeQuery("SELECT " + date + str);
                return resultSet.next() ? "'" + resultSet.getString(1) + "'" : "";
            }
        }
    }


    /**
     * 为模板化后的SQL语句添加conflictArgs和cannotFindArgs参数
     *
     * @param title  标题
     * @param params 需要添加的
     * @return 添加的参数部分
     */
    public String appendArgs(String title, List<Parameter> params) {
        String argsString = params.stream().map(parameter -> String.format("{id:%s,data:%s,operand:%s}",
                        parameter.getId(),
                        "'" + parameter.getDataValue() + "'",
                        parameter.getOperand()))
                .collect(Collectors.joining(","));
        return String.format("-- %s:%s%s", title, argsString, System.lineSeparator());
    }

    private static class parameterColumnName2Location {
        public String columnName;
        public List<Map.Entry<Integer, Integer>> range = new ArrayList<>();
    }
}
