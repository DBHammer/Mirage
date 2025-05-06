package ecnu.db.utils.exception.analyze;

import ecnu.db.utils.exception.TouchstoneException;

/**
 * @author alan
 */
public class UnsupportedSelectionConditionException extends TouchstoneException {
    public UnsupportedSelectionConditionException(String operatorInfo) {
        super(String.format("非法的select条件 operator_info:'%s'", operatorInfo));
    }
}