package io.github.wesleym.mappa.internal.layout;

/**
 * How each cluster (community) of tables is arranged on screen — a runtime-selectable node-placement
 * algorithm. The clustering, packing, and edge routing are unchanged; only the way a single cluster's boxes
 * are positioned varies. Switching it re-runs the (off-EDT) layout.
 */
public enum LayoutStyle {

	/** Sugiyama layered flow: referenced tables float up, referencing tables sit below. The default. */
	LAYERED("Layered"),
	/** A hub-and-spoke star: the most-connected table at the centre, the rest on concentric rings around it. */
	RADIAL("Radial / Star"),
	/** A force-directed relaxation: springs along edges, repulsion between boxes — an organic web. */
	FORCE("Force-directed"),
	/** A plain row-major grid — every table the same cell, no topology bias. */
	GRID("Grid");

	private final String label;

	LayoutStyle(String label) {
		this.label = label;
	}

	/** A short human label for the picker. */
	public String label() {
		return label;
	}
}
