package io.github.wesleym.mappa.internal.model;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** A laid-out diagram: the tables, their foreign-key edges, and each edge's bend points. */
public final class Scene {

	private final List<EntityBox> tables;
	private final List<Link> edges;
	private final List<List<Point2D.Double>> edgePaths;   // bend points per edge, parallel to edges
	private final Map<EntityBox, Integer> tableIndex;  // O(1) indexOf — the flow animation calls it every frame

	public Scene(List<EntityBox> tables, List<Link> edges, List<List<Point2D.Double>> edgePaths) {
		this.tables = tables;
		this.edges = edges;
		this.edgePaths = edgePaths;
		this.tableIndex = new IdentityHashMap<>(tables.size() * 2);
		for (int i = 0; i < tables.size(); i++) {
			tableIndex.putIfAbsent(tables.get(i), i);
		}
	}

	public static Scene empty() {
		return new Scene(List.of(), List.of(), List.of());
	}

	public List<EntityBox> tables() {
		return tables;
	}

	public List<Link> edges() {
		return edges;
	}

	public List<List<Point2D.Double>> edgePaths() {
		return edgePaths;
	}

	public boolean isEmpty() {
		return tables.isEmpty();
	}

	public int indexOf(EntityBox table) {
		Integer i = tableIndex.get(table);
		return i == null ? -1 : i;
	}

	/** The topmost table under a world point, or null. Iterates in reverse paint order (top first). */
	public EntityBox tableAt(Point2D world) {
		for (int i = tables.size() - 1; i >= 0; i--) {
			if (tables.get(i).contains(world)) {
				return tables.get(i);
			}
		}
		return null;
	}

	/** The bounding rectangle of every table, or null when empty. */
	public Rectangle2D worldBounds() {
		Rectangle2D bounds = null;
		for (EntityBox table : tables) {
			bounds = bounds == null ? (Rectangle2D) table.bounds().clone() : bounds.createUnion(table.bounds());
		}
		return bounds;
	}
}
