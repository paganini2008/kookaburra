package com.github.kookaburra.oql;

import com.github.kookaburra.oql.grammar.OqlBaseVisitor;
import com.github.kookaburra.oql.grammar.OqlLexer;
import com.github.kookaburra.oql.grammar.OqlParser;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.misc.Interval;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses OQL text (ANTLR {@code Oql.g4}) into an {@link Ast}.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class AstBuilder extends OqlBaseVisitor<Object> {

    private final String text;

    private AstBuilder(String text) {
        this.text = text;
    }

    /** Parses one statement; throws {@link OqlException} with the position of the first syntax error. */
    public static Ast.Statement parse(String text) {
        BaseErrorListener fail = new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offending, int line, int col, String msg,
                                    RecognitionException e) {
                throw new OqlException("syntax error: " + readable(msg, text), line, col);
            }
        };
        OqlLexer lexer = new OqlLexer(CharStreams.fromString(text));
        lexer.removeErrorListeners();
        lexer.addErrorListener(fail);
        OqlParser parser = new OqlParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(fail);
        return (Ast.Statement) new AstBuilder(text).visit(parser.statement());
    }

    private static final Pattern EXPECTING = Pattern.compile("expecting \\{([^}]*)}");

    /** ANTLR messages trimmed for people: unterminated strings named, long "expecting {…}" sets summarised. */
    static String readable(String msg, String text) {
        if (text.chars().filter(c -> c == '\'').count() % 2 == 1) {          // '' (an escaped quote) counts twice
            return "unterminated string literal (strings use single quotes; write '' for a quote inside)";
        }
        Matcher m = EXPECTING.matcher(msg);
        if (m.find() && m.group(1).split(",").length > 6) {
            return msg.substring(0, m.start()).strip() + " (unexpected here)";
        }
        return msg;
    }

    private String source(ParserRuleContext ctx) {
        return text.substring(ctx.start.getStartIndex(), ctx.stop.getStopIndex() + 1);
    }

    private static String id(OqlParser.IdentifierContext ctx) {
        String t = ctx.getText();
        return t.startsWith("`") ? t.substring(1, t.length() - 1) : t;
    }

    private Ast.Expr expr(OqlParser.ExprContext ctx) {
        return (Ast.Expr) visit(ctx);
    }

    private List<Ast.Expr> exprs(List<OqlParser.ExprContext> ctx) {
        List<Ast.Expr> out = new ArrayList<>();
        ctx.forEach(c -> out.add(expr(c)));
        return out;
    }

    // ---------------------------------------------------------------- statements

    @Override
    public Object visitStatement(OqlParser.StatementContext ctx) {
        boolean explain = ctx.EXPLAIN() != null;
        if (ctx.selectStmt() != null) {
            return select(ctx.selectStmt(), explain);
        }
        if (ctx.updateStmt() != null) {
            OqlParser.UpdateStmtContext u = ctx.updateStmt();
            List<Ast.SetItem> sets = new ArrayList<>();
            for (OqlParser.SetItemContext s : u.setItem()) {
                sets.add(new Ast.SetItem(path(s.path()), expr(s.expr())));
            }
            return new Ast.Update(explain, from(u.qualifiedName(), u.identifier(), u.start.getLine(), u.start.getCharPositionInLine()),
                    sets, u.whereClause() == null ? null : expr(u.whereClause().expr()));
        }
        OqlParser.DeleteStmtContext d = ctx.deleteStmt();
        return new Ast.Delete(explain, from(d.qualifiedName(), d.identifier(), d.start.getLine(), d.start.getCharPositionInLine()),
                d.whereClause() == null ? null : expr(d.whereClause().expr()));
    }

    private Ast.From from(OqlParser.QualifiedNameContext name, OqlParser.IdentifierContext alias, int line, int col) {
        return new Ast.From(qualified(name), alias == null ? null : id(alias), line, col);
    }

    private static String qualified(OqlParser.QualifiedNameContext q) {
        List<String> parts = new ArrayList<>();
        q.identifier().forEach(i -> parts.add(id(i)));
        return String.join(".", parts);
    }

    private Ast.Select select(OqlParser.SelectStmtContext s, boolean explain) {
        Ast.Shape shape;
        String newClass = null;
        List<Ast.SelectItem> items = new ArrayList<>();
        OqlParser.SelectListContext list = s.selectList();
        if (list instanceof OqlParser.SelectStarContext) {
            shape = Ast.Shape.STAR;
        } else if (list instanceof OqlParser.SelectNewContext n) {
            shape = Ast.Shape.NEW;
            newClass = qualified(n.qualifiedName());
            n.selectItem().forEach(i -> items.add(item(i)));
        } else {
            shape = Ast.Shape.ITEMS;
            ((OqlParser.SelectItemsContext) list).selectItem().forEach(i -> items.add(item(i)));
        }
        OqlParser.FromClauseContext f = s.fromClause();
        List<Ast.Join> joins = new ArrayList<>();
        for (OqlParser.JoinClauseContext j : s.joinClause()) {
            joins.add(new Ast.Join(path(j.path()), id(j.identifier()), j.LEFT() != null));
        }
        List<Ast.Expr> groupBy = s.groupClause() == null ? List.of() : exprs(s.groupClause().expr());
        List<Ast.OrderItem> orderBy = new ArrayList<>();
        if (s.orderClause() != null) {
            for (OqlParser.OrderItemContext o : s.orderClause().orderItem()) {
                Boolean nullsFirst = o.nulls == null ? null : o.nulls.getType() == OqlParser.FIRST;
                orderBy.add(new Ast.OrderItem(expr(o.expr()), o.dir != null && o.dir.getType() == OqlParser.DESC, nullsFirst));
            }
        }
        Ast.Expr limit = null;
        Ast.Expr offset = null;
        OqlParser.SliceClauseContext slice = s.sliceClause();
        if (slice != null) {
            boolean limitFirst = slice.getChild(0).getText().equalsIgnoreCase("limit");
            List<Ast.Expr> e = exprs(slice.expr());
            if (limitFirst) {
                limit = e.get(0);
                offset = e.size() > 1 ? e.get(1) : null;
            } else {
                offset = e.get(0);
                limit = e.size() > 1 ? e.get(1) : null;
            }
        }
        return new Ast.Select(explain, s.DISTINCT() != null, shape, newClass, items,
                from(f.qualifiedName(), f.identifier(), f.start.getLine(), f.start.getCharPositionInLine()), joins,
                s.whereClause() == null ? null : expr(s.whereClause().expr()), groupBy,
                s.havingClause() == null ? null : expr(s.havingClause().expr()), orderBy, limit, offset);
    }

    private Ast.SelectItem item(OqlParser.SelectItemContext i) {
        return new Ast.SelectItem(expr(i.expr()), i.identifier() == null ? null : id(i.identifier()), source(i.expr()));
    }

    private Ast.Path path(OqlParser.PathContext p) {
        List<String> parts = new ArrayList<>();
        parts.add(id(p.identifier()));
        for (OqlParser.SegmentContext seg : p.segment()) {
            parts.add(seg.identifier() != null ? id(seg.identifier()) : seg.getText());   // a keyword used as a field name
        }
        return new Ast.Path(parts, p.start.getLine(), p.start.getCharPositionInLine());
    }

    // ---------------------------------------------------------------- expressions

    private Ast.Expr value(OqlParser.ValueExprContext ctx) {
        return (Ast.Expr) visit(ctx);
    }

    @Override
    public Object visitExpr(OqlParser.ExprContext ctx) {
        return visit(ctx.orExpr());
    }

    @Override
    public Object visitOrExpr(OqlParser.OrExprContext ctx) {
        Ast.Expr e = (Ast.Expr) visit(ctx.andExpr(0));
        for (int i = 1; i < ctx.andExpr().size(); i++) {
            e = new Ast.Binary("OR", e, (Ast.Expr) visit(ctx.andExpr(i)));
        }
        return e;
    }

    @Override
    public Object visitAndExpr(OqlParser.AndExprContext ctx) {
        Ast.Expr e = (Ast.Expr) visit(ctx.notExpr(0));
        for (int i = 1; i < ctx.notExpr().size(); i++) {
            e = new Ast.Binary("AND", e, (Ast.Expr) visit(ctx.notExpr(i)));
        }
        return e;
    }

    @Override
    public Object visitNotOp(OqlParser.NotOpContext ctx) {
        return new Ast.Unary("NOT", (Ast.Expr) visit(ctx.notExpr()));
    }

    @Override
    public Object visitPredOp(OqlParser.PredOpContext ctx) {
        return visit(ctx.predicate());
    }

    @Override
    public Object visitCmpPred(OqlParser.CmpPredContext ctx) {
        String op = ctx.cmp().getText();
        return new Ast.Binary(op.equals("!=") ? "<>" : op, value(ctx.valueExpr(0)), value(ctx.valueExpr(1)));
    }

    @Override
    public Object visitBetweenPred(OqlParser.BetweenPredContext ctx) {
        return new Ast.Between(value(ctx.valueExpr(0)), value(ctx.valueExpr(1)), value(ctx.valueExpr(2)), ctx.NOT() != null);
    }

    @Override
    public Object visitInPred(OqlParser.InPredContext ctx) {
        return new Ast.InList(value(ctx.valueExpr()), exprs(ctx.expr()), ctx.NOT() != null);
    }

    @Override
    public Object visitLikePred(OqlParser.LikePredContext ctx) {
        return new Ast.Like(value(ctx.valueExpr(0)), value(ctx.valueExpr(1)),
                ctx.valueExpr().size() > 2 ? value(ctx.valueExpr(2)) : null, ctx.NOT() != null);
    }

    @Override
    public Object visitIsNullPred(OqlParser.IsNullPredContext ctx) {
        return new Ast.IsNull(value(ctx.valueExpr()), ctx.NOT() != null);
    }

    @Override
    public Object visitIsEmptyPred(OqlParser.IsEmptyPredContext ctx) {
        return new Ast.IsEmpty(value(ctx.valueExpr()), ctx.NOT() != null);
    }

    @Override
    public Object visitMemberPred(OqlParser.MemberPredContext ctx) {
        return new Ast.Member(value(ctx.valueExpr(0)), value(ctx.valueExpr(1)), ctx.NOT() != null);
    }

    @Override
    public Object visitTypeInPred(OqlParser.TypeInPredContext ctx) {
        List<String> classes = new ArrayList<>();
        ctx.qualifiedName().forEach(q -> classes.add(qualified(q)));
        return new Ast.TypeIn(id(ctx.identifier()), classes, ctx.NOT() != null, ctx.start.getLine(), ctx.start.getCharPositionInLine());
    }

    @Override
    public Object visitTypeCmpPred(OqlParser.TypeCmpPredContext ctx) {
        String op = ctx.cmp().getText();
        if (!op.equals("=") && !op.equals("<>") && !op.equals("!=")) {
            throw new OqlException("TYPE() only supports = and <>", ctx.start.getLine(), ctx.start.getCharPositionInLine());
        }
        return new Ast.TypeIn(id(ctx.identifier()), List.of(qualified(ctx.qualifiedName())), !op.equals("="),
                ctx.start.getLine(), ctx.start.getCharPositionInLine());
    }

    @Override
    public Object visitValuePred(OqlParser.ValuePredContext ctx) {
        return visit(ctx.valueExpr());
    }

    @Override
    public Object visitNegExpr(OqlParser.NegExprContext ctx) {
        Ast.Expr e = value(ctx.valueExpr());
        if (e instanceof Ast.Lit l && l.value() instanceof Long n) {
            return new Ast.Lit(-n);
        }
        if (e instanceof Ast.Lit l && l.value() instanceof Double d) {
            return new Ast.Lit(-d);
        }
        return new Ast.Unary("-", e);
    }

    @Override
    public Object visitMulExpr(OqlParser.MulExprContext ctx) {
        return new Ast.Binary(ctx.op.getText(), value(ctx.valueExpr(0)), value(ctx.valueExpr(1)));
    }

    @Override
    public Object visitAddExpr(OqlParser.AddExprContext ctx) {
        return new Ast.Binary(ctx.op.getText(), value(ctx.valueExpr(0)), value(ctx.valueExpr(1)));
    }

    @Override
    public Object visitConcatExpr(OqlParser.ConcatExprContext ctx) {
        return new Ast.Binary("||", value(ctx.valueExpr(0)), value(ctx.valueExpr(1)));
    }

    @Override
    public Object visitPrimaryExpr(OqlParser.PrimaryExprContext ctx) {
        return visit(ctx.primary());
    }

    @Override
    public Object visitParenExpr(OqlParser.ParenExprContext ctx) {
        return expr(ctx.expr());
    }

    @Override
    public Object visitCaseExpr(OqlParser.CaseExprContext ctx) {
        List<Ast.Expr> all = exprs(ctx.expr());
        int pairs = ctx.WHEN().size();
        List<Ast.Expr> whens = new ArrayList<>();
        List<Ast.Expr> thens = new ArrayList<>();
        for (int i = 0; i < pairs; i++) {
            whens.add(all.get(2 * i));
            thens.add(all.get(2 * i + 1));
        }
        return new Ast.Case(whens, thens, ctx.ELSE() != null ? all.get(all.size() - 1) : null);
    }

    @Override
    public Object visitAggExpr(OqlParser.AggExprContext ctx) {
        return visit(ctx.aggregate());
    }

    @Override
    public Object visitCountStar(OqlParser.CountStarContext ctx) {
        return new Ast.Agg("COUNT", null, false);
    }

    @Override
    public Object visitAggCall(OqlParser.AggCallContext ctx) {
        return new Ast.Agg(ctx.fn.getText().toUpperCase(Locale.ROOT), expr(ctx.expr()), ctx.DISTINCT() != null);
    }

    @Override
    public Object visitFuncExpr(OqlParser.FuncExprContext ctx) {
        return new Ast.Func(id(ctx.identifier()).toUpperCase(Locale.ROOT), exprs(ctx.expr()), ctx.start.getLine(),
                ctx.start.getCharPositionInLine());
    }

    @Override
    public Object visitLitExpr(OqlParser.LitExprContext ctx) {
        return visit(ctx.literal());
    }

    @Override
    public Object visitParamExpr(OqlParser.ParamExprContext ctx) {
        return visit(ctx.param());
    }

    @Override
    public Object visitPathExpr(OqlParser.PathExprContext ctx) {
        return path(ctx.path());
    }

    @Override
    public Object visitNamedParam(OqlParser.NamedParamContext ctx) {
        return new Ast.Param(id(ctx.identifier()), 0, ctx.start.getLine(), ctx.start.getCharPositionInLine());
    }

    @Override
    public Object visitPositionalParam(OqlParser.PositionalParamContext ctx) {
        return new Ast.Param(null, Integer.parseInt(ctx.INTEGER().getText()), ctx.start.getLine(), ctx.start.getCharPositionInLine());
    }

    // ---------------------------------------------------------------- literals

    @Override
    public Object visitIntLit(OqlParser.IntLitContext ctx) {
        try {
            return new Ast.Lit(Long.parseLong(ctx.getText()));
        } catch (NumberFormatException e) {
            throw new OqlException("integer out of range: " + ctx.getText(), ctx.start.getLine(), ctx.start.getCharPositionInLine());
        }
    }

    @Override
    public Object visitDecLit(OqlParser.DecLitContext ctx) {
        return new Ast.Lit(Double.parseDouble(ctx.getText()));
    }

    @Override
    public Object visitStrLit(OqlParser.StrLitContext ctx) {
        return new Ast.Lit(unquote(ctx.getText()));
    }

    static String unquote(String s) {
        return s.substring(1, s.length() - 1).replace("''", "'");
    }

    @Override
    public Object visitTrueLit(OqlParser.TrueLitContext ctx) {
        return new Ast.Lit(Boolean.TRUE);
    }

    @Override
    public Object visitFalseLit(OqlParser.FalseLitContext ctx) {
        return new Ast.Lit(Boolean.FALSE);
    }

    @Override
    public Object visitNullLit(OqlParser.NullLitContext ctx) {
        return new Ast.Lit(null);
    }

    @Override
    public Object visitDateLit(OqlParser.DateLitContext ctx) {
        return temporal(ctx, () -> LocalDate.parse(unquote(ctx.STRING().getText())));
    }

    @Override
    public Object visitTimestampLit(OqlParser.TimestampLitContext ctx) {
        return temporal(ctx, () -> LocalDateTime.parse(unquote(ctx.STRING().getText()).trim().replace(' ', 'T')));
    }

    @Override
    public Object visitInstantLit(OqlParser.InstantLitContext ctx) {
        return temporal(ctx, () -> Instant.parse(unquote(ctx.STRING().getText()).trim().replace(' ', 'T')));
    }

    private static Ast.Lit temporal(ParserRuleContext ctx, Supplier<Object> parse) {
        try {
            return new Ast.Lit(parse.get());
        } catch (DateTimeParseException e) {
            throw new OqlException("invalid " + ctx.start.getText().toUpperCase(Locale.ROOT) + " literal: " + e.getMessage(),
                    ctx.start.getLine(), ctx.start.getCharPositionInLine());
        }
    }

    /** Text covered by a context (for diagnostics). */
    String covered(ParserRuleContext ctx) {
        return ctx.start.getInputStream().getText(Interval.of(ctx.start.getStartIndex(), ctx.stop.getStopIndex()));
    }
}
