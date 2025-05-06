package ecnu.db.generator.constraintchain.join;

import com.fasterxml.jackson.annotation.JsonIgnore;
import ecnu.db.LanguageManager;
import ecnu.db.generator.ConstructCpModel;
import ecnu.db.generator.constraintchain.ConstraintChainNode;
import ecnu.db.generator.constraintchain.ConstraintChainNodeType;
import ecnu.db.generator.joininfo.JoinStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ResourceBundle;

/**
 * @author wangqingshuai
 */
public class ConstraintChainFkJoinNode extends ConstraintChainNode {
    @JsonIgnore
    public int joinStatusIndex;
    @JsonIgnore
    public int joinStatusLocation;
    private String refCols;
    private String localCols;
    private int pkTag;
    private BigDecimal probability;
    private BigDecimal probabilityWithFailFilter;
    private BigDecimal pkDistinctProbability;
    private ConstraintNodeJoinType type = ConstraintNodeJoinType.INNER_JOIN;

    @JsonIgnore
    private boolean[] joinResultStatus;

    @JsonIgnore
    private final Logger logger = LoggerFactory.getLogger(ConstraintChainFkJoinNode.class);

    public ConstraintChainFkJoinNode() {
        super(ConstraintChainNodeType.FK_JOIN);
    }

    private final ResourceBundle rb = LanguageManager.getInstance().getRb();

    public ConstraintChainFkJoinNode(String localCols, String refCols, int pkTag, BigDecimal probability) {
        super(ConstraintChainNodeType.FK_JOIN);
        this.refCols = refCols;
        this.pkTag = pkTag;
        this.localCols = localCols;
        this.probability = probability;
    }

    public ConstraintNodeJoinType getType() {
        return type;
    }

    public void setType(ConstraintNodeJoinType type) {
        this.type = type;
    }

    public BigDecimal getPkDistinctProbability() {
        return pkDistinctProbability;
    }

    public void setPkDistinctProbability(BigDecimal pkDistinctProbability) {
        this.pkDistinctProbability = pkDistinctProbability;
    }

    @Override
    public String toString() {
        return String.format("{pkTag:%d,refCols:%s,probability:%s,pkDistinctProbability:%f,probabilityWithFailFilter:%s}", pkTag, refCols, probability, pkDistinctProbability, probabilityWithFailFilter);
    }

    public int getPkTag() {
        return pkTag;
    }

    public void setPkTag(int pkTag) {
        this.pkTag = pkTag;
    }

    public BigDecimal getProbability() {
        return probability;
    }

    public void setProbability(BigDecimal probability) {
        this.probability = probability.stripTrailingZeros();
    }

    public String getLocalCols() {
        return localCols;
    }

    public void setLocalCols(String localCols) {
        this.localCols = localCols;
    }

    public String getRefCols() {
        return refCols;
    }

    public void setRefCols(String refCols) {
        this.refCols = refCols;
    }

    public BigDecimal getProbabilityWithFailFilter() {
        return probabilityWithFailFilter;
    }

    public void setProbabilityWithFailFilter(BigDecimal probabilityWithFailFilter) {
        this.probabilityWithFailFilter = probabilityWithFailFilter.stripTrailingZeros();
    }

    private void addIndexJoinCardinalityConstraint(ConstructCpModel cpModel, long unFilterSize, boolean[][] canBeInput) {
        for (int filterIndex = 0; filterIndex < canBeInput.length; filterIndex++) {
            for (int pkStatusIndex = 0; pkStatusIndex < canBeInput[0].length; pkStatusIndex++) {
                if (!canBeInput[filterIndex][pkStatusIndex] && joinResultStatus[pkStatusIndex]) {
                    cpModel.addJoinCardinalityValidVar(filterIndex, pkStatusIndex);
                }
            }
        }
        BigDecimal bIndexJoinSize = BigDecimal.valueOf(unFilterSize).multiply(probabilityWithFailFilter);
        long indexJoinSize = bIndexJoinSize.setScale(0, RoundingMode.HALF_UP).longValue();
        cpModel.addJoinCardinalityConstraint(indexJoinSize);
        logger.info(rb.getString("indexJoinInfo"), indexJoinSize, joinStatusIndex, joinStatusLocation);
    }

    private long addJoinCardinalityConstraint(ConstructCpModel cpModel, long filterSize, boolean[][] canBeInput) {
        for (int filterIndex = 0; filterIndex < canBeInput.length; filterIndex++) {
            for (int pkStatusIndex = 0; pkStatusIndex < canBeInput[0].length; pkStatusIndex++) {
                if (canBeInput[filterIndex][pkStatusIndex] && joinResultStatus[pkStatusIndex]) {
                    cpModel.addJoinCardinalityValidVar(filterIndex, pkStatusIndex);
                } else {
                    canBeInput[filterIndex][pkStatusIndex] = false;
                }
            }
        }
        BigDecimal bFilterSize = BigDecimal.valueOf(filterSize).multiply(probability);
        filterSize = bFilterSize.setScale(0, RoundingMode.HALF_UP).longValue();
        cpModel.addJoinCardinalityConstraint(filterSize);
        logger.info(rb.getString("statusDataOutput"), filterSize, joinStatusLocation, joinStatusIndex);
        return filterSize;
    }

    public void addJoinDistinctConstraint(ConstructCpModel cpModel, long filterSize, boolean[][] canBeInput) {
        if (!type.hasCardinalityConstraint()) {
            return;
        }
        // 获取join对应的位置
        for (int filterIndex = 0; filterIndex < canBeInput.length; filterIndex++) {
            for (int pkStatusIndex = 0; pkStatusIndex < canBeInput[0].length; pkStatusIndex++) {
                if (canBeInput[filterIndex][pkStatusIndex] && joinResultStatus[pkStatusIndex]) {
                    cpModel.addJoinDistinctValidVar(joinStatusIndex, filterIndex, pkStatusIndex);
                }
            }
        }

        var bPkSize = BigDecimal.valueOf(filterSize).multiply(pkDistinctProbability);
        long pkSize = bPkSize.setScale(0, RoundingMode.HALF_UP).longValue();
        logger.info(rb.getString("addDistinctConstraint"), this, pkSize);
        // 合法性约束，每个pkStatus不能超过提供的数量
        cpModel.addJoinCardinalityConstraint(pkSize);
    }

    public long addJoinCardinalityConstraint(ConstructCpModel cpModel, long filterSize, long unFilerSize, boolean[][] canBeInput) {
        if (type.isSemi()) {
            return filterSize;
        }
        if (probabilityWithFailFilter != null) {
            addIndexJoinCardinalityConstraint(cpModel, unFilerSize, canBeInput);
        }
        return addJoinCardinalityConstraint(cpModel, filterSize, canBeInput);
    }

    public void initJoinResultStatus(JoinStatus[][] pkJointStatus) {
        joinResultStatus = new boolean[pkJointStatus.length];
        boolean status = !type.isAnti();
        for (int i = 0; i < pkJointStatus.length; i++) {
            joinResultStatus[i] = pkJointStatus[i][joinStatusIndex].status()[joinStatusLocation] == status;
        }
    }
}
