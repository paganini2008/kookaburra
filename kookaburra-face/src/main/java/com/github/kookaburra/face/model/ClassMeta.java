package com.github.kookaburra.face.model;

import java.util.List;

/**
 * Metadata of a stored class for the sidebar panel.
 *
 * @param name         fully qualified class name
 * @param id           class id (high bits of OIDs)
 * @param parent       parent entity class or null
 * @param collection   collection root class
 * @param subclasses   direct and indirect subclasses (simple names)
 * @param idField      {@code @Id} field
 * @param versionField {@code @Version} field or null
 * @param layouts      number of stored layouts (schema versions of the class)
 * @param count        objects of this class and its subclasses
 * @param fields       stored fields, embedded paths flattened
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public record ClassMeta(String name, int id, String parent, String collection, List<String> subclasses, String idField,
                        String versionField, int layouts, long count, List<FieldMeta> fields) {

    /**
     * One stored field.
     *
     * @param path       field path ({@code address.city} for embedded fields)
     * @param type       stored type, e.g. {@code STRING}, {@code ENUM(LOW, HIGH)}, {@code LIST(STRING)}
     * @param index      index kind, or empty when the field has no column
     * @param declaredIn simple name of the class that declares the field
     */
    public record FieldMeta(String path, String type, String index, String declaredIn) {
    }
}
