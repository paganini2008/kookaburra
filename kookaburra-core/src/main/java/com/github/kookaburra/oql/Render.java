package com.github.kookaburra.oql;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Position-independent text of expressions (for matching GROUP BY items and aggregates, and explain).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class Render {

    private Render() {
    }

    static String render(Ast.Expr e) {
        if (e instanceof Ast.Lit l) {
            return l.value() instanceof String s ? "'" + s.replace("'", "''") + "'" : String.valueOf(l.value());
        }
        if (e instanceof Ast.Param p) {
            return p.name() != null ? ":" + p.name() : "?" + p.position();
        }
        if (e instanceof Ast.Path p) {
            return p.text();
        }
        if (e instanceof Ast.Unary u) {
            return u.op().equals("NOT") ? "NOT (" + render(u.expr()) + ")" : "-(" + render(u.expr()) + ")";
        }
        if (e instanceof Ast.Binary b) {
            return "(" + render(b.left()) + " " + b.op() + " " + render(b.right()) + ")";
        }
        if (e instanceof Ast.Between b) {
            return render(b.expr()) + (b.not() ? " NOT" : "") + " BETWEEN " + render(b.lo()) + " AND " + render(b.hi());
        }
        if (e instanceof Ast.InList in) {
            return render(in.expr()) + (in.not() ? " NOT" : "") + " IN " + list(in.items());
        }
        if (e instanceof Ast.Like l) {
            return render(l.expr()) + (l.not() ? " NOT" : "") + " LIKE " + render(l.pattern())
                    + (l.escape() == null ? "" : " ESCAPE " + render(l.escape()));
        }
        if (e instanceof Ast.IsNull n) {
            return render(n.expr()) + (n.not() ? " IS NOT NULL" : " IS NULL");
        }
        if (e instanceof Ast.IsEmpty n) {
            return render(n.expr()) + (n.not() ? " IS NOT EMPTY" : " IS EMPTY");
        }
        if (e instanceof Ast.Member m) {
            return render(m.value()) + (m.not() ? " NOT" : "") + " MEMBER OF " + render(m.collection());
        }
        if (e instanceof Ast.TypeIn t) {
            return "TYPE(" + t.alias() + ")" + (t.not() ? " NOT" : "") + " IN (" + String.join(", ", t.classes()) + ")";
        }
        if (e instanceof Ast.Case c) {
            StringBuilder sb = new StringBuilder("CASE");
            for (int i = 0; i < c.whens().size(); i++) {
                sb.append(" WHEN ").append(render(c.whens().get(i))).append(" THEN ").append(render(c.thens().get(i)));
            }
            if (c.otherwise() != null) {
                sb.append(" ELSE ").append(render(c.otherwise()));
            }
            return sb.append(" END").toString();
        }
        if (e instanceof Ast.Func f) {
            return f.name() + list(f.args());
        }
        Ast.Agg a = (Ast.Agg) e;
        return a.fn() + "(" + (a.arg() == null ? "*" : (a.distinct() ? "DISTINCT " : "") + render(a.arg())) + ")";
    }

    private static String list(List<Ast.Expr> es) {
        return es.stream().map(Render::render).collect(Collectors.joining(", ", "(", ")"));
    }

    /** Rebuilds {@code e} with {@code f} applied to its direct children. */
    static Ast.Expr map(Ast.Expr e, UnaryOperator<Ast.Expr> f) {
        if (e instanceof Ast.Unary u) {
            return new Ast.Unary(u.op(), f.apply(u.expr()));
        }
        if (e instanceof Ast.Binary b) {
            return new Ast.Binary(b.op(), f.apply(b.left()), f.apply(b.right()));
        }
        if (e instanceof Ast.Between b) {
            return new Ast.Between(f.apply(b.expr()), f.apply(b.lo()), f.apply(b.hi()), b.not());
        }
        if (e instanceof Ast.InList in) {
            return new Ast.InList(f.apply(in.expr()), all(in.items(), f), in.not());
        }
        if (e instanceof Ast.Like l) {
            return new Ast.Like(f.apply(l.expr()), f.apply(l.pattern()), l.escape() == null ? null : f.apply(l.escape()), l.not());
        }
        if (e instanceof Ast.IsNull n) {
            return new Ast.IsNull(f.apply(n.expr()), n.not());
        }
        if (e instanceof Ast.IsEmpty n) {
            return new Ast.IsEmpty(f.apply(n.expr()), n.not());
        }
        if (e instanceof Ast.Member m) {
            return new Ast.Member(f.apply(m.value()), f.apply(m.collection()), m.not());
        }
        if (e instanceof Ast.Case c) {
            return new Ast.Case(all(c.whens(), f), all(c.thens(), f), c.otherwise() == null ? null : f.apply(c.otherwise()));
        }
        if (e instanceof Ast.Func fn) {
            return new Ast.Func(fn.name(), all(fn.args(), f), fn.line(), fn.column());
        }
        return e;
    }

    private static List<Ast.Expr> all(List<Ast.Expr> es, UnaryOperator<Ast.Expr> f) {
        List<Ast.Expr> out = new ArrayList<>(es.size());
        es.forEach(x -> out.add(f.apply(x)));
        return out;
    }
}
