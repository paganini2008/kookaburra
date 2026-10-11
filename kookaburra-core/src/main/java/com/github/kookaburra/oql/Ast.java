package com.github.kookaburra.oql;

import java.util.List;

/**
 * OQL syntax tree (design §9.9).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class Ast {

    private Ast() {
    }

    /** Expression node. */
    public sealed interface Expr {
    }

    /** Literal: Long, Double, String, Boolean, null, LocalDate, LocalDateTime, Instant. */
    public record Lit(Object value) implements Expr {
    }

    /** {@code :name} (position 0) or {@code ?n}. */
    public record Param(String name, int position, int line, int column) implements Expr {
    }

    /** Dotted path; may start with an alias. */
    public record Path(List<String> parts, int line, int column) implements Expr {
        public String text() {
            return String.join(".", parts);
        }
    }

    /** {@code -e} or {@code NOT e}. */
    public record Unary(String op, Expr expr) implements Expr {
    }

    /** {@code + - * / % || = <> < <= > >= AND OR}. */
    public record Binary(String op, Expr left, Expr right) implements Expr {
    }

    public record Between(Expr expr, Expr lo, Expr hi, boolean not) implements Expr {
    }

    public record InList(Expr expr, List<Expr> items, boolean not) implements Expr {
    }

    public record Like(Expr expr, Expr pattern, Expr escape, boolean not) implements Expr {
    }

    public record IsNull(Expr expr, boolean not) implements Expr {
    }

    public record IsEmpty(Expr expr, boolean not) implements Expr {
    }

    /** {@code value MEMBER OF collectionPath}. */
    public record Member(Expr value, Expr collection, boolean not) implements Expr {
    }

    /** {@code TYPE(alias) IN (...)} / {@code TYPE(alias) = X} (exact classes). */
    public record TypeIn(String alias, List<String> classes, boolean not, int line, int column) implements Expr {
    }

    public record Case(List<Expr> whens, List<Expr> thens, Expr otherwise) implements Expr {
    }

    public record Func(String name, List<Expr> args, int line, int column) implements Expr {
    }

    /** Aggregate; {@code arg == null} for {@code COUNT(*)}. */
    public record Agg(String fn, Expr arg, boolean distinct) implements Expr {
    }

    // ---------------------------------------------------------------- statements

    public sealed interface Statement {
        boolean explain();
    }

    public record From(String entity, String alias, int line, int column) {
    }

    public record Join(Path path, String alias, boolean left) {
    }

    public record SelectItem(Expr expr, String alias, String text) {
    }

    public record OrderItem(Expr expr, boolean desc, Boolean nullsFirst) {
    }

    /** Select list shapes. */
    public enum Shape { STAR, NEW, ITEMS }

    public record Select(boolean explain, boolean distinct, Shape shape, String newClass, List<SelectItem> items,
                         From from, List<Join> joins, Expr where, List<Expr> groupBy, Expr having,
                         List<OrderItem> orderBy, Expr limit, Expr offset) implements Statement {
    }

    public record SetItem(Path path, Expr value) {
    }

    public record Update(boolean explain, From from, List<SetItem> sets, Expr where) implements Statement {
    }

    public record Delete(boolean explain, From from, Expr where) implements Statement {
    }
}
