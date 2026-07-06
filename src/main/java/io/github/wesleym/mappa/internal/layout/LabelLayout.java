package io.github.wesleym.mappa.internal.layout;

import io.github.wesleym.mappa.internal.model.Link;
import io.github.wesleym.mappa.internal.model.Scene;
import io.github.wesleym.mappa.internal.model.EntityBox;

import java.awt.FontMetrics;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Placement for the join-column labels, run only when the user turns labels on (off by default, so the
 * diagram's routing is untouched). This is the classic map-labeling problem; we use the standard practical
 * approach: a discrete set of <em>candidate positions</em> per label, scored by a penalty, chosen greedily.
 *
 * <p>Each label first tries to <em>slide along its own edge</em> to the clearest spot, so it sits right on
 * the line with no leader. Only when the whole edge is congested does it step off perpendicular — and then it
 * keeps an anchor on its edge so the renderer draws a short leader. The edges themselves are never moved.
 * Pure geometry — unit-testable, no painting.
 */
public final class LabelLayout {

	public static final int PAD_X = 7;
	public static final int PAD_Y = 4;

	private static final double LABEL_GAP = 9;        // breathing room enforced between labels
	private static final int MAX_FORCED = 250;        // above this many labels, skip the (costlier) edge-cross test
	// Candidate positions: first slide along the edge (centre-out), then step off perpendicular if crowded.
	private static final double[] SLIDE_FRACTIONS = { 0.5, 0.42, 0.58, 0.34, 0.66, 0.26, 0.74 };
	private static final double[] OFFSET_FRACTIONS = { 0.5, 0.4, 0.6 };
	private static final double[] OFFSET_DISTANCES = { 22, 36, 52, 70 };
	// Penalty weights, in descending tiers: a clear position always beats an overlapping one, etc.
	private static final double OVERLAP_PENALTY = 1e6;   // rect over a table or another label — never if avoidable
	private static final double CROSS_PENALTY = 1e3;     // rect straddling another edge's curve
	private static final int MAX_CROSS_COUNTED = 3;
	private static final double LEADER_WEIGHT = 1.0;     // prefer sitting on the edge over a long leader
	private static final double CENTRE_WEIGHT = 8.0;     // prefer the middle of the edge

	private LabelLayout() { }

	/** A placed label: its rectangle, and the point on its own edge a leader line should connect to. */
	public record Placed(Rectangle2D rect, Point2D anchor) { }

	private record Candidate(double cx, double cy, Point2D anchor, double leader, double frac) { }

	public static Map<Integer, Placed> layout(Scene scene, List<Path2D> basePaths, FontMetrics fm,
			Map<Integer, Point2D> overrides) {
		List<Link> edges = scene.edges();
		int m = basePaths.size();
		double[][][] polylines = new double[m][][];
		double[][] edgeBounds = new double[m][];
		for (int i = 0; i < m; i++) {
			polylines[i] = flatten(basePaths.get(i));
			edgeBounds[i] = boundsOf(polylines[i]);
		}

		List<Integer> labelled = new ArrayList<>();
		for (int i = 0; i < edges.size() && i < m; i++) {
			// A user-pinned label is placed by hand (below), so it's excluded from automatic placement.
			if (edges.get(i).joinLabel() != null && polylines[i] != null && !overrides.containsKey(i)) {
				labelled.add(i);
			}
		}

		// Obstacles every label must avoid: the table boxes, plus the pinned labels (fixed by the user).
		List<Rectangle2D> obstacles = new ArrayList<>();
		for (EntityBox table : scene.tables()) {
			obstacles.add(table.bounds());
		}
		Map<Integer, Placed> pinned = new LinkedHashMap<>();
		for (Map.Entry<Integer, Point2D> override : overrides.entrySet()) {
			int edge = override.getKey();
			if (edge < 0 || edge >= edges.size() || edges.get(edge).joinLabel() == null) {
				continue;
			}
			Rectangle2D rect = labelRect(fm, edges.get(edge).joinLabel(), override.getValue());
			obstacles.add(rect);
			pinned.put(edge, new Placed(rect, override.getValue()));   // anchor == centre → no leader; the edge bends to it
		}

		double labelH = fm.getAscent() + fm.getDescent() + PAD_Y * 2;
		// Place the most constrained first: a short edge has the least room to slide, so it gets first pick.
		labelled.sort(Comparator.comparingDouble(i -> length(polylines[i])));
		boolean edgeAvoid = labelled.size() <= MAX_FORCED;

		List<Rectangle2D> placedRects = new ArrayList<>();
		Map<Integer, Placed> placed = new LinkedHashMap<>();
		for (int edge : labelled) {
			double w = fm.stringWidth(edges.get(edge).joinLabel()) + PAD_X * 2;
			Candidate best = bestCandidate(polylines[edge], w, labelH, obstacles, placedRects,
					polylines, edgeBounds, edge, edgeAvoid);
			Rectangle2D rect = new Rectangle2D.Double(best.cx() - w / 2, best.cy() - labelH / 2, w, labelH);
			placed.put(edge, new Placed(rect, best.anchor()));
			placedRects.add(rect);
		}
		placed.putAll(pinned);
		return placed;
	}

	/** The rectangle a label of {@code text} would occupy centred on {@code centre}. */
	public static Rectangle2D labelRect(FontMetrics fm, String text, Point2D centre) {
		double w = fm.stringWidth(text) + PAD_X * 2;
		double h = fm.getAscent() + fm.getDescent() + PAD_Y * 2;
		return new Rectangle2D.Double(centre.getX() - w / 2, centre.getY() - h / 2, w, h);
	}

	// Tries the on-edge slide positions first, then perpendicular offsets; returns the lowest-penalty one.
	private static Candidate bestCandidate(double[][] poly, double w, double h, List<Rectangle2D> obstacles,
			List<Rectangle2D> placedRects, double[][][] polylines, double[][] edgeBounds, int selfEdge,
			boolean edgeAvoid) {
		double total = length(poly);
		Candidate best = null;
		double bestScore = Double.MAX_VALUE;
		for (double f : SLIDE_FRACTIONS) {
			Point2D p = pointAtFraction(poly, f, total);
			Candidate c = new Candidate(p.getX(), p.getY(), p, 0, f);
			double score = penalty(c, w, h, obstacles, placedRects, polylines, edgeBounds, selfEdge, edgeAvoid);
			if (score < bestScore) {
				bestScore = score;
				best = c;
			}
		}
		// A clear on-edge spot near the middle scores ~0; only pay for perpendicular candidates if we didn't get one.
		if (bestScore >= OVERLAP_PENALTY || bestScore >= CROSS_PENALTY) {
			for (double f : OFFSET_FRACTIONS) {
				Point2D base = pointAtFraction(poly, f, total);
				double[] normal = normalAtFraction(poly, f, total);
				for (double d : OFFSET_DISTANCES) {
					for (int sign = -1; sign <= 1; sign += 2) {
						double cx = base.getX() + normal[0] * d * sign;
						double cy = base.getY() + normal[1] * d * sign;
						Candidate c = new Candidate(cx, cy, base, d, f);
						double score = penalty(c, w, h, obstacles, placedRects, polylines, edgeBounds, selfEdge, edgeAvoid);
						if (score < bestScore) {
							bestScore = score;
							best = c;
						}
					}
				}
			}
		}
		return best;
	}

	private static double penalty(Candidate c, double w, double h, List<Rectangle2D> obstacles,
			List<Rectangle2D> placedRects, double[][][] polylines, double[][] edgeBounds, int selfEdge,
			boolean edgeAvoid) {
		Rectangle2D rect = new Rectangle2D.Double(c.cx() - w / 2, c.cy() - h / 2, w, h);
		double p = 0;
		for (Rectangle2D o : obstacles) {
			if (rect.intersects(o)) {
				p += OVERLAP_PENALTY;
			}
		}
		for (Rectangle2D r : placedRects) {
			if (intersectsWithGap(rect, r)) {
				p += OVERLAP_PENALTY;
			}
		}
		if (edgeAvoid) {
			int crosses = 0;
			for (int e = 0; e < polylines.length && crosses < MAX_CROSS_COUNTED; e++) {
				double[] b = edgeBounds[e];
				if (e == selfEdge || b == null
						|| rect.getMaxX() < b[0] || rect.getMinX() > b[2]
						|| rect.getMaxY() < b[1] || rect.getMinY() > b[3]) {
					continue;   // far edge — skip the segment loop entirely
				}
				double[][] pl = polylines[e];
				for (int s = 0; s + 1 < pl[0].length; s++) {
					if (rect.intersectsLine(pl[0][s], pl[1][s], pl[0][s + 1], pl[1][s + 1])) {
						crosses++;
						break;
					}
				}
			}
			p += crosses * CROSS_PENALTY;
		}
		p += c.leader() * LEADER_WEIGHT;
		p += Math.abs(c.frac() - 0.5) * CENTRE_WEIGHT;
		return p;
	}

	private static boolean intersectsWithGap(Rectangle2D a, Rectangle2D b) {
		return a.getMaxX() + LABEL_GAP > b.getMinX() && a.getMinX() - LABEL_GAP < b.getMaxX()
				&& a.getMaxY() + LABEL_GAP > b.getMinY() && a.getMinY() - LABEL_GAP < b.getMaxY();
	}

	// ---- polyline helpers --------------------------------------------------------------------------

	private static double[][] flatten(Path2D path) {
		List<double[]> points = new ArrayList<>();
		double[] coords = new double[6];
		for (PathIterator it = path.getPathIterator(null, 2.0); !it.isDone(); it.next()) {
			if (it.currentSegment(coords) != PathIterator.SEG_CLOSE) {
				points.add(new double[] { coords[0], coords[1] });
			}
		}
		if (points.size() < 2) {
			return null;
		}
		int n = points.size();
		double[] xs = new double[n];
		double[] ys = new double[n];
		for (int i = 0; i < n; i++) {
			xs[i] = points.get(i)[0];
			ys[i] = points.get(i)[1];
		}
		return new double[][] { xs, ys };
	}

	private static double length(double[][] poly) {
		double[] xs = poly[0];
		double[] ys = poly[1];
		double total = 0;
		for (int i = 1; i < xs.length; i++) {
			total += Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]);
		}
		return total;
	}

	// The point a fraction f (0..1) of the way along the polyline by arc length.
	private static Point2D pointAtFraction(double[][] poly, double f, double total) {
		double[] xs = poly[0];
		double[] ys = poly[1];
		if (total <= 0) {
			return new Point2D.Double(xs[0], ys[0]);
		}
		double target = total * f;
		double acc = 0;
		for (int i = 1; i < xs.length; i++) {
			double seg = Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]);
			if (acc + seg >= target) {
				double t = seg <= 0 ? 0 : (target - acc) / seg;
				return new Point2D.Double(xs[i - 1] + (xs[i] - xs[i - 1]) * t, ys[i - 1] + (ys[i] - ys[i - 1]) * t);
			}
			acc += seg;
		}
		return new Point2D.Double(xs[xs.length - 1], ys[ys.length - 1]);
	}

	// The unit normal to the polyline at fraction f — the direction a label steps off the edge.
	private static double[] normalAtFraction(double[][] poly, double f, double total) {
		double[] xs = poly[0];
		double[] ys = poly[1];
		double target = total * f;
		double acc = 0;
		int seg = 0;
		for (int i = 1; i < xs.length; i++) {
			double len = Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]);
			seg = i - 1;
			if (acc + len >= target) {
				break;
			}
			acc += len;
		}
		double dx = xs[seg + 1] - xs[seg];
		double dy = ys[seg + 1] - ys[seg];
		double len = Math.hypot(dx, dy);
		if (len < 1e-6) {
			return new double[] { 0, 1 };
		}
		return new double[] { -dy / len, dx / len };   // perpendicular to the segment direction
	}

	// Bounding box {minX, minY, maxX, maxY} of a flattened polyline, or null if degenerate.
	private static double[] boundsOf(double[][] poly) {
		if (poly == null) {
			return null;
		}
		double[] xs = poly[0];
		double[] ys = poly[1];
		double minX = xs[0];
		double maxX = xs[0];
		double minY = ys[0];
		double maxY = ys[0];
		for (int i = 1; i < xs.length; i++) {
			minX = Math.min(minX, xs[i]);
			maxX = Math.max(maxX, xs[i]);
			minY = Math.min(minY, ys[i]);
			maxY = Math.max(maxY, ys[i]);
		}
		return new double[] { minX, minY, maxX, maxY };
	}
}
