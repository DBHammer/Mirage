package ecnu.db.dbconnector;

import ecnu.db.utils.exception.TouchstoneException;

import java.util.List;
import java.util.Map;

public class DumpFileConnector implements DatabaseConnectorInterface {

    public static final String DUMP_FILE_POSTFIX = "dump";

    private final Map<String, List<String[]>> queryPlanMap;

    private final Map<String, Integer> multiColumnsNdvMap;

    public DumpFileConnector(Map<String, List<String[]>> queryPlanMap, Map<String, Integer> multiColumnsNdvMap) {
        this.queryPlanMap = queryPlanMap;
        this.multiColumnsNdvMap = multiColumnsNdvMap;
    }


    @Override
    public List<String[]> explainQuery(String queryCanonicalName, String sql, String[] sqlInfoColumns) throws TouchstoneException {
        List<String[]> queryPlan = this.queryPlanMap.get(String.format("%s.%s", queryCanonicalName, DUMP_FILE_POSTFIX));
        if (queryPlan == null) {
            throw new TouchstoneException(String.format("cannot find query plan for %s", queryCanonicalName));
        }
        return queryPlan;
    }

    @Override
    public int getMultiColNdv(String canonicalTableName, String columns) throws TouchstoneException {
        Integer ndv = this.multiColumnsNdvMap.get(String.format("%s.%s", canonicalTableName, columns));
        if (ndv == null) {
            throw new TouchstoneException(String.format("cannot find multicolumn ndv information for schema:%s, cols:%s", canonicalTableName, columns));
        }
        return ndv;
    }

    @Override
    public Map<String, Integer> getMultiColNdvMap() {
        return this.multiColumnsNdvMap;
    }
}
