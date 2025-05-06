package ecnu.db.generator.constraintchain.filter.operation;

/**
 * @author wangqingshuai
 * 比较算子Tag
 * 顺序不能改变，用于算子的合并，
 */

public enum CompareOperator {
    /**
     * 大于或等于
     */
    GE,
    /**
     * 比较运算符，大于
     */
    GT,
    /**
     * 小于或等于
     */
    LE,
    /**
     * 比较运算符，小于
     */
    LT,
    /**
     * 比较运算符，等于
     */
    EQ,
    /**
     * 比较运算符，不等于
     */
    NE,
    /**
     * 比较运算符，相似
     */
    LIKE,
    /**
     * 比较运算符，不相似
     */
    NOT_LIKE,
    /**
     * 比较运算符，包含
     */
    IN,
    /**
     * 比较运算符，不包含
     */
    NOT_IN,
    /**
     * ISNULL运算符
     */
    ISNULL,
    /**
     * ISNOTNULL运算符
     */
    IS_NOT_NULL,
    /**
     * RANGE运算符，表示多个lt,gt,le,ge的整合，不直接在parser中使用
     */
    RANGE;

    public static String toSQL(CompareOperator operator) {
        return " " + switch (operator) {
            case GE -> ">=";
            case LE -> "<=";
            case EQ -> "=";
            case NE -> "<>";
            case IN -> "in";
            case NOT_IN -> "not in";
            case NOT_LIKE -> "not like";
            case LIKE -> "like";
            case GT -> ">";
            case LT -> "<";
            case IS_NOT_NULL -> "is not null";
            case ISNULL -> "is null";
            default -> throw new UnsupportedOperationException();
        } + " ";
    }

    public boolean isEqual() {
        return switch (this) {
            case EQ, LIKE, IN, NE, NOT_LIKE, NOT_IN -> true;
            case GT, LT, GE, LE -> false;
            default -> throw new UnsupportedOperationException();
        };
    }

    public boolean isBigger() {
        return switch (this) {
            case GT, GE -> true;
            default -> false;
        };
    }

    public boolean isMultiEqual() {
        return switch (this) {
            case IN, NOT_IN -> true;
            case GT, LT, GE, LE, EQ, LIKE, NE, NOT_LIKE -> false;
            default -> throw new UnsupportedOperationException();
        };
    }

}
