package ecnu.db.utils.exception.schema;

import ecnu.db.utils.exception.TouchstoneException;

/**
 * @author alan
 */
public class CannotFindColumnException extends TouchstoneException {
    public CannotFindColumnException(String columnName) {
        super(String.format("找不到'%s'对应的Column", columnName));
    }
}
