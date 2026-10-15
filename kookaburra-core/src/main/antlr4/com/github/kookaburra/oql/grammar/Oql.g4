/*
 * OQL — Kookaburra Object Query Language (design §9.9).
 * JPQL syntax, SQL-92 core semantics, single collection per query.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
grammar Oql;

options { caseInsensitive = true; }

// ---------------------------------------------------------------- statements

statement
    : EXPLAIN? (selectStmt | updateStmt | deleteStmt) ';'? EOF
    ;

selectStmt
    : SELECT DISTINCT? selectList fromClause joinClause* whereClause? groupClause? havingClause? orderClause? sliceClause?
    ;

selectList
    : STAR                                                              # selectStar
    | NEW qualifiedName '(' selectItem (',' selectItem)* ')'            # selectNew
    | selectItem (',' selectItem)*                                      # selectItems
    ;

selectItem
    : expr (AS? identifier)?
    ;

fromClause
    : FROM qualifiedName (AS? identifier)?
    ;

joinClause
    : (INNER | LEFT OUTER?)? JOIN path AS? identifier
    ;

whereClause  : WHERE expr ;
groupClause  : GROUP BY expr (',' expr)* ;
havingClause : HAVING expr ;
orderClause  : ORDER BY orderItem (',' orderItem)* ;

orderItem
    : expr dir=(ASC | DESC)? (NULLS nulls=(FIRST | LAST))?
    ;

sliceClause
    : LIMIT expr (OFFSET expr)?
    | OFFSET expr (LIMIT expr)?
    ;

updateStmt
    : UPDATE qualifiedName (AS? identifier)? SET setItem (',' setItem)* whereClause?
    ;

setItem
    : path '=' expr
    ;

deleteStmt
    : DELETE FROM qualifiedName (AS? identifier)? whereClause?
    ;

// ---------------------------------------------------------------- expressions
// Layered like SQL: OR < AND < NOT < predicate < value expression < primary. Predicate operands are value
// expressions, so BETWEEN … AND … and LIKE … never swallow a following AND / OR.

expr    : orExpr ;
orExpr  : andExpr (OR andExpr)* ;
andExpr : notExpr (AND notExpr)* ;

notExpr
    : NOT notExpr                                                       # notOp
    | predicate                                                         # predOp
    ;

predicate
    : TYPE '(' identifier ')' NOT? IN '(' qualifiedName (',' qualifiedName)* ')'   # typeInPred
    | TYPE '(' identifier ')' cmp qualifiedName                         # typeCmpPred
    | valueExpr cmp valueExpr                                           # cmpPred
    | valueExpr NOT? BETWEEN valueExpr AND valueExpr                    # betweenPred
    | valueExpr NOT? IN '(' expr (',' expr)* ')'                        # inPred
    | valueExpr NOT? LIKE valueExpr (ESCAPE valueExpr)?                 # likePred
    | valueExpr IS NOT? NULL                                            # isNullPred
    | valueExpr IS NOT? EMPTY                                           # isEmptyPred
    | valueExpr NOT? MEMBER OF? valueExpr                               # memberPred
    | valueExpr                                                         # valuePred
    ;

valueExpr
    : op='-' valueExpr                                                  # negExpr
    | valueExpr op=('*' | '/' | '%') valueExpr                          # mulExpr
    | valueExpr op=('+' | '-') valueExpr                                # addExpr
    | valueExpr '||' valueExpr                                          # concatExpr
    | primary                                                           # primaryExpr
    ;

primary
    : '(' expr ')'                                                      # parenExpr
    | CASE (WHEN expr THEN expr)+ (ELSE expr)? END                      # caseExpr
    | aggregate                                                         # aggExpr
    | identifier '(' (expr (',' expr)*)? ')'                            # funcExpr
    | literal                                                           # litExpr
    | param                                                             # paramExpr
    | path                                                              # pathExpr
    ;

aggregate
    : COUNT '(' STAR ')'                                                # countStar
    | fn=(COUNT | SUM | AVG | MIN | MAX) '(' DISTINCT? expr ')'         # aggCall
    ;

cmp
    : '=' | '<>' | '!=' | '<' | '<=' | '>' | '>='
    ;

literal
    : INTEGER                   # intLit
    | DECIMAL                   # decLit
    | STRING                    # strLit
    | TRUE                      # trueLit
    | FALSE                     # falseLit
    | NULL                      # nullLit
    | DATE STRING               # dateLit
    | TIMESTAMP STRING          # timestampLit
    | INSTANT STRING            # instantLit
    ;

param
    : ':' identifier            # namedParam
    | '?' INTEGER               # positionalParam
    ;

path          : identifier ('.' segment)* ;

// after a dot every word is a field name, keywords included (e.g. route.from.city, order.by)
segment
    : identifier
    | SELECT | DISTINCT | FROM | WHERE | GROUP | BY | HAVING | ORDER | ASC | DESC | AS | NEW | JOIN | UPDATE | SET
    | DELETE | AND | OR | NOT | BETWEEN | IN | LIKE | IS | NULL | CASE | WHEN | THEN | ELSE | END | TRUE | FALSE
    ;
qualifiedName : identifier ('.' identifier)* ;

identifier
    : IDENT
    | QUOTED_IDENT
    | DATE | TIMESTAMP | INSTANT | TYPE | FIRST | LAST | EMPTY | MEMBER | OF | KEY | VALUE
    | COUNT | SUM | AVG | MIN | MAX | LEFT | INNER | OUTER | NULLS | ESCAPE | OFFSET | LIMIT | EXPLAIN
    ;

// ---------------------------------------------------------------- tokens

SELECT : 'select' ;   DISTINCT : 'distinct' ;  FROM : 'from' ;     WHERE : 'where' ;
GROUP : 'group' ;     BY : 'by' ;              HAVING : 'having' ; ORDER : 'order' ;
ASC : 'asc' ;         DESC : 'desc' ;          NULLS : 'nulls' ;   FIRST : 'first' ;   LAST : 'last' ;
LIMIT : 'limit' ;     OFFSET : 'offset' ;      AS : 'as' ;         NEW : 'new' ;
JOIN : 'join' ;       INNER : 'inner' ;        LEFT : 'left' ;     OUTER : 'outer' ;
UPDATE : 'update' ;   SET : 'set' ;            DELETE : 'delete' ; EXPLAIN : 'explain' ;
AND : 'and' ;         OR : 'or' ;              NOT : 'not' ;       BETWEEN : 'between' ;
IN : 'in' ;           LIKE : 'like' ;          ESCAPE : 'escape' ; IS : 'is' ;
NULL : 'null' ;       EMPTY : 'empty' ;        MEMBER : 'member' ; OF : 'of' ;
TYPE : 'type' ;       CASE : 'case' ;          WHEN : 'when' ;     THEN : 'then' ;
ELSE : 'else' ;       END : 'end' ;            TRUE : 'true' ;     FALSE : 'false' ;
COUNT : 'count' ;     SUM : 'sum' ;            AVG : 'avg' ;       MIN : 'min' ;       MAX : 'max' ;
DATE : 'date' ;       TIMESTAMP : 'timestamp' ; INSTANT : 'instant' ;
KEY : 'key' ;         VALUE : 'value' ;
STAR : '*' ;

INTEGER      : [0-9]+ ;
DECIMAL      : [0-9]+ '.' [0-9]* EXPONENT? | '.' [0-9]+ EXPONENT? | [0-9]+ EXPONENT ;
fragment EXPONENT : 'e' [+-]? [0-9]+ ;
STRING       : '\'' ( ~'\'' | '\'\'' )* '\'' ;
IDENT        : [a-z_$] [a-z0-9_$]* ;
QUOTED_IDENT : '`' ~'`'+ '`' ;

LINE_COMMENT  : '--' ~[\r\n]* -> channel(HIDDEN) ;
BLOCK_COMMENT : '/*' .*? '*/' -> channel(HIDDEN) ;
WS            : [ \t\r\n]+ -> channel(HIDDEN) ;
ERROR_CHAR    : . ;
