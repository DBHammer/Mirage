package ecnu.db.analyzer.online.adapter.pg.parser;

import ecnu.db.generator.constraintchain.filter.LogicNode;
import java_cup.runtime.ComplexSymbolFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PgSelectOperatorInfoParserTest {
    private final PgSelectOperatorInfoLexer lexer = new PgSelectOperatorInfoLexer(new StringReader(""));
    private PgSelectOperatorInfoParser parser;

    @BeforeEach
    void setUp() {
        parser = new PgSelectOperatorInfoParser(lexer, new ComplexSymbolFactory());
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "(db1.table.col1 >= 2);" +
                    "col1 >= '2'",
            "((db1.table.col1 * (db1.table.col2 + 3.0)) >= 2);" +
                    "db1.table.col1 * db1.table.col2 + 3.0 >= 2",
            "((db1.table.col1 >= 2) or (db1.table.col4 < 3.0)); " +
                    "(col1 >= '2' or col4 < '3.0')",
            "((db1.table.col3) ~~ 'STRING');" +
                    "col3 like 'STRING'",
            "(db1.table.col3 = ANY ('{\"dasd\", dasd}'));" +
                    "col3 in ('dasd','dasd')",
            "(public.part.p_size = ANY ('{42,33,35,6,46,24,15,21}'::integer[]));" +
                    "p_size in ('42','33','35','6','46','24','15','21')",
            "((public.part.p_type)::text !~~ 'STANDARD ANODIZED%'::text);" +
                    "p_type not like 'STANDARD ANODIZED%'",
            "((public.part.p_type)::text = 'STANDARD BURNISHED NICKEL'::text);" +
                    "p_type = 'STANDARD BURNISHED NICKEL'"
    })
    void testPgParse(String input, String output) throws Exception {
        LogicNode node = parser.parseSelectOperatorInfo(input);
        assertEquals(output, node.toString().replaceAll(System.lineSeparator(), " "));
    }
}
