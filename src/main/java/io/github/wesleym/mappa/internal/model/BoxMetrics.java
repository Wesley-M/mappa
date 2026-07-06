package io.github.wesleym.mappa.internal.model;

/** Shared pixel metrics for the ER diagram's tables, rows, and edge decorations. */
public final class BoxMetrics {

	public static final int HEADER_HEIGHT = 30;
	public static final int ROW_HEIGHT = 22;
	public static final int TEXT_PADDING = 14;
	public static final int CORNER = 10;
	public static final int BADGE_GUTTER = 16;       // left gutter for the painted PK/FK marker
	public static final int KIND_GLYPH_GUTTER = 24;  // right gutter the header reserves for the table/view glyph
	public static final int BOTTOM_PADDING = 8;
	static final int MAX_BOX_HEIGHT = 320;    // taller tables cap here and scroll their columns
	public static final int CROWS_FOOT_LENGTH = 13;  // setback of the crow's-foot/one mark from the box

	private BoxMetrics() { }
}
