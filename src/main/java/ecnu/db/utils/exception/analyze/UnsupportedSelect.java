package ecnu.db.utils.exception.analyze;

import ecnu.db.utils.exception.TouchstoneException;

/**
 * @author alan
 */
public class UnsupportedSelect extends TouchstoneException {
    public UnsupportedSelect(String operatorInfo, Exception e) {
        super(String.format("暂时不支持的select类型 operator_info:'%s'%n%s", operatorInfo, e.getMessage()));
    }
}
