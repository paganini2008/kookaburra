package com.github.kookaburra.mapping;

/**
 * An entity class cannot be mapped, or is incompatible with the stored schema.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class SchemaException extends RuntimeException {
    public SchemaException(String message) {
        super(message);
    }

    public SchemaException(String message, Throwable cause) {
        super(message, cause);
    }
}
