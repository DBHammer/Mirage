package ecnu.db.generator.constraintchain;

import com.fasterxml.jackson.core.type.TypeReference;
import ecnu.db.generator.constraintchain.filter.ConstraintChainFilterNode;
import ecnu.db.generator.constraintchain.filter.Parameter;
import ecnu.db.generator.constraintchain.filter.arithmetic.ArithmeticNode;
import ecnu.db.schema.ColumnManager;
import ecnu.db.utils.CommonUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static ecnu.db.analyzer.QueryInstantiate.queryInstantiation;
import static org.junit.jupiter.api.Assertions.assertEquals;

class QueryInstantiationMultiVarTest {
    private final static int samplingSize = 10_000;
    Map<String, List<ConstraintChain>> query2chains;

    @BeforeEach
    public void setUp() throws IOException {
        ArithmeticNode.setSize(samplingSize);
        String fileContent = CommonUtils.readFile("src/test/resources/data/query-instantiation/multi-var-test/constraintChain.json");
        query2chains = CommonUtils.MAPPER.readValue(fileContent, new TypeReference<>() {
        });
        ColumnManager.getInstance().setResultDir("src/test/resources/data/query-instantiation/multi-var-test");
        ColumnManager.getInstance().loadColumnMetaData();
    }

    @Test
    void computeMultiVarTest() throws Exception {

        // *********************************
        // *    test query instantiation   *
        // *********************************
        queryInstantiation(query2chains.values().stream().flatMap(Collection::stream).collect(Collectors.toList()), samplingSize);
        Map<Integer, Parameter> id2Parameter = new HashMap<>();
        for (String key : query2chains.keySet()) {
            List<Parameter> parameters = query2chains.get(key).stream().flatMap((l) -> l.getParameters().stream()).toList();
            parameters.forEach((param) -> id2Parameter.put(param.getId(), param));
        }
        // known distribution c2:[2,23], c3[-3,9], c4[0,10]
        Float[] c2 = new Float[samplingSize], c3 = new Float[samplingSize], c4 = new Float[samplingSize];
        Random random = new Random();
        initVectorData(samplingSize, c2, 2, 23, random);
        initVectorData(samplingSize, c3, -3, 9, random);
        initVectorData(samplingSize, c4, 0, 10, random);
        // ====================== t1.sql_1: c2 + 2 * c3 * c4 > p0 (ratio = 0.3270440252)
        Float[] v = new Float[samplingSize];
        for (int i = 0; i < samplingSize; i++) {
            v[i] = c2[i] + 2 * c3[i] * c4[i];
        }
        Arrays.sort(v);
        double target = v[(int) ((1 - 0.3270440252) * samplingSize)];
        String parameterValue = id2Parameter.get(0).getDataValue().replace("'","").trim();
        assertEquals(target, Double.parseDouble(parameterValue), 3);
        // ====================== t1.sql_2: c2 + 2 * c3 + c4 > p1 (ratio = 0.8364779874)
        for (int i = 0; i < samplingSize; i++) {
            v[i] = c2[i] + 2 * c3[i] + c4[i];
        }
        Arrays.sort(v);
        target = v[(int) ((1 - 0.8364779874) * samplingSize)];
        parameterValue = id2Parameter.get(1).getDataValue().replace("'","").trim();
        assertEquals(target, Double.parseDouble(parameterValue), 3);

        // ******************************
        // *    test data generation    *
        // ******************************
        List<ConstraintChain> chains;
        double rate;
        chains = query2chains.get("t1_1.sql");
        rate = getRate(chains);
        assertEquals(0.3270440252, rate, 0.03);

        chains = query2chains.get("t1_2.sql");
        rate = getRate(chains);
        assertEquals(0.8364779874, rate, 0.03);

    }

    private double getRate(List<ConstraintChain> chains) {
        for (ConstraintChain chain : chains) {
            for (ConstraintChainNode node : chain.getNodes()) {
                if (node instanceof ConstraintChainFilterNode) {
                    boolean[] evaluation = ((ConstraintChainFilterNode) node).getRoot().evaluate();
                    return IntStream.range(0, evaluation.length).filter((i) -> evaluation[i]).count() * 1.0 / evaluation.length;
                }
            }
        }
        return -1;
    }

    public void initVectorData(int test_size, Float[] c2, int c2_min, int c2_max, Random random) {
        for (int i = 0; i < test_size; i++) {
            c2[i] = random.nextFloat() * (c2_max - c2_min) + c2_min;
        }
    }
}
