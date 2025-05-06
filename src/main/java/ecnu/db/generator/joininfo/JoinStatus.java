package ecnu.db.generator.joininfo;

import java.util.Arrays;

public record JoinStatus(boolean[] status) {
    @Override
    public String toString() {
        return "JoinStatus{" +
                "status=" + Arrays.toString(status) +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JoinStatus that = (JoinStatus) o;
        return Arrays.equals(status, that.status);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(status);
    }
}
