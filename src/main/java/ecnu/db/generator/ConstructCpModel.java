package ecnu.db.generator;

import com.google.ortools.Loader;
import com.google.ortools.sat.*;
import ecnu.db.LanguageManager;
import ecnu.db.generator.joininfo.JoinStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class ConstructCpModel {

    private static final double DISTINCT_FK_SKEW = 2;
    private final Logger logger = LoggerFactory.getLogger(ConstructCpModel.class);
    private final CpModel model = new CpModel();
    private final CpSolver solver = new CpSolver();
    private IntVar[][] vars;
    private final Map<Integer, IntVar[][]> fkDistinctVars = new HashMap<>();
    private final List<IntVar> involvedVars = new LinkedList<>();

    private final Map<Integer, Map<Integer, Set<IntVar>>> fkSharePkVars = new HashMap<>();

    private final Map<Integer, List<List<IntVar>>> fkDistinctInvolvedVars = new HashMap<>();

    private final ResourceBundle rb = LanguageManager.getInstance().getRb();

    static {
        Loader.loadNativeLibraries();
    }

    public long[][] solve() {
        logger.debug("num of vars is {}", model.model().getVariablesCount());
        solver.getParameters().setEnumerateAllSolutions(false);
        solver.getParameters().setNumWorkers(Runtime.getRuntime().availableProcessors());
        CpSolverStatus status = solver.solve(model);
        if (status == CpSolverStatus.OPTIMAL || status == CpSolverStatus.FEASIBLE) {
            logger.info(rb.getString("constructCpModelCostTime"), solver.wallTime() * 1000);
            int filterStatusCount = vars.length;
            int pkStatusCount = vars[0].length;
            long[][] rowCountForEachStatus = new long[filterStatusCount][pkStatusCount];
            for (int filterIndex = 0; filterIndex < filterStatusCount; filterIndex++) {
                for (int pkStatusIndex = 0; pkStatusIndex < pkStatusCount; pkStatusIndex++) {
                    rowCountForEachStatus[filterIndex][pkStatusIndex] = solver.value(vars[filterIndex][pkStatusIndex]);
                }
            }
            return rowCountForEachStatus;
        } else {
            throw new UnsupportedOperationException("No solution found.");
        }
    }

    public FkRange[][] getDistinctResult(int fkColIndex) {
        var involvedFkVars = fkDistinctInvolvedVars.remove(fkColIndex);
        FkRange[][] fkRanges = new FkRange[vars.length][vars[0].length];
        for (List<IntVar> samePkStatusVars : involvedFkVars) {
            int start = 0;
            for (IntVar samePkStatusVar : samePkStatusVars) {
                int range = (int) solver.value(samePkStatusVar);
                String[] tags = samePkStatusVar.getName().split("-");
                int filterIndex = Integer.parseInt(tags[1]);
                int pkIndex = Integer.parseInt(tags[2]);
                fkRanges[filterIndex][pkIndex] = new FkRange(start, range);
                start += range;
            }
        }
        for (int filterIndex = 0; filterIndex < vars.length; filterIndex++) {
            for (int pkIndex = 0; pkIndex < vars[0].length; pkIndex++) {
                if (fkRanges[filterIndex][pkIndex] == null) {
                    fkRanges[filterIndex][pkIndex] = new FkRange(-1, -1);
                }
            }
        }
        return fkRanges;
    }

    public void initDistinctModel(int fkColIndex, long fkColCardinality, long fkTableSize) {
        IntVar[][] distinctVars = new IntVar[vars.length][vars[0].length];
        // todo 用fk的最大重复次数来替代
        fkTableSize = (long) (fkTableSize * DISTINCT_FK_SKEW);
        for (int filterIndex = 0; filterIndex < distinctVars.length; filterIndex++) {
            for (int pkIndex = 0; pkIndex < distinctVars[0].length; pkIndex++) {
                IntVar numVar = vars[filterIndex][pkIndex];
                String varName = fkColIndex + "-" + filterIndex + "-" + pkIndex;
                IntVar distinctVar = model.newIntVarFromDomain(numVar.getDomain(), varName);
                model.addLessOrEqual(distinctVar, numVar);
                // distinct的外键均匀分布与每个range中, i.e., x / tableSize <= d/fkColCardinality
                model.addLessOrEqual(LinearExpr.term(numVar, fkColCardinality), LinearExpr.term(distinctVar, fkTableSize));
                distinctVars[filterIndex][pkIndex] = distinctVar;
            }
        }
        fkSharePkVars.put(fkColIndex, new HashMap<>());
        fkDistinctVars.put(fkColIndex, distinctVars);
    }

    public void applyFKShareConstraint(int fkColIndex, Map<ArrayList<Integer>, Long> samePkStatusIndexes2Limitations) {
        var pkIndex2IntVar = fkSharePkVars.remove(fkColIndex);
        fkDistinctInvolvedVars.put(fkColIndex, new ArrayList<>());
        for (var pkIndexes2Limitation : samePkStatusIndexes2Limitations.entrySet()) {
            var samePkStatusIndexes = pkIndexes2Limitation.getKey();
            List<IntVar> sharedFk = new ArrayList<>();
            for (Integer samePkStatusIndex : samePkStatusIndexes) {
                if (pkIndex2IntVar.containsKey(samePkStatusIndex)) {
                    sharedFk.addAll(pkIndex2IntVar.get(samePkStatusIndex));
                }
            }
            if (!sharedFk.isEmpty()) {
                fkDistinctInvolvedVars.get(fkColIndex).add(sharedFk);
                model.addLessOrEqual(LinearExpr.sum(sharedFk.toArray(new IntVar[0])), pkIndexes2Limitation.getValue());
            }
        }
    }


    /**
     * 根据join info table计算不同status的填充数量
     *
     * @param filterHistogram  filter status的统计直方图
     * @param pkJointStatusNum 所有联合主键的数量
     * @param range            每个填充方案的的上界
     */
    public void initModel(Map<JoinStatus, Long> filterHistogram, int pkJointStatusNum, int range) {
        vars = new IntVar[filterHistogram.size()][pkJointStatusNum];
        for (int i = 0; i < filterHistogram.size(); i++) {
            for (int j = 0; j < pkJointStatusNum; j++) {
                vars[i][j] = model.newIntVar(0, range, i + "-" + j);
            }
        }
        int i = 0;
        for (Map.Entry<JoinStatus, Long> status2Size : filterHistogram.entrySet()) {
            model.addEquality(LinearExpr.sum(vars[i++]), status2Size.getValue());
        }
    }

    public void addJoinDistinctValidVar(int fkColIndex, int filterIndex, int pkStatusIndex) {
        IntVar fkVar = fkDistinctVars.get(fkColIndex)[filterIndex][pkStatusIndex];
        involvedVars.add(fkVar);
        var pkIndex2IntVar = fkSharePkVars.get(fkColIndex);
        pkIndex2IntVar.computeIfAbsent(pkStatusIndex, v -> new HashSet<>());
        pkIndex2IntVar.get(pkStatusIndex).add(fkVar);
    }

    public void addJoinCardinalityValidVar(int filterIndex, int pkStatusIndex) {
        involvedVars.add(vars[filterIndex][pkStatusIndex]);
    }

    public void addJoinCardinalityConstraint(long eqJoinSize) {
        if (eqJoinSize == 1 || eqJoinSize == 2) {
            model.addLinearConstraint(LinearExpr.sum(involvedVars.toArray(new IntVar[0])), eqJoinSize - 2, eqJoinSize + 2);
        } else {
            model.addLinearConstraint(LinearExpr.sum(involvedVars.toArray(new IntVar[0])), (long) (eqJoinSize*0.96), (long) (eqJoinSize*1.04));
        }

        involvedVars.clear();
    }
}
