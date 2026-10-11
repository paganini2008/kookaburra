package com.github.kookaburra.oql;

import com.github.kookaburra.KookaburraException;

/**
 * Invalid OQL (syntax or semantics) or a runtime evaluation error, with the position in the query text
 * when known (line 1-based, column 0-based; -1 if unknown).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class OqlException extends KookaburraException {

    private final int line;
    private final int column;

    public OqlException(String message, int line, int column) {
        super(line > 0 ? message + " (line " + line + ":" + column + ")" : message);
        this.line = line;
        this.column = column;
    }

    public OqlException(String message) {
        this(message, -1, -1);
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }
}
