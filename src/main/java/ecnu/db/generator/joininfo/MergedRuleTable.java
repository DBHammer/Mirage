package ecnu.db.generator.joininfo;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class MergedRuleTable {
    Map<JoinStatus, Rule> status2Rule = new HashMap<>();

    private static class Rule {
        long[] beforeNums;
        long[] delta;
        int totalSize;
        long assignCounter;
        long assignMaxIndexForTheBatchCounter;

        public Rule(long[] beforeNums, long[] delta, int totalSize, long assignCounter, long assignMaxIndexForTheBatchCounter) {
            this.beforeNums = beforeNums;
            this.delta = delta;
            this.totalSize = totalSize;
            this.assignCounter = assignCounter;
            this.assignMaxIndexForTheBatchCounter = assignMaxIndexForTheBatchCounter;
        }

        public long findDelta(long fkIndex) {
            int left = 0;
            int right = beforeNums.length - 1;
            int index = 0; // 初始值为-1，表示没有找到小于等于a的元素

            while (left <= right) {
                int mid = left + (right - left) / 2;

                if (beforeNums[mid] <= fkIndex) {
                    index = mid; // 更新结果
                    left = mid + 1; // 继续在右侧查找更大的元素
                } else {
                    right = mid - 1; // 在左侧查找
                }
            }

            return delta[index];
        }
    }

    public MergedRuleTable(Map<JoinStatus, List<PkRange>> mergedRules) {
        for (Map.Entry<JoinStatus, List<PkRange>> status2PkRanges : mergedRules.entrySet()) {
            int totalNum = 0;
            long[] beforeNums = new long[status2PkRanges.getValue().size()];
            long[] delta = new long[status2PkRanges.getValue().size()];
            int i = 0;
            for (PkRange pkRange : status2PkRanges.getValue()) {
                beforeNums[i] = totalNum;
                delta[i] = pkRange.start() - totalNum;
                totalNum += (int) (pkRange.end() - pkRange.start());
                i++;
            }
            status2Rule.put(status2PkRanges.getKey(), new Rule(beforeNums, delta, totalNum, 0L, 0L));
        }
    }

    public JoinStatus[] getPkStatus(boolean withNull) {
        JoinStatus[] pkStatuses = new JoinStatus[status2Rule.size()];
        int i = 0;
        for (JoinStatus joinStatus : status2Rule.keySet()) {
            pkStatuses[i++] = joinStatus;
        }
        // deal with null
        if (withNull) {
            int statusLength = new ArrayList<>(status2Rule.keySet()).getFirst().status().length;
            JoinStatus allFalseStatus = new JoinStatus(new boolean[statusLength]);
            if (!status2Rule.containsKey(allFalseStatus)) {
                JoinStatus[] copy = new JoinStatus[pkStatuses.length + 1];
                System.arraycopy(pkStatuses, 0, copy, 0, pkStatuses.length);
                copy[copy.length - 1] = allFalseStatus;
                pkStatuses = copy;
            }
        }
        return pkStatuses;
    }

    public long getStatusSize(JoinStatus status) {
        return status2Rule.get(status).totalSize;
    }

    public void refreshRuleCounter() {
        status2Rule.values().forEach(rule -> {
            rule.assignCounter += rule.assignMaxIndexForTheBatchCounter;
            rule.assignMaxIndexForTheBatchCounter = 0;
        });
    }

    public long getKey(JoinStatus joinStatus, long index) {
        Rule rule = status2Rule.get(joinStatus);
        if (rule == null) {
            return Long.MIN_VALUE;
        }
        if (index < 0) {
            index = ThreadLocalRandom.current().nextInt(rule.totalSize);
        } else {
            if (rule.assignMaxIndexForTheBatchCounter < index) {
                rule.assignMaxIndexForTheBatchCounter = index;
            }
            index += rule.assignCounter;
        }
        return index + rule.findDelta(index);
    }


}
