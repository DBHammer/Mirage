package ecnu.db.generator.constraintchain;

import com.fasterxml.jackson.core.type.TypeReference;
import ecnu.db.utils.CommonUtils;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

import static ecnu.db.utils.CommonUtils.readFile;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ReadAndWriteJsonTest {
    private static final String dir = "src/test/resources/data/query-instantiation/TPCH/";
    private static final String DISTRIBUTION_DIR = "/distribution";

    @Test
    void writeTestConstraintChain() throws IOException {
        String content = getConstraintChainForAllSQL();
        Map<String, List<ConstraintChain>> query2chains = CommonUtils.MAPPER.readValue(content, new TypeReference<>() {
        });
        String contentWrite = CommonUtils.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(query2chains);
        content = content.replaceAll(System.lineSeparator(), "");
        contentWrite = contentWrite.replaceAll(System.lineSeparator(), "");
        assertEquals(content, contentWrite);
    }

    @Test
    void writeTestStringTemplate() throws IOException {
        String content = readFile(dir + DISTRIBUTION_DIR + "/stringTemplate.json");
        Map<String, Map<Long, boolean[]>> columName2StringTemplate = CommonUtils.MAPPER.readValue(content, new TypeReference<>() {
        });
        String contentWrite = CommonUtils.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(columName2StringTemplate);
        assertEquals(content, contentWrite);
    }

    @Test
    void writeTestDistribution() throws IOException {
        String content = readFile(dir + DISTRIBUTION_DIR + "/distribution.json");
        Map<String, SortedMap<Long, BigDecimal>> bucket2Probabilities = CommonUtils.MAPPER.readValue(content, new TypeReference<>() {
        });
        String contentWrite = CommonUtils.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(bucket2Probabilities);
        assertEquals(content, contentWrite);
    }

    @Test
    void writeTestBoundPara() throws IOException {
        String content = readFile(dir + DISTRIBUTION_DIR + "/boundPara.json");
        Map<String, SortedMap<BigDecimal, Long>> boundPara = CommonUtils.MAPPER.readValue(content, new TypeReference<>() {
        });
        String contentWrite = CommonUtils.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(boundPara);
        assertEquals(content, contentWrite);
    }

    private String getConstraintChainForAllSQL() throws IOException {
        String path = dir + "/workload";
        File sqlDic = new File(path);
        File[] sqlArray = sqlDic.listFiles();
        assert sqlArray != null;
        StringBuilder result = new StringBuilder();
        for (File file : sqlArray) {
            File[] graphArray = file.listFiles();
            assert graphArray != null;
            for (File file1 : graphArray) {
                if (file1.getName().contains("json")) {
                    String eachCC = readFile(file1.getPath());
                    //去掉前后大括号
                    eachCC = eachCC.substring(1, eachCC.length() - 1);
                    eachCC += ",";
                    result.append(eachCC);
                }
            }
        }
        result = new StringBuilder("{" + result.substring(0, result.length() - 1) + "}");
        return result.toString();
    }
}
