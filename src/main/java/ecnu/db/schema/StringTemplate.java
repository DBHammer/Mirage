package ecnu.db.schema;

import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

class StringTemplate {
    private static final char[] randomCharSet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final char[] likeRandomCharSet = "0123456789".toCharArray();

    private static final char NO_EXIST_TAIL_CHAR = '-';

    int avgLength;
    int maxLength;
    long specialValue;
    int tag;

    TreeSet<Long> subStringIndex = new TreeSet<>();

    public StringTemplate(int avgLength, int maxLength, long specialValue, long range) {
        this.avgLength = avgLength;
        this.maxLength = maxLength;
        this.specialValue = specialValue;
        if (range < 1 || maxLength == 0) {
            return;
        }
        this.tag = 1;
        while ((range /= randomCharSet.length) > 0) {
            tag++;
        }
        if (tag > this.avgLength) {
            throw new UnsupportedOperationException("无法唯一绑定");
        }

    }

    public String getParameterValue(long dataId) {
        Random random = new Random(specialValue * dataId);
        char[] values = new char[avgLength];
        if (dataId < 0) {
            values[0] = NO_EXIST_TAIL_CHAR;
            for (int i = 1; i < values.length; i++) {
                values[i] = randomCharSet[random.nextInt(randomCharSet.length)];
            }
        } else {
            int startId = 0;
            if (subStringIndex.contains(dataId)) {
                // todo : mod may be error
                values[0] = likeRandomCharSet[subStringIndex.headSet(dataId).size() % likeRandomCharSet.length];
                startId++;
            }
            for (int i = tag - 1; i >= startId; i--) {
                values[i] = randomCharSet[(int) (dataId % randomCharSet.length)];
                dataId /= randomCharSet.length;
            }
            for (int i = tag; i < values.length; i++) {
                values[i] = randomCharSet[random.nextInt(randomCharSet.length)];
            }
        }
        return new String(values);
    }

    public void addSubStringIndex(long dataId) {
        subStringIndex.add(dataId);
    }

    public Set<Long> getLikeIndex2Status() {
        return subStringIndex;
    }

    public void setLikeIndex2Status(TreeSet<Long> likeIndex) {
        this.subStringIndex = likeIndex;
    }
}