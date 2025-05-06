package ecnu.db.utils.exception.analyze;

import ecnu.db.utils.exception.TouchstoneException;

/**
 * @author alan
 */
public class IllegalCharacterException extends TouchstoneException {
    public IllegalCharacterException(String character, int line, long begin) {
        super(String.format("非法字符 %s at line:%d pos:%s", character, line, begin));
    }
}
