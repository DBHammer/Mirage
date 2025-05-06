package ecnu.db.generator.joininfo;

/**
 * Map中记录的区间，左闭右开，即[start, end)
 * @param start
 * @param end
 */
public record PkRange(long start, long end) {
}
