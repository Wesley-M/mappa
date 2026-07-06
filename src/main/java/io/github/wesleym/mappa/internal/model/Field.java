package io.github.wesleym.mappa.internal.model;

/** A column drawn in a table box; the PK/FK flags drive the painted key markers. */
public record Field(String name, String type, boolean primaryKey, boolean foreignKey) { }
