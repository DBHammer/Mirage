package ecnu.db.analyzer.online.adapter;

import java_cup.runtime.ComplexSymbolFactory;

public class Token extends ComplexSymbolFactory.ComplexSymbol {
    /**
     * token所在的第一个字符的位置，从当前行开始计数
     */
    private final int column;
    private final String[] terminalNames;

    public Token(String[] terminalNames, int type, int column) {
        this(terminalNames, type, column, null);
    }

    public Token(String[] terminalNames, int type, int column, Object value) {
        super(terminalNames[type].toLowerCase(), type, new ComplexSymbolFactory.Location(1, column), new ComplexSymbolFactory.Location(1, column), value);
        this.column = column;
        this.terminalNames = terminalNames;
    }

    @Override
    public String toString() {
        return "column "
                + column
                + ", sym: "
                + terminalNames[this.sym].toLowerCase()
                + (value == null ? "" : (", value: '" + value + "'"));
    }
}
