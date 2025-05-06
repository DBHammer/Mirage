package ecnu.db.analyzer.online.adapter.tidb.parser;

import ecnu.db.utils.exception.analyze.IllegalCharacterException;
import ecnu.db.analyzer.online.adapter.Token;
import java_cup.runtime.*;
import ecnu.db.generator.constraintchain.filter.arithmetic.ArithmeticNodeType;
import ecnu.db.generator.constraintchain.filter.operation.CompareOperator;
%%

%public
%class TidbSelectOperatorInfoLexer
/* throws TouchstoneException */
%yylexthrow{
ecnu.db.utils.exception.TouchstoneException
%yylexthrow}

%{
  private StringBuilder str_buff = new StringBuilder();
  private Symbol symbol(int type) {
    return new Token(TidbSelectSymbol.terminalNames, type, yycolumn+1);
  }

  private Symbol symbol(int type, Object value) {
    return new Token(TidbSelectSymbol.terminalNames, type, yycolumn+1, value);
  }
%}

%implements TidbSelectSymbol
%line
%column
%state STRING_LITERAL
%unicode
%cupsym TidbSelectSymbol
%cup

/* tokens */
DIGIT=[0-9]
WHITE_SPACE_CHAR=[\n\r\ \t\b\012]
SCHEMA_NAME_CHAR=[A-Za-z0-9$_]
CANONICAL_COL_NAME=({SCHEMA_NAME_CHAR})+\.({SCHEMA_NAME_CHAR})+\.({SCHEMA_NAME_CHAR})+
FLOAT=(0|([1-9]({DIGIT}*)))\.({DIGIT}*)
INTEGER=(0|[1-9]({DIGIT}*))
DATE=(({DIGIT}{4}-{DIGIT}{2}-{DIGIT}{2} {DIGIT}{2}:{DIGIT}{2}:{DIGIT}{2}\.{DIGIT}{6})|({DIGIT}{4}-{DIGIT}{2}-{DIGIT}{2} {DIGIT}{2}:{DIGIT}{2}:{DIGIT}{2})|({DIGIT}{4}-{DIGIT}{2}-{DIGIT}{2}))
%%

<YYINITIAL> {
  /* logical operators */
  "and" {
    return symbol(AND);
  }
  "or" {
    return symbol(OR);
  }

  /* compare operators */
  "in" {
    return symbol(IN, CompareOperator.IN);
  }
  "like" {
    return symbol(LIKE, CompareOperator.LIKE);
  }
  "lt" {
    return symbol(LT, CompareOperator.LT);
  }
  "gt" {
    return symbol(GT, CompareOperator.GT);
  }
  "le" {
    return symbol(LE, CompareOperator.LE);
  }
  "ge" {
    return symbol(GE, CompareOperator.GE);
  }
  "eq" {
    return symbol(EQ, CompareOperator.EQ);
  }
  "ne" {
    return symbol(NE, CompareOperator.NE);
  }

  /* isnull operators */
  "isnull" {
    return symbol(ISNULL, CompareOperator.ISNULL);
  }

  /* arithmetic operators */
  "plus" {
    return symbol(PLUS, ArithmeticNodeType.PLUS);
  }
  "minus" {
    return symbol(MINUS, ArithmeticNodeType.MINUS);
  }
  "div" {
    return symbol(DIV, ArithmeticNodeType.DIV);
  }
  "mul" {
    return symbol(MUL, ArithmeticNodeType.MUL);
  }

  /* not operators */
  "not" {
    return symbol(NOT);
  }

  /* canonical column names */
  {CANONICAL_COL_NAME} {
    return symbol(CANONICAL_COLUMN_NAME, yytext());
  }

  /* constants */
  {DATE} {
    return symbol(DATE, yytext());
  }
  {FLOAT} {
    return symbol(FLOAT, Float.valueOf(yytext()));
  }
  {INTEGER} {
    return symbol(INTEGER, Integer.valueOf(yytext()));
  }

  /* delimiters */
  ", " {}

  /* white spaces */
  {WHITE_SPACE_CHAR}+ {}

  /* parentheses */
  \( {
     return symbol(LPAREN);
  }
  \) {
     return symbol(RPAREN);
  }

  /* string start */
  \" {
    str_buff.setLength(0); yybegin(STRING_LITERAL);
  }

}
<STRING_LITERAL> {
  \" {
    yybegin(YYINITIAL);
    return symbol(STRING, str_buff.toString());
  }
  [^\n\r\"\\]+                   { str_buff.append( yytext() ); }
  \\t                            { str_buff.append('\t'); }
  \\n                            { str_buff.append('\n'); }
  \\r                            { str_buff.append('\r'); }
  \\\"                           { str_buff.append('\"'); }
  \\                             { str_buff.append('\\'); }
}

<<EOF>>                          { return symbol(EOF); }

. {
   throw new IllegalCharacterException(yytext(), yyline + 1, yycolumn + 1);
}

