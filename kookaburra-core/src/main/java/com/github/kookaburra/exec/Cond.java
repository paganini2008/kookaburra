package com.github.kookaburra.exec;

import com.github.kookaburra.mapping.Canonical;
import com.github.kookaburra.mapping.TypeDef;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Planner output: a predicate over canonical values with resolved paths and types. Leaves can be
 * answered by a {@link ColumnIndex} or by testing canonical values; {@link Residual} needs the object.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public sealed interface Cond {

    /** Leaf on a stored path; {@link #test} evaluates it on a canonical value. */
    sealed interface Leaf extends Cond {
        String path();

        boolean test(Object value);
    }

    record Eq(String path, TypeDef type, Object key) implements Leaf {
        @Override
        public boolean test(Object v) {
            return v != null && key.equals(v);
        }

        @Override
        public String toString() {
            return path + " = " + lit(key);
        }
    }

    record In(String path, TypeDef type, Set<Object> keys) implements Leaf {
        @Override
        public boolean test(Object v) {
            return v != null && keys.contains(v);
        }

        @Override
        public String toString() {
            return path + " IN " + keys.stream().map(Cond::lit).collect(Collectors.joining(", ", "(", ")"));
        }
    }

    record Range(String path, TypeDef type, Object lo, boolean loInclusive, Object hi, boolean hiInclusive)
            implements Leaf {
        @Override
        public boolean test(Object v) {
            if (v == null) {
                return false;
            }
            if (lo != null) {
                int c = Canonical.compare(type, v, lo);
                if (c < 0 || (c == 0 && !loInclusive)) {
                    return false;
                }
            }
            if (hi != null) {
                int c = Canonical.compare(type, v, hi);
                return c < 0 || (c == 0 && hiInclusive);
            }
            return true;
        }

        @Override
        public String toString() {
            return path + " IN " + (lo == null ? "(-∞" : (loInclusive ? "[" : "(") + lit(lo)) + ", "
                    + (hi == null ? "+∞)" : lit(hi) + (hiInclusive ? "]" : ")"));
        }
    }

    record IsNull(String path) implements Leaf {
        @Override
        public boolean test(Object v) {
            return v == null;
        }

        @Override
        public String toString() {
            return path + " IS NULL";
        }
    }

    record Prefix(String path, String prefix) implements Leaf {
        @Override
        public boolean test(Object v) {
            return v instanceof String s && s.startsWith(prefix);
        }

        @Override
        public String toString() {
            return path + " STARTS WITH " + lit(prefix);
        }
    }

    record Contains(String path, TypeDef type, Set<Object> keys, boolean all) implements Leaf {
        @Override
        public boolean test(Object v) {
            if (!(v instanceof List<?> list)) {
                return false;
            }
            return all ? list.containsAll(keys) : keys.stream().anyMatch(list::contains);
        }

        @Override
        public String toString() {
            return path + (all ? " CONTAINS ALL " : " CONTAINS ANY ")
                    + keys.stream().map(Cond::lit).collect(Collectors.joining(", ", "(", ")"));
        }
    }

    /** Objects with one of these OIDs. */
    record Oids(Set<Long> oids) implements Cond {
        @Override
        public String toString() {
            return "OID IN " + oids;
        }
    }

    /** Objects whose class id is in the set. */
    record Types(Set<Integer> classIds, String label) implements Cond {
        @Override
        public String toString() {
            return "TYPE IN " + label;
        }
    }

    record And(List<Cond> conds) implements Cond {
        @Override
        public String toString() {
            return conds.stream().map(c -> c instanceof Or ? "(" + c + ")" : c.toString())
                    .collect(Collectors.joining(" AND "));
        }
    }

    record Or(List<Cond> conds) implements Cond {
        @Override
        public String toString() {
            return conds.stream().map(c -> c instanceof And ? "(" + c + ")" : c.toString())
                    .collect(Collectors.joining(" OR "));
        }
    }

    record Not(Cond cond) implements Cond {
        @Override
        public String toString() {
            return "NOT (" + cond + ")";
        }
    }

    /** Opaque predicate on the materialized object. */
    record Residual(Predicate<Object> predicate, String text) implements Cond {
        @Override
        public String toString() {
            return "RESIDUAL " + text;
        }
    }

    record Const(boolean value) implements Cond {
        static final Const TRUE = new Const(true);
        static final Const FALSE = new Const(false);

        @Override
        public String toString() {
            return value ? "TRUE" : "FALSE";
        }
    }

    private static String lit(Object v) {
        return v instanceof String ? "'" + v + "'" : String.valueOf(v);
    }
}
