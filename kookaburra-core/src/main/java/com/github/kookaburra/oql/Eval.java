package com.github.kookaburra.oql;

import com.github.kookaburra.Ref;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.Temporal;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Evaluates OQL expressions (design §9.9): two-valued logic (comparisons with NULL are false), integer
 * arithmetic on integral operands, {@code ||} concatenation, CASE, LIKE and scalar functions.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class Eval {

    /** Values of paths, parameters and aggregates in the current row / group. */
    interface Env {
        Object path(Ast.Path p);

        Object param(Ast.Param p);

        Object agg(Ast.Agg a);

        /** Stored class name of the current object (for TYPE()). */
        String className(String alias);
    }

    private Eval() {
    }

    static boolean truth(Object v) {
        return Boolean.TRUE.equals(v);
    }

    static Object eval(Ast.Expr e, Env env) {
        if (e instanceof Ast.Lit l) {
            return l.value();
        }
        if (e instanceof Ast.Param p) {
            return env.param(p);
        }
        if (e instanceof Ast.Path p) {
            return env.path(p);
        }
        if (e instanceof Ast.Agg a) {
            return env.agg(a);
        }
        if (e instanceof Ast.Unary u) {
            Object v = eval(u.expr(), env);
            if (u.op().equals("NOT")) {
                return !truth(v);
            }
            if (v == null) {
                return null;
            }
            if (v instanceof Double d) {
                return -d;
            }
            return -number(v, "-").longValue();
        }
        if (e instanceof Ast.Binary b) {
            return binary(b, env);
        }
        if (e instanceof Ast.Between b) {
            Object v = eval(b.expr(), env);
            Object lo = eval(b.lo(), env);
            Object hi = eval(b.hi(), env);
            boolean r = v != null && lo != null && hi != null && compare(v, lo) >= 0 && compare(v, hi) <= 0;
            return r != b.not();
        }
        if (e instanceof Ast.InList in) {
            Object v = eval(in.expr(), env);
            boolean r = false;
            if (v != null) {
                for (Ast.Expr x : in.items()) {
                    Object o = eval(x, env);
                    if (o != null && compare(v, o) == 0) {
                        r = true;
                        break;
                    }
                }
            }
            return r != in.not();
        }
        if (e instanceof Ast.Like l) {
            Object v = eval(l.expr(), env);
            Object p = eval(l.pattern(), env);
            Object esc = l.escape() == null ? null : eval(l.escape(), env);
            boolean r = v != null && p != null && like(String.valueOf(v), String.valueOf(p), esc == null ? null : String.valueOf(esc));
            return r != l.not();
        }
        if (e instanceof Ast.IsNull n) {
            return (eval(n.expr(), env) == null) != n.not();
        }
        if (e instanceof Ast.IsEmpty n) {
            Object v = eval(n.expr(), env);
            boolean empty = v == null || (v instanceof Collection<?> c && c.isEmpty()) || (v instanceof Map<?, ?> m && m.isEmpty());
            return empty != n.not();
        }
        if (e instanceof Ast.Member m) {
            Object v = eval(m.value(), env);
            Object c = eval(m.collection(), env);
            boolean r = false;
            if (v != null && c instanceof Collection<?> coll) {
                for (Object o : coll) {
                    if (o != null && compare(v, o) == 0) {
                        r = true;
                        break;
                    }
                }
            }
            return r != m.not();
        }
        if (e instanceof Ast.TypeIn t) {
            return t.classes().contains(env.className(t.alias())) != t.not();
        }
        if (e instanceof Ast.Case c) {
            for (int i = 0; i < c.whens().size(); i++) {
                if (truth(eval(c.whens().get(i), env))) {
                    return eval(c.thens().get(i), env);
                }
            }
            return c.otherwise() == null ? null : eval(c.otherwise(), env);
        }
        return function((Ast.Func) e, env);
    }

    private static Object binary(Ast.Binary b, Env env) {
        String op = b.op();
        if (op.equals("AND")) {
            return truth(eval(b.left(), env)) && truth(eval(b.right(), env));
        }
        if (op.equals("OR")) {
            return truth(eval(b.left(), env)) || truth(eval(b.right(), env));
        }
        Object l = eval(b.left(), env);
        Object r = eval(b.right(), env);
        switch (op) {
            case "=", "<>", "<", "<=", ">", ">=": {
                if (l == null || r == null) {
                    return false;
                }
                int c = compare(l, r);
                return switch (op) {
                    case "=" -> c == 0;
                    case "<>" -> c != 0;
                    case "<" -> c < 0;
                    case "<=" -> c <= 0;
                    case ">" -> c > 0;
                    default -> c >= 0;
                };
            }
            case "||":
                return l == null || r == null ? null : text(l) + text(r);
            default:
                return arithmetic(op, l, r);
        }
    }

    private static Object arithmetic(String op, Object l, Object r) {
        if (l == null || r == null) {
            return null;
        }
        Number a = number(l, op);
        Number b = number(r, op);
        if (integral(a) && integral(b)) {
            long x = a.longValue();
            long y = b.longValue();
            return switch (op) {
                case "+" -> x + y;
                case "-" -> x - y;
                case "*" -> x * y;
                case "/" -> {
                    if (y == 0) {
                        throw new OqlException("division by zero");
                    }
                    yield x / y;
                }
                default -> {
                    if (y == 0) {
                        throw new OqlException("division by zero");
                    }
                    yield x % y;
                }
            };
        }
        double x = a.doubleValue();
        double y = b.doubleValue();
        return switch (op) {
            case "+" -> x + y;
            case "-" -> x - y;
            case "*" -> x * y;
            case "/" -> x / y;
            default -> x % y;
        };
    }

    private static Object function(Ast.Func f, Env env) {
        List<Ast.Expr> args = f.args();
        switch (f.name()) {
            case "COALESCE": {
                for (Ast.Expr a : args) {
                    Object v = eval(a, env);
                    if (v != null) {
                        return v;
                    }
                }
                return null;
            }
            case "NULLIF": {
                arity(f, 2);
                Object a = eval(args.get(0), env);
                Object b = eval(args.get(1), env);
                return a != null && b != null && compare(a, b) == 0 ? null : a;
            }
            case "CONCAT": {
                StringBuilder sb = new StringBuilder();
                for (Ast.Expr a : args) {
                    Object v = eval(a, env);
                    if (v == null) {
                        return null;
                    }
                    sb.append(text(v));
                }
                return sb.toString();
            }
            case "SIZE": {
                arity(f, 1);
                Object v = eval(args.get(0), env);
                return v instanceof Collection<?> c ? (long) c.size() : v instanceof Map<?, ?> m ? (long) m.size() : 0L;
            }
            default:
                break;
        }
        Object[] v = new Object[args.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = eval(args.get(i), env);
            if (v[i] == null) {
                return null;
            }
        }
        return switch (f.name()) {
            case "UPPER" -> {
                arity(f, 1);
                yield text(v[0]).toUpperCase(Locale.ROOT);
            }
            case "LOWER" -> {
                arity(f, 1);
                yield text(v[0]).toLowerCase(Locale.ROOT);
            }
            case "TRIM" -> {
                arity(f, 1);
                yield text(v[0]).strip();
            }
            case "LENGTH" -> {
                arity(f, 1);
                yield (long) text(v[0]).length();
            }
            case "SUBSTRING" -> {
                if (v.length != 2 && v.length != 3) {
                    throw new OqlException("SUBSTRING takes 2 or 3 arguments", f.line(), f.column());
                }
                String s = text(v[0]);
                int start = (int) Math.max(1, number(v[1], "SUBSTRING").longValue()) - 1;
                if (start >= s.length()) {
                    yield "";
                }
                int end = v.length == 3 ? (int) Math.min(s.length(), start + Math.max(0, number(v[2], "SUBSTRING").longValue())) : s.length();
                yield s.substring(start, end);
            }
            case "ABS" -> {
                arity(f, 1);
                Number n = number(v[0], "ABS");
                yield integral(n) ? (Object) Math.abs(n.longValue()) : (Object) Math.abs(n.doubleValue());
            }
            case "MOD" -> {
                arity(f, 2);
                yield arithmetic("%", v[0], v[1]);
            }
            default -> throw new OqlException("unknown function " + f.name(), f.line(), f.column());
        };
    }

    private static void arity(Ast.Func f, int n) {
        if (f.args().size() != n) {
            throw new OqlException(f.name() + " takes " + n + " argument" + (n == 1 ? "" : "s"), f.line(), f.column());
        }
    }

    private static String text(Object v) {
        return v instanceof Ref<?> r ? String.valueOf(r.oid()) : String.valueOf(v);
    }

    private static boolean integral(Number n) {
        return !(n instanceof Double || n instanceof Float);
    }

    private static Number number(Object v, String op) {
        if (v instanceof Number n) {
            return n;
        }
        if (v instanceof Ref<?> r) {
            return r.oid();
        }
        throw new OqlException("'" + op + "' needs numbers, got " + v.getClass().getSimpleName() + " " + v);
    }

    /** Orders comparable values of compatible types (numbers across types, refs as OIDs, temporals from strings). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static int compare(Object a, Object b) {
        if ((a instanceof Number || a instanceof Ref) && (b instanceof Number || b instanceof Ref)) {
            Number x = number(a, "compare");
            Number y = number(b, "compare");
            return integral(x) && integral(y) ? Long.compare(x.longValue(), y.longValue())
                    : Double.compare(x.doubleValue(), y.doubleValue());
        }
        if (a instanceof Temporal && b instanceof String s) {
            b = parseLike(a, s);
        } else if (b instanceof Temporal && a instanceof String s) {
            a = parseLike(b, s);
        }
        if (a.getClass() == b.getClass() && a instanceof Comparable c) {
            return c.compareTo(b);
        }
        if (a instanceof Collection || a instanceof Map) {
            return Objects.equals(a, b) ? 0 : 1;
        }
        throw new OqlException("cannot compare " + a.getClass().getSimpleName() + " " + a + " with "
                + b.getClass().getSimpleName() + " " + b);
    }

    private static Object parseLike(Object sample, String s) {
        if (sample instanceof LocalDate) {
            return LocalDate.parse(s);
        }
        if (sample instanceof LocalDateTime) {
            return LocalDateTime.parse(s.replace(' ', 'T'));
        }
        if (sample instanceof Instant) {
            return Instant.parse(s.replace(' ', 'T'));
        }
        return s;
    }

    /** SQL LIKE: {@code %} any run, {@code _} one character, optional escape character. */
    static boolean like(String value, String pattern, String escape) {
        return toRegex(pattern, escape).matcher(value).matches();
    }

    static Pattern toRegex(String pattern, String escape) {
        if (escape != null && escape.length() != 1) {
            throw new OqlException("ESCAPE must be a single character");
        }
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (escape != null && c == escape.charAt(0) && i + 1 < pattern.length()) {
                re.append(Pattern.quote(String.valueOf(pattern.charAt(++i))));
            } else if (c == '%') {
                re.append(".*");
            } else if (c == '_') {
                re.append('.');
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(re.toString(), Pattern.DOTALL);
    }

    /** {@code prefix} if the pattern is {@code 'prefix%'} without other wildcards / escapes, else null. */
    static String prefixOf(String pattern) {
        if (!pattern.endsWith("%")) {
            return null;
        }
        String p = pattern.substring(0, pattern.length() - 1);
        return p.indexOf('%') < 0 && p.indexOf('_') < 0 ? p : null;
    }
}
