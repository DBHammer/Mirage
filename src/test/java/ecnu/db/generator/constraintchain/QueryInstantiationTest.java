package ecnu.db.generator.constraintchain;

import ecnu.db.generator.constraintchain.filter.ConstraintChainFilterNode;
import ecnu.db.schema.ColumnManager;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static ecnu.db.utils.CommonUtils.DECIMAL_DIVIDE_SCALE;
import static org.junit.jupiter.api.Assertions.assertEquals;

class QueryInstantiationTest {
    private static final BigDecimal sampleSize = BigDecimal.valueOf(400_0000L);

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "src/test/resources/data/query-instantiation/TPCH/;0.0007",
            "src/test/resources/data/query-instantiation/SSB/;0.0000005",
            "src/test/resources/data/query-instantiation/TPCDS/;0.000005"
    })
    void computeTest(String configPath, double delta) throws Exception {
        // load column configuration
        ColumnManager.getInstance().setResultDir(configPath);
        ColumnManager.getInstance().loadColumnMetaData();
        ColumnManager.getInstance().loadColumnDistribution();

        // load constraintChain configuration
        Map<String, List<ConstraintChain>> query2chains = ConstraintChainManager.loadConstrainChainResult(configPath);
        List<ConstraintChain> constraintChains = query2chains.values().stream().flatMap(Collection::stream).toList();

        // 筛选所有的filterNode
        List<ConstraintChainFilterNode> filterNodes = constraintChains.stream()
                .map(ConstraintChain::getNodes)
                .flatMap(Collection::stream)
                .filter(ConstraintChainFilterNode.class::isInstance)
                .map(ConstraintChainFilterNode.class::cast).toList();

        // 筛选涉及到的列名
        Set<String> columnNames = filterNodes.stream()
                .map(ConstraintChainFilterNode::getColumns)
                .flatMap(Collection::stream).collect(Collectors.toSet());

        // 生成测试数据集
        ColumnManager.getInstance().cacheAttributeColumn(columnNames);
        ColumnManager.getInstance().prepareGeneration(sampleSize.intValue());

        //验证每个filterNode的执行结果
        filterNodes.stream().parallel().forEach(filterNode -> {
            boolean[] evaluation = filterNode.getRoot().evaluate();
            long satisfyRowCount = IntStream.range(0, evaluation.length).filter((i) -> evaluation[i]).count();
            BigDecimal bSatisfyRowCount = BigDecimal.valueOf(satisfyRowCount);
            BigDecimal realFilterProbability = bSatisfyRowCount.divide(sampleSize, DECIMAL_DIVIDE_SCALE,RoundingMode.DOWN);
            double rate = filterNode.getProbability().subtract(realFilterProbability).doubleValue();
            assertEquals(0, rate, delta, filterNode.toString());
        });
    }
}
