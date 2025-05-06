package ecnu.db.generator.constraintchain;

import ecnu.db.generator.constraintchain.filter.ConstraintChainFilterNode;
import ecnu.db.generator.constraintchain.filter.operation.AbstractFilterOperation;
import ecnu.db.schema.ColumnManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PushDownProbabilityTest {
    static final Map<String, List<AbstractFilterOperation>> query2operations = new HashMap<>();
    @BeforeAll
    static void init() throws IOException {
        String configPath = "src/test/resources/data/query-instantiation/TPCH/";
        Map<String, List<ConstraintChain>>query2chains = ConstraintChainManager.loadConstrainChainResult(configPath);
        ColumnManager.getInstance().setResultDir(configPath);
        ColumnManager.getInstance().loadColumnMetaData();
        for (String query : query2chains.keySet()) {
            List<ConstraintChain> chains = query2chains.get(query);
            for (ConstraintChain chain : chains) {
                String tableName = chain.getTableName();
                for (ConstraintChainNode node : chain.getNodes()) {
                    if (node instanceof ConstraintChainFilterNode) {
                        List<AbstractFilterOperation> operations = ((ConstraintChainFilterNode) node).pushDownProbability();
                        String key = query + "_" + tableName;
                        if (!query2operations.containsKey(key)) {
                            query2operations.put(key, new ArrayList<>());
                        }
                        query2operations.get(key).addAll(operations);
                    }
                }
            }
        }
    }
    @Test
    void getOperationsTest() {
        List<AbstractFilterOperation> operations;
        operations = query2operations.get("2_1.sql_public.part");
        assertEquals(2, operations.size());

        operations = query2operations.get("3_1.sql_public.customer");
        assertEquals(1, operations.size());
        assertEquals(0.1997866667, operations.get(0).getProbability().doubleValue(), 0.0000001);
        operations = query2operations.get("3_1.sql_public.orders");
        assertEquals(1, operations.size());
        assertEquals(0.4827473333, operations.get(0).getProbability().doubleValue(), 0.0000001);


        operations = query2operations.get("1_1.sql_public.lineitem");
        assertEquals(1, operations.size());
        assertEquals(0.9928309517, operations.get(0).getProbability().doubleValue(), 0.0000001);
    }

    @Test
    void getMultiOperationsTest() {
        List<AbstractFilterOperation> operations;
        operations = query2operations.get("19_1.sql_public.lineitem");
        assertEquals(8, operations.size());
        assertEquals(0.01924177021, operations.get(0).getProbability().doubleValue(), 0.0000001);

        operations = query2operations.get("19_1.sql_public.lineitem");
        assertEquals(8, operations.size());
        assertEquals(1.0, operations.get(1).getProbability().doubleValue(), 0.0000001);

        operations = query2operations.get("19_1.sql_public.lineitem");
        assertEquals(8, operations.size());
        assertEquals(1.0, operations.get(2).getProbability().doubleValue(), 0.0000001);

        operations = query2operations.get("19_1.sql_public.part");
        assertEquals(10, operations.size());
        assertEquals(0.00243, operations.get(1).getProbability().doubleValue(), 0.0000001);

        operations = query2operations.get("19_1.sql_public.part");
        assertEquals(10, operations.size());
        assertEquals(0.00243, operations.get(3).getProbability().doubleValue(), 0.0000001);

        operations = query2operations.get("19_1.sql_public.part");
        assertEquals(10, operations.size());
        assertEquals(0, operations.get(6).getProbability().doubleValue(), 0.0000001);

        operations = query2operations.get("6_1.sql_public.lineitem");
        assertEquals(5, operations.size());
        assertEquals(0.0190413108, operations.get(0).getProbability().doubleValue(), 0.0000001);

        operations = query2operations.get("6_1.sql_public.lineitem");
        assertEquals(5, operations.size());
        assertEquals(1.0, operations.get(1).getProbability().doubleValue(), 0.0000001);

        operations = query2operations.get("6_1.sql_public.lineitem");
        assertEquals(5, operations.size());
        assertEquals(1.0, operations.get(2).getProbability().doubleValue(), 0.0000001);

        operations = query2operations.get("6_1.sql_public.lineitem");
        assertEquals(5, operations.size());
        assertEquals(1.0, operations.get(3).getProbability().doubleValue(), 0.0000001);
    }
}