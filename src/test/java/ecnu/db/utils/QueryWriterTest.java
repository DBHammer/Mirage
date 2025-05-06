package ecnu.db.utils;

import com.alibaba.druid.util.JdbcConstants;
import ecnu.db.analyzer.statical.QueryWriter;
import ecnu.db.generator.constraintchain.filter.Parameter;
import ecnu.db.schema.Column;
import ecnu.db.schema.ColumnManager;
import ecnu.db.schema.ColumnType;
import ecnu.db.utils.exception.TouchstoneException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QueryWriterTest {
    final static QueryWriter queryWriter = new QueryWriter();

    @BeforeAll
    static void setUp() {
        queryWriter.setDbType(JdbcConstants.MYSQL);
    }

    @ParameterizedTest
    @CsvSource(quoteCharacter = '#', value = {
            "5, 5",
            "1.5, 1.5",
            "'5', 5",
            "'1998-12-12 12:00:00.000000', 1998-12-12 12:00:00.000000"
    })
    void testTemplatizeSqlInt(String sqlValue, String dataValue) throws SQLException {
        String sql = "select * from test where a=" + sqlValue;
        List<Parameter> parameters = new ArrayList<>();
        parameters.add(new Parameter(0, null, dataValue));
        String modified = queryWriter.templatizeSql("Test Query", sql, parameters);
        modified = modified.replace('\n', ' ').replace('\t', ' ').replaceAll(" +", " ");
        assertEquals("select * from test where a = 'Mirage#0'", modified);
    }

    @Test
    void testTemplatizeSqlConflicts() throws SQLException, TouchstoneException {
        String sql = "select * from test where a='5' or b='5'";
        List<Parameter> parameters = new ArrayList<>();
        ColumnManager.getInstance().addColumn("db.test.a", new Column(ColumnType.INTEGER));
        ColumnManager.getInstance().addColumn("db.test.b", new Column(ColumnType.INTEGER));
        parameters.add(new Parameter(0, "db.test.a", "5"));
        parameters.add(new Parameter(1, "db.test.b", "5"));
        String modified = queryWriter.templatizeSql("q5", sql, parameters);
        modified = modified.replace('\n', ' ').replace('\t', ' ').replaceAll(" +", " ");
        assertEquals("select * from test where a = 'Mirage#0' or b = 'Mirage#1'", modified.replace('\n', ' '));
    }

    @Test
    void testTemplatizeSqlCannotFind() throws SQLException {
        String sql = "select * from test where a='5' or b='5'";
        List<Parameter> parameters = new ArrayList<>();
        Parameter parameter = new Parameter(0, "db.test.b", "6");
        parameters.add(parameter);
        String modified = queryWriter.templatizeSql("q6", sql, parameters);
        modified = modified.replace(System.lineSeparator(), " ").replace('\n', ' ').replace('\t', ' ').replaceAll(" +", " ");
        assertEquals("-- cannotFindArgs:{id:0,data:'6',operand:db.test.b} select * from test where a = '5' or b = '5'", modified);
    }
}