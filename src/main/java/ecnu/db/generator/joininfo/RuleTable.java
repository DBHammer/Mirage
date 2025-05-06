package ecnu.db.generator.joininfo;

import ecnu.db.generator.FkGenerator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RuleTable {
    private static boolean expandRuleMap = false;

    public static void openExpandRuleMap(){
        expandRuleMap = true;
    }

    Map<JoinStatus, List<PkRange>> rules = new HashMap<>();

    public void addRule(JoinStatus status, long start, long end) {
        rules.computeIfAbsent(status, value -> new ArrayList<>());
        if (expandRuleMap) {
            List<PkRange> ranges = rules.get(status);
            long i = start;
            for (; i <= end - 2; i += 2) {
                ranges.add(new PkRange(i, i + 2));
            }
            if (i < end) {
                ranges.add(new PkRange(i, end));
            }
        } else {
            rules.get(status).add(new PkRange(start, end));
        }
    }

    public MergedRuleTable mergeRules(int[] location) {
        Map<JoinStatus, List<PkRange>> mergedRules = new HashMap<>();
        for (Map.Entry<JoinStatus, List<PkRange>> joinStatusListEntry : rules.entrySet()) {
            boolean[] joinStatus = joinStatusListEntry.getKey().status();
            JoinStatus pkStatus = FkGenerator.chooseCorrespondingStatus(joinStatus, location);
            mergedRules.computeIfAbsent(pkStatus, v -> new ArrayList<>());
            mergedRules.get(pkStatus).addAll(joinStatusListEntry.getValue());
        }
        return new MergedRuleTable(mergedRules);
    }
}
