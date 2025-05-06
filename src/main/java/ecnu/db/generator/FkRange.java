package ecnu.db.generator;

public class FkRange {
    public FkRange(int start, int range) {
        this.start = start;
        this.range = range;
        this.totalRange = range;
    }

    int start;
    int range;

    int totalRange;
}
