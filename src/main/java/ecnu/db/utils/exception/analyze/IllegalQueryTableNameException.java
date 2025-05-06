package ecnu.db.utils.exception.analyze;

import ecnu.db.utils.exception.TouchstoneException;

/**
 * @author wangqingshuai
 */
public class IllegalQueryTableNameException extends TouchstoneException {
    public IllegalQueryTableNameException() {
        super("查询中未包含数据库名");
    }
}
