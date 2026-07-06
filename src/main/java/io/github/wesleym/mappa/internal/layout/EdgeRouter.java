package io.github.wesleym.mappa.internal.layout;

import io.github.wesleym.mappa.internal.model.Link;
import io.github.wesleym.mappa.internal.model.Scene;
import io.github.wesleym.mappa.internal.model.BoxMetrics;

import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves how each edge attaches to its tables and the smooth path it follows. An edge attaches to the
 * side of each box that faces the other table — top/bottom for the vertical layered spine, left/right for
 * horizontally-separated tables (which keeps same-row links short and frees up vertical space). When several
 * edges share a side they're spread along it so they don't stack. Pure geometry over a {@link Scene}
 * — no painting — so it's unit-testable.
 */
public final class EdgeRouter {

	private EdgeRouter() { }

	/** The side of a table an edge attaches to; {@code ox,oy} is the outward unit normal. */
	public enum Side {
		TOP(0, -1), BOTTOM(0, 1), LEFT(-1, 0), RIGHT(1, 0);

		public final int ox;
		public final int oy;

		Side(int ox, int oy) {
			this.ox = ox;
			this.oy = oy;
		}

		public boolean horizontal() {
			return ox != 0;
		}
	}

	/** An edge's resolved attach ports (on box borders), the side each is on, and the points its curve threads. */
	public record EdgeGeometry(Point2D start, Side startSide, Point2D end, Side endSide, List<Point2D> waypoints) {
		public boolean startHorizontal() {
			return startSide.horizontal();
		}

		public boolean endHorizontal() {
			return endSide.horizontal();
		}
	}

	// A side must be clearly the dominant axis before an edge attaches there, so near-diagonal edges stay on
	// the vertical layered flow rather than flipping to the sides.
	private static final double HORIZONTAL_BIAS = 1.3;
	private static final double SELF_LOOP_DROP = 46;   // how far a self-loop bows out from its table

	public static List<EdgeGeometry> route(Scene scene) {
		List<Link> edges = scene.edges();
		List<List<Point2D.Double>> edgePaths = scene.edgePaths();
		int n = edges.size();
		Point2D[] starts = new Point2D[n];
		Point2D[] ends = new Point2D[n];
		Side[] startSide = new Side[n];
		Side[] endSide = new Side[n];
		double[] startOrder = new double[n];   // coordinate that orders this edge along its start side
		double[] endOrder = new double[n];

		Map<Integer, List<int[]>> bySide = new HashMap<>();   // boxIndex*4 + side.ordinal() → {edge, isStart}
		for (int i = 0; i < n; i++) {
			Link edge = edges.get(i);
			Rectangle2D fromBox = scene.tables().get(edge.from()).bounds();
			Rectangle2D toBox = scene.tables().get(edge.to()).bounds();
			if (edge.from() == edge.to()) {
				// Self-referential FK: a loop off the right side, both feet spread vertically.
				startSide[i] = Side.RIGHT;
				endSide[i] = Side.RIGHT;
				startOrder[i] = fromBox.getCenterY() - fromBox.getHeight() * 0.2;
				endOrder[i] = fromBox.getCenterY() + fromBox.getHeight() * 0.2;
				bySide.computeIfAbsent(sideKey(edge.from(), Side.RIGHT), k -> new ArrayList<>()).add(new int[] { i, 1 });
				bySide.computeIfAbsent(sideKey(edge.to(), Side.RIGHT), k -> new ArrayList<>()).add(new int[] { i, 0 });
				continue;
			}
			List<Point2D.Double> bends = i < edgePaths.size() ? edgePaths.get(i) : List.of();
			Point2D firstTarget = bends.isEmpty() ? centreOf(toBox) : bends.get(0);
			Point2D lastTarget = bends.isEmpty() ? centreOf(fromBox) : bends.get(bends.size() - 1);
			Side fromSide;
			Side toSide;
			if (!bends.isEmpty()) {
				// A multi-layer edge is routed vertically through its dummy bends — keep top/bottom departure.
				fromSide = firstTarget.getY() >= fromBox.getCenterY() ? Side.BOTTOM : Side.TOP;
				toSide = lastTarget.getY() > toBox.getCenterY() ? Side.BOTTOM : Side.TOP;
			}
			else {
				// A direct edge attaches on whichever axis clearly dominates the gap, so horizontally-separated
				// tables connect side-to-side instead of looping over the top or under the bottom.
				double dx = toBox.getCenterX() - fromBox.getCenterX();
				double dy = toBox.getCenterY() - fromBox.getCenterY();
				if (Math.abs(dx) > Math.abs(dy) * HORIZONTAL_BIAS) {
					fromSide = dx >= 0 ? Side.RIGHT : Side.LEFT;
					toSide = dx >= 0 ? Side.LEFT : Side.RIGHT;
				}
				else {
					fromSide = dy >= 0 ? Side.BOTTOM : Side.TOP;
					toSide = dy >= 0 ? Side.TOP : Side.BOTTOM;
				}
			}
			startSide[i] = fromSide;
			endSide[i] = toSide;
			startOrder[i] = fromSide.horizontal() ? firstTarget.getY() : firstTarget.getX();
			endOrder[i] = toSide.horizontal() ? lastTarget.getY() : lastTarget.getX();
			bySide.computeIfAbsent(sideKey(edge.from(), fromSide), k -> new ArrayList<>()).add(new int[] { i, 1 });
			bySide.computeIfAbsent(sideKey(edge.to(), toSide), k -> new ArrayList<>()).add(new int[] { i, 0 });
		}

		for (Map.Entry<Integer, List<int[]>> group : bySide.entrySet()) {
			Rectangle2D box = scene.tables().get(group.getKey() / 4).bounds();
			Side side = Side.values()[group.getKey() % 4];
			List<int[]> ports = group.getValue();
			ports.sort((a, b) -> Double.compare(orderKey(a, startOrder, endOrder), orderKey(b, startOrder, endOrder)));
			for (int rank = 0; rank < ports.size(); rank++) {
				int[] port = ports.get(rank);
				Point2D at = portPoint(box, side, (rank + 1.0) / (ports.size() + 1));
				if (port[1] == 1) {
					starts[port[0]] = at;
				}
				else {
					ends[port[0]] = at;
				}
			}
		}

		List<EdgeGeometry> geometry = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			List<Point2D> waypoints = new ArrayList<>();
			// The curve stops short of each box at a setback point; the crow's-foot (many) and the "one"
			// stub+bar bridge that point to the box border, so the line and the symbol always meet cleanly.
			waypoints.add(footBase(starts[i], startSide[i]));
			if (edges.get(i).from() == edges.get(i).to()) {
				waypoints.add(selfLoopApex(starts[i], ends[i], startSide[i]));   // the loop's far point
			}
			else if (i < edgePaths.size()) {
				waypoints.addAll(edgePaths.get(i));
			}
			waypoints.add(footBase(ends[i], endSide[i]));
			geometry.add(new EdgeGeometry(starts[i], startSide[i], ends[i], endSide[i], waypoints));
		}
		return geometry;
	}

	/**
	 * A smooth spline through the waypoints. The end tangents are forced perpendicular to the attach side
	 * (vertical for top/bottom, horizontal for left/right) so every edge curves cleanly out of and into its
	 * tables, aligning with the crow's-foot. Interior tangents follow the average of the two adjacent unit
	 * directions, scaled to the <em>shorter</em> neighbouring chord — so the curve bends through each waypoint
	 * without overshooting it (plain Catmull-Rom overshoots when the dummy vertices from layered routing are
	 * unevenly spaced, which is what produced wiggles and self-crossings).
	 */
	public static Path2D spline(List<Point2D> points) {
		return spline(points, false, false);
	}

	public static Path2D spline(List<Point2D> points, boolean startHorizontal, boolean endHorizontal) {
		int n = points.size();
		Path2D path = new Path2D.Double();
		if (n == 0) {
			return path;
		}
		path.moveTo(points.get(0).getX(), points.get(0).getY());
		Point2D[] tangent = new Point2D[n];
		for (int i = 0; i < n; i++) {
			tangent[i] = tangentAt(points, i, n, startHorizontal, endHorizontal);
		}
		for (int i = 0; i < n - 1; i++) {
			Point2D p0 = points.get(i);
			Point2D p1 = points.get(i + 1);
			path.curveTo(
					p0.getX() + tangent[i].getX() / 3, p0.getY() + tangent[i].getY() / 3,
					p1.getX() - tangent[i + 1].getX() / 3, p1.getY() - tangent[i + 1].getY() / 3,
					p1.getX(), p1.getY());
		}
		return path;
	}

	/** Plain straight segments through the routed waypoints — the most literal wire, no decoration. */
	public static Path2D straight(List<Point2D> points) {
		Path2D path = new Path2D.Double();
		if (points.isEmpty()) {
			return path;
		}
		path.moveTo(points.get(0).getX(), points.get(0).getY());
		for (int i = 1; i < points.size(); i++) {
			path.lineTo(points.get(i).getX(), points.get(i).getY());
		}
		return path;
	}

	/**
	 * Axis-aligned (Manhattan) segments through the routed waypoints — a circuit-board / classic-ERD look.
	 * Each leg turns with right angles; the first leg leaves along the start attach axis and the last leg
	 * arrives along the end attach axis (so the stub still lines up with the crow's-foot), and a leg whose two
	 * ends share an axis takes a single mid-point dog-leg rather than cutting the corner.
	 */
	public static Path2D orthogonal(List<Point2D> points, boolean startHorizontal, boolean endHorizontal) {
		Path2D path = new Path2D.Double();
		int n = points.size();
		if (n == 0) {
			return path;
		}
		path.moveTo(points.get(0).getX(), points.get(0).getY());
		for (int i = 0; i < n - 1; i++) {
			Point2D a = points.get(i);
			Point2D b = points.get(i + 1);
			boolean leaveHorizontal = i == 0 ? startHorizontal : dominantHorizontal(a, b);
			boolean arriveHorizontal = i == n - 2 ? endHorizontal : dominantHorizontal(a, b);
			orthoLeg(path, a, b, leaveHorizontal, arriveHorizontal);
		}
		return path;
	}

	private static boolean dominantHorizontal(Point2D a, Point2D b) {
		return Math.abs(b.getX() - a.getX()) >= Math.abs(b.getY() - a.getY());
	}

	// Appends the right-angled leg from a to b. When both ends share an axis (leave==arrive) the leg takes a
	// three-segment dog-leg through the mid-line so it stays on that axis at both ends; otherwise a single L bend.
	private static void orthoLeg(Path2D path, Point2D a, Point2D b, boolean leaveHorizontal, boolean arriveHorizontal) {
		double ax = a.getX();
		double ay = a.getY();
		double bx = b.getX();
		double by = b.getY();
		if (leaveHorizontal == arriveHorizontal) {
			if (leaveHorizontal) {
				double midX = (ax + bx) / 2;
				path.lineTo(midX, ay);
				path.lineTo(midX, by);
				path.lineTo(bx, by);
			}
			else {
				double midY = (ay + by) / 2;
				path.lineTo(ax, midY);
				path.lineTo(bx, midY);
				path.lineTo(bx, by);
			}
		}
		else if (leaveHorizontal) {
			path.lineTo(bx, ay);   // horizontal first, then vertical into b
			path.lineTo(bx, by);
		}
		else {
			path.lineTo(ax, by);   // vertical first, then horizontal into b
			path.lineTo(bx, by);
		}
	}

	private static Point2D tangentAt(List<Point2D> points, int i, int n,
			boolean startHorizontal, boolean endHorizontal) {
		if (i == 0) {
			Point2D next = points.get(Math.min(1, n - 1));
			return startHorizontal ? new Point2D.Double(next.getX() - points.get(0).getX(), 0)
					: new Point2D.Double(0, next.getY() - points.get(0).getY());
		}
		if (i == n - 1) {
			Point2D prev = points.get(n - 2);
			return endHorizontal ? new Point2D.Double(points.get(n - 1).getX() - prev.getX(), 0)
					: new Point2D.Double(0, points.get(n - 1).getY() - prev.getY());
		}
		Point2D prev = points.get(i - 1);
		Point2D cur = points.get(i);
		Point2D next = points.get(i + 1);
		double lp = cur.distance(prev);
		double ln = next.distance(cur);
		if (lp < 1e-6 || ln < 1e-6) {
			return new Point2D.Double((next.getX() - prev.getX()) * 0.5, (next.getY() - prev.getY()) * 0.5);
		}
		double ux = (cur.getX() - prev.getX()) / lp + (next.getX() - cur.getX()) / ln;
		double uy = (cur.getY() - prev.getY()) / lp + (next.getY() - cur.getY()) / ln;
		double ul = Math.hypot(ux, uy);
		if (ul < 1e-6) {
			return new Point2D.Double(0, 0);   // a hairpin reversal: no tangent, let the curve pinch
		}
		double mag = Math.min(lp, ln);   // clamp to the shorter chord so the curve can't overshoot a waypoint
		return new Point2D.Double(ux / ul * mag, uy / ul * mag);
	}

	private static Point2D portPoint(Rectangle2D box, Side side, double fraction) {
		return switch (side) {
			case TOP -> new Point2D.Double(box.getX() + box.getWidth() * fraction, box.getMinY());
			case BOTTOM -> new Point2D.Double(box.getX() + box.getWidth() * fraction, box.getMaxY());
			case LEFT -> new Point2D.Double(box.getMinX(), box.getY() + box.getHeight() * fraction);
			case RIGHT -> new Point2D.Double(box.getMaxX(), box.getY() + box.getHeight() * fraction);
		};
	}

	private static Point2D footBase(Point2D port, Side side) {
		return new Point2D.Double(port.getX() + side.ox * BoxMetrics.CROWS_FOOT_LENGTH,
				port.getY() + side.oy * BoxMetrics.CROWS_FOOT_LENGTH);
	}

	// The far point of a self-loop: midway between its two feet (along the border) and pushed out past the
	// crow's foot, deeper when the feet are farther apart, so the spline rounds it into a clean loop.
	private static Point2D selfLoopApex(Point2D start, Point2D end, Side side) {
		double span = side.horizontal() ? Math.abs(end.getY() - start.getY()) : Math.abs(end.getX() - start.getX());
		double out = BoxMetrics.CROWS_FOOT_LENGTH + Math.max(SELF_LOOP_DROP, span * 0.45);
		return new Point2D.Double((start.getX() + end.getX()) / 2 + side.ox * out,
				(start.getY() + end.getY()) / 2 + side.oy * out);
	}

	private static double orderKey(int[] port, double[] startOrder, double[] endOrder) {
		return port[1] == 1 ? startOrder[port[0]] : endOrder[port[0]];
	}

	private static int sideKey(int boxIndex, Side side) {
		return boxIndex * 4 + side.ordinal();
	}

	private static Point2D centreOf(Rectangle2D r) {
		return new Point2D.Double(r.getCenterX(), r.getCenterY());
	}
}
