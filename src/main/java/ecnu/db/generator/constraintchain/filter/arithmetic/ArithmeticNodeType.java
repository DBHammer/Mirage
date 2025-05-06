package ecnu.db.generator.constraintchain.filter.arithmetic;

/**
 * @author alan
 */
public enum ArithmeticNodeType {
    /**
     * 常数类型计算节点
     */
    CONSTANT,
    /**
     * COLUMN类型计算节点, 用于实例化
     */
    COLUMN,
    /**
     * 加类型计算节点
     */
    PLUS,
    /**
     * 减类型计算节点
     */
    MINUS,
    /**
     * 乘类型计算节点
     */
    MUL,
    /**
     * 除类型计算节点
     */
    DIV,
    /**
     * SUM类型计算节点
     */
    SUM,
    /**
     * AVG类型计算节点
     */
    AVG,
    /**
     * MIN类型计算节点
     */
    MIN,
    /**
     * MAX类型计算节点
     */
    MAX;
    public boolean isUniComparator(){
        return switch (this){
            case MAX,MIN,AVG,SUM -> true;
            default -> false;
        };
    }
}
