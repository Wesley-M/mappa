package io.github.wesleym.mappa.internal.model;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.List;

/**
 * A table in the diagram: its name and columns, its on-canvas bounds (set by the layout, movable by
 * dragging), and how far its column list is scrolled when the box is height-capped.
 */
public final class EntityBox {

	private final String name;
	private final List<Field> columns;
	private final boolean view;
	private final Rectangle2D.Double bounds = new Rectangle2D.Double();
	private double scrollOffset;
	private double centrality;   // 0..1, this table's relationship degree over the busiest table's — drives the hub glow
	private int clusterId = -1;  // which laid-out community this table belongs to (for the tinted region behind it)

	public EntityBox(String name, List<Field> columns, boolean view) {
		this.name = name;
		this.columns = columns;
		this.view = view;
	}

	public String name() {
		return name;
	}

	public List<Field> columns() {
		return columns;
	}

	public boolean isView() {
		return view;
	}

	/** How central this table is to the schema (0 = no relationships, 1 = the most-connected table). */
	public double centrality() {
		return centrality;
	}

	void setCentrality(double centrality) {
		this.centrality = centrality;
	}

	/** The laid-out community this table belongs to, or -1 if not clustered. */
	public int clusterId() {
		return clusterId;
	}

	void setClusterId(int clusterId) {
		this.clusterId = clusterId;
	}

	public Rectangle2D bounds() {
		return bounds;
	}

	public double scrollOffset() {
		return scrollOffset;
	}

	public void placeCentre(Point2D.Double centre, double width, double height) {
		bounds.setRect(centre.x - width / 2, centre.y - height / 2, width, height);
	}

	public void moveCentreTo(double centreX, double centreY) {
		bounds.setRect(centreX - bounds.width / 2, centreY - bounds.height / 2, bounds.width, bounds.height);
	}

	boolean contains(Point2D world) {
		return bounds.contains(world);
	}

	double viewportHeight() {
		return bounds.height - BoxMetrics.HEADER_HEIGHT - BoxMetrics.BOTTOM_PADDING;
	}

	public double maxScroll() {
		return Math.max(0, columns.size() * BoxMetrics.ROW_HEIGHT - viewportHeight());
	}

	public boolean scrollable() {
		return maxScroll() > 0;
	}

	public void scrollBy(double delta) {
		scrollOffset = Math.max(0, Math.min(scrollOffset + delta, maxScroll()));
	}
}
