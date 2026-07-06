package io.github.wesleym.mappa.internal.render;

/** The backdrop pattern tiled behind the ER diagram — a runtime-selectable, quietly decorative texture. */
public enum Backdrop {

	/** A faint point cloud — the default. */
	DOTS("Dots"),
	/** Thin grid rules (a ruled graph-paper look). */
	GRID("Grid"),
	/** A muted honeycomb of hexagons. */
	HEXAGONS("Hexagons"),
	/** No pattern — just the solid canvas colour. */
	PLAIN("Plain");

	private final String label;

	Backdrop(String label) {
		this.label = label;
	}

	/** A short human label for the picker. */
	public String label() {
		return label;
	}
}
