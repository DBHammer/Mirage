package ecnu.db.utils;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import ecnu.db.generator.constraintchain.ConstraintChainNode;
import ecnu.db.generator.constraintchain.ConstraintChainNodeDeserializer;
import ecnu.db.generator.constraintchain.filter.BoolExprNode;
import ecnu.db.generator.constraintchain.filter.BoolExprNodeDeserializer;
import ecnu.db.generator.constraintchain.filter.arithmetic.ArithmeticNode;
import ecnu.db.generator.constraintchain.filter.arithmetic.ArithmeticNodeDeserializer;

import java.io.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author xuechao.lian
 */
public class CommonUtils {

    private CommonUtils() {
    }

    public static final int DECIMAL_DIVIDE_SCALE = 10;

    public static final String CANONICAL_NAME_CONTACT_SYMBOL = ".";
    public static final String CANONICAL_NAME_SPLIT_REGEX = "\\.";
    public static final int SAMPLE_DOUBLE_PRECISION = (int) 1E6;
    public static final CsvMapper CSV_MAPPER = new CsvMapper();

    public static final DateTimeFormatter INPUT_FMT = new DateTimeFormatterBuilder()
            .appendOptional(new DateTimeFormatterBuilder().appendPattern("yyyy-MM-dd HH:mm:ss")
                    .appendFraction(ChronoField.MILLI_OF_SECOND, 2, 3, true) // min 2 max 3
                    .toFormatter())
            .appendOptional(new DateTimeFormatterBuilder().appendPattern("yyyy-MM-dd HH:mm:ss").toFormatter())
            .appendOptional(new DateTimeFormatterBuilder().appendPattern("yyyy-MM-dd")
                    .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
                    .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
                    .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0).toFormatter())
            .appendOptional(DateTimeFormatter.ISO_LOCAL_DATE)
            .toFormatter();
    public static final DateTimeFormatter dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault());
    public static final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.systemDefault());
    private static final SimpleModule touchStoneJsonModule = new SimpleModule()
            .addDeserializer(ArithmeticNode.class, new ArithmeticNodeDeserializer())
            .addDeserializer(ConstraintChainNode.class, new ConstraintChainNodeDeserializer())
            .addDeserializer(BoolExprNode.class, new BoolExprNodeDeserializer());
    private static final DefaultPrettyPrinter dpf = new DefaultPrettyPrinter();
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
            .setDefaultPrettyPrinter(dpf)
            .registerModule(new JavaTimeModule()).registerModule(touchStoneJsonModule);

    static {
        dpf.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
    }

    public static boolean isNotCanonicalColumnName(String columnName) {
        return columnName.split(CANONICAL_NAME_SPLIT_REGEX).length != 3;
    }

    /**
     * 获取正则表达式的匹配
     *
     * @param pattern 正则表达式
     * @param str     传入的字符串
     * @return 成功的所有匹配，一个{@code List<String>}对应一个匹配的所有group
     */
    public static List<List<String>> matchPattern(Pattern pattern, String str) {
        Matcher matcher = pattern.matcher(str);
        List<List<String>> ret = new ArrayList<>();
        while (matcher.find()) {
            List<String> groups = new ArrayList<>();
            for (int i = 0; i <= matcher.groupCount(); i++) {
                groups.add(matcher.group(i));
            }
            ret.add(groups);
        }
        return ret;
    }

    public static String readFile(String path) throws IOException {
        try (BufferedReader bufferedReader = new BufferedReader(new FileReader(path))) {
            List<String> fileContent = new ArrayList<>();
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                fileContent.add(line);
            }
            return fileContent.stream().collect(Collectors.joining(System.lineSeparator()));
        }
    }

    public static void writeFile(String path, String content) throws IOException {
        try (BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter(path))) {
            bufferedWriter.write(content);
        }
    }
}
