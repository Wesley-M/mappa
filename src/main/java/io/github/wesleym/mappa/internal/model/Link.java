package io.github.wesleym.mappa.internal.model;

/**
 * A foreign-key edge between two table indices, carrying the joining columns ({@code from.fk → to.pk}).
 * {@code inferred} marks an edge found by statistical inference rather than a declared constraint — the
 * renderer draws those dashed.
 */
public record Link(int from, int to, String fromColumn, String toColumn, boolean inferred) {

	public Link(int from, int to, String fromColumn, String toColumn) {
		this(from, to, fromColumn, toColumn, false);
	}

	public Link(int from, int to) {
		this(from, to, null, null, false);
	}

	/**
	 * The join columns as "fk → pk", or null when the columns aren't known. When both sides share the same
	 * name (the common case, e.g. {@code customer_id} → {@code customer_id}) just the one name is shown.
	 */
	public String joinLabel() {
		if (fromColumn == null || toColumn == null) {
			return null;
		}
		return fromColumn.equalsIgnoreCase(toColumn) ? fromColumn : fromColumn + " -> " + toColumn;
	}
}
