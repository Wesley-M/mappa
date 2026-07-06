package io.github.wesleym.mappa.internal.layout;

import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.List;

/**
 * How a relationship edge is drawn between its tables — a runtime-selectable wire style. The router
 * ({@link EdgeRouter}) decides where an edge attaches and the bend points it passes through; the style
 * only decides the shape of the line through those points, so switching it is a cheap re-stroke (no relayout).
 */
public enum EdgeStyle {

	/** Smooth Catmull-Rom curves with perpendicular exits — the default, soft organic look. */
	CURVED("Curved"),
	/** Right-angled, axis-aligned segments — a circuit-board / classic-ERD look. */
	ORTHOGONAL("Orthogonal"),
	/** Plain straight segments through the waypoints — the most literal, least decorated. */
	STRAIGHT("Straight"),
	/**
	 * Curved edges colour-graded by reference direction: each edge fades from the "outbound" hue at the child
	 * (foreign key) end to the "inbound" hue at the parent (referenced) end. Paired with table saturation scaled
	 * by degree centrality, so from afar the hub tables glow and you can read which tables are referenced versus
	 * which do the referencing. The path is the same spline centreline; the renderer applies the gradient.
	 */
	DIRECTIONAL("Directional");

	private final String label;

	EdgeStyle(String label) {
		this.label = label;
	}

	/** A short human label for the picker. */
	public String label() {
		return label;
	}

	/** Builds the drawable path for one edge's routed waypoints in this style. */
	public Path2D path(List<Point2D> waypoints, boolean startHorizontal, boolean endHorizontal) {
		return switch (this) {
			case CURVED, DIRECTIONAL -> EdgeRouter.spline(waypoints, startHorizontal, endHorizontal);
			case ORTHOGONAL -> EdgeRouter.orthogonal(waypoints, startHorizontal, endHorizontal);
			case STRAIGHT -> EdgeRouter.straight(waypoints);
		};
	}
}
