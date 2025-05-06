package ecnu.db.generator.constraintchain.agg;

import com.fasterxml.jackson.annotation.JsonIgnore;
import ecnu.db.LanguageManager;
import ecnu.db.generator.ConstructCpModel;
import ecnu.db.generator.constraintchain.ConstraintChainNode;
import ecnu.db.generator.constraintchain.ConstraintChainNodeType;
import ecnu.db.generator.constraintchain.filter.ConstraintChainFilterNode;
import ecnu.db.generator.constraintchain.filter.Parameter;
import ecnu.db.schema.TableManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

public class ConstraintChainAggregateNode extends ConstraintChainNode {
    private final Logger logger = LoggerFactory.getLogger(ConstraintChainAggregateNode.class);
    private final ResourceBundle rb = LanguageManager.getInstance().getRb();
    @JsonIgnore
    public int joinStatusIndex = -1;
    private List<String> groupKey;
    private BigDecimal aggProbability;
    ConstraintChainFilterNode aggFilter;


    public ConstraintChainAggregateNode(List<String> groupKeys, BigDecimal aggProbability) {
        super(ConstraintChainNodeType.AGGREGATE);
        this.groupKey = groupKeys;
        this.aggProbability = aggProbability.stripTrailingZeros();
    }

    public ConstraintChainAggregateNode() {
        super(ConstraintChainNodeType.AGGREGATE);
    }

    public BigDecimal getAggProbability() {
        return aggProbability;
    }

    public void setAggProbability(BigDecimal aggProbability) {
        this.aggProbability = aggProbability.stripTrailingZeros();
    }

    public boolean removeAgg() {
        // 如果filter含有虚参，则不能被约减。其需要参与计算。
        if (aggFilter != null && aggFilter.getParameters().stream().anyMatch(parameter -> parameter.getType() == Parameter.ParameterType.VIRTUAL)) {
            return false;
        }
        // filter不再需要被计算，只需要考虑group key的情况
        // 如果没有group key 则不需要进行分布控制 无需考虑
        if (groupKey == null) {
            return true;
        }
        // 清理group key， 如果含有参照表的外键，则clean被参照表的group key
        cleanGroupKeys();
        // 如果group key中全部是外键 则需要控制外键分布 不能删减
        if (groupKey.stream().allMatch(key -> TableManager.getInstance().isForeignKey(key))) {
            return false;
        }
        // 如果group key中包含主键 且无法支持 提示报错
        if (groupKey.stream().anyMatch(key -> TableManager.getInstance().isPrimaryKey(key))) {
            logger.error(rb.getString("AggOperatorCannotBeSupportedInQuery"), this);
        }
        return true;
    }

    public void addJoinDistinctConstraint(ConstructCpModel cpModel, long filterSize, boolean[][] canBeInput) {
        if (joinStatusIndex < 0) {
            return;
        }
        for (int filterIndex = 0; filterIndex < canBeInput.length; filterIndex++) {
            for (int pkStatusIndex = 0; pkStatusIndex < canBeInput[0].length; pkStatusIndex++) {
                if (canBeInput[filterIndex][pkStatusIndex]) {
                    cpModel.addJoinDistinctValidVar(joinStatusIndex, filterIndex, pkStatusIndex);
                }
            }
        }
        var bPkSize = BigDecimal.valueOf(filterSize).multiply(aggProbability);
        long pkSize = bPkSize.setScale(0, RoundingMode.HALF_UP).longValue();
        // 合法性约束，每个pkStatus不能超过提供的数量
        cpModel.addJoinCardinalityConstraint(pkSize);
    }

    private void cleanGroupKeys() {
        TreeMap<String, List<String>> table2keys = new TreeMap<>();
        for (String key : groupKey) {
            String[] array = key.split("\\.");
            String tableName = array[0] + "." + array[1];
            table2keys.computeIfAbsent(tableName, v -> new ArrayList<>());
            table2keys.get(tableName).add(key);
        }
        // todo filter the attributes of the same table
        // todo mutiple key columns
        if (table2keys.size() == 1) {
            return;
        }
        List<String> topologicalOrder = TableManager.getInstance().createTopologicalOrder();
        Collections.reverse(topologicalOrder);
        // 从参照表到被参照表进行访问
        for (String tableName : topologicalOrder) {
            List<String> keys = table2keys.get(tableName);
            // if the first group attribute is fk, remove all its referenced table
            // todo 参照关系和groupkey可能不一致
            if (keys != null && keys.stream().anyMatch(key -> TableManager.getInstance().isForeignKey(key))) {
                var tableNames = table2keys.keySet().stream()
                        .filter(currentTable -> TableManager.getInstance().isRefTable(tableName, currentTable)).toList();
                for (String currentTable : tableNames) {
                    List<String> groupKeys = table2keys.get(currentTable);
                    logger.debug("remove invalid group key {} from node {}", groupKeys, this);
                    groupKey.removeAll(groupKeys);
                }
            }
        }
    }

    public ConstraintChainFilterNode getAggFilter() {
        return aggFilter;
    }

    public void setAggFilter(ConstraintChainFilterNode aggFilter) {
        this.aggFilter = aggFilter;
    }

    @Override
    public String toString() {
        return String.format("{GroupKey:%s, aggProbability:%s}", groupKey, aggProbability);
    }

    public List<String> getGroupKey() {
        return groupKey;
    }

    public void setGroupKey(List<String> groupKey) {
        this.groupKey = groupKey;
    }
}
