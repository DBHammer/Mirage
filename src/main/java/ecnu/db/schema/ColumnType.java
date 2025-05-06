package ecnu.db.schema;

import com.fasterxml.jackson.annotation.JsonFormat;
import ecnu.db.LanguageManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Types;
import java.util.ResourceBundle;

/**
 * @author wangqingshuai
 */
@JsonFormat(shape = JsonFormat.Shape.STRING)
public enum ColumnType {
    /* 定义类型的列，可根据配置文件将类型映射到这些类型*/
    INTEGER, VARCHAR, DECIMAL, BOOL, DATE, DATETIME;

    private static final Logger logger = LoggerFactory.getLogger(ColumnType.class);
    private static final ResourceBundle rb = LanguageManager.getInstance().getRb();

    public static ColumnType getColumnType(int dataType) {
        return switch (dataType) {
            case Types.INTEGER, Types.BIGINT, Types.SMALLINT, Types.BOOLEAN, Types.BIT -> INTEGER;
            case Types.VARCHAR, Types.CHAR -> VARCHAR;
            case Types.FLOAT, Types.DOUBLE, Types.DECIMAL, Types.NUMERIC -> DECIMAL;
            case Types.DATE -> DATE;
            case Types.TIMESTAMP, Types.TIME -> DATETIME;
            default -> {
                logger.error(rb.getString("unsupportedOperatorConversions"), dataType);
                throw new UnsupportedOperationException();
            }
        };
    }

    public boolean isHasCardinalityConstraint() {
        return this == INTEGER || this == VARCHAR;
    }
}
