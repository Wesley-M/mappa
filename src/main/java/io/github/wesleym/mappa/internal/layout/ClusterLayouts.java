package io.github.wesleym.mappa.internal.layout;

import io.github.wesleym.mappa.internal.layout.LayoutEngine.Box;
import io.github.wesleym.mappa.internal.layout.LayoutEngine.Edge;

import java.awt.geom.Point2D;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * Alternative node placements for one cluster of tables, selectable at runtime ({@link LayoutStyle}).
 * Each takes the cluster's boxes (and, where topology matters, its edges) and returns box centres in the same
 * shape as {@link LayeredLayout} — so {@link LayoutEngine} normalises and packs them identically,
 * whichever style is chosen. Edges are left to the router (no bend chains), so these return empty edge paths.
 *
 * <p>Fully deterministic — no randomness anywhere — so the same cluster always lays out the same way and the
 * result is unit-testable, matching the layered layout's contract.
 */
final class ClusterLayouts {

	private static final double GAP = 56;   // breathing room between boxes, on top of their own size

	private ClusterLayouts() { }

	/** A plain row-major grid of uniform cells sized to the largest box. */
	static LayeredLayout.Result grid(List<Box> boxes, List<Edge> edges, double gap) {
		int n = boxes.size();
		if (n == 0) {
			return new LayeredLayout.Result(List.of(), emptyPaths(edges.size()));
		}
		double cell = maxExtent(boxes);
		double step = cell + gap;
		int cols = Math.max(1, (int) Math.ceil(Math.sqrt(n)));
		List<Point2D.Double> centres = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			int row = i / cols;
			int col = i % cols;
			centres.add(new Point2D.Double(col * step, row * step));
		}
		return new LayeredLayout.Result(centres, emptyPaths(edges.size()));
	}

	/**
	 * A hub-and-spoke star: the highest-degree table sits at the centre and every other table is placed on a
	 * concentric ring at its BFS distance from the hub. Within a ring, tables are ordered by their parent's
	 * angle so siblings stay together and spokes don't tangle.
	 */
	static LayeredLayout.Result radial(List<Box> boxes, List<Edge> edges, double gap) {
		int n = boxes.size();
		if (n <= 1) {
			return new LayeredLayout.Result(
					n == 0 ? List.of() : List.of(new Point2D.Double(0, 0)), emptyPaths(edges.size()));
		}
		List<List<Integer>> adjacency = adjacency(n, edges);
		int hub = highestDegree(adjacency);

		// BFS from the hub: ring[v] = hop distance, parent[v] = the node we reached it through.
		int[] ring = new int[n];
		int[] parent = new int[n];
		Arrays.fill(ring, -1);
		Arrays.fill(parent, -1);
		ring[hub] = 0;
		Deque<Integer> queue = new ArrayDeque<>();
		queue.add(hub);
		List<List<Integer>> rings = new ArrayList<>();
		rings.add(new ArrayList<>(List.of(hub)));
		while (!queue.isEmpty()) {
			int v = queue.poll();
			for (int w : adjacency.get(v)) {
				if (ring[w] == -1) {
					ring[w] = ring[v] + 1;
					parent[w] = v;
					while (rings.size() <= ring[w]) {
						rings.add(new ArrayList<>());
					}
					rings.get(ring[w]).add(w);
					queue.add(w);
				}
			}
		}
		// Any node the BFS didn't reach (a disconnected cluster — not expected from Leiden communities, but
		// guard anyway) lands together on one extra outer ring so it's still placed.
		List<Integer> stranded = new ArrayList<>();
		for (int v = 0; v < n; v++) {
			if (ring[v] == -1) {
				stranded.add(v);
			}
		}
		if (!stranded.isEmpty()) {
			rings.add(stranded);
		}

		double cell = maxExtent(boxes);
		double ringStep = cell + gap;
		Point2D.Double[] centres = new Point2D.Double[n];
		double[] angleOf = new double[n];
		centres[hub] = new Point2D.Double(0, 0);
		for (int r = 1; r < rings.size(); r++) {
			List<Integer> members = rings.get(r);
			if (members.isEmpty()) {
				continue;
			}
			members.sort((a, b) -> {
				int cmp = Double.compare(parentAngle(parent, a, angleOf), parentAngle(parent, b, angleOf));
				return cmp != 0 ? cmp : Integer.compare(a, b);
			});
			// The ring must be wide enough that its members don't crowd: at least r steps out, and enough
			// circumference to seat them all.
			double radius = Math.max(r * ringStep, members.size() * ringStep / (2 * Math.PI));
			for (int k = 0; k < members.size(); k++) {
				int v = members.get(k);
				double angle = 2 * Math.PI * k / members.size();
				angleOf[v] = angle;
				centres[v] = new Point2D.Double(radius * Math.cos(angle), radius * Math.sin(angle));
			}
		}
		return new LayeredLayout.Result(Arrays.asList(centres), emptyPaths(edges.size()));
	}

	private static final double FORCE_GRAVITY = 1.6;   // centroid pull; compacts the blob so it stays navigable

	/**
	 * A deterministic force-directed (Fruchterman–Reingold) relaxation: every pair of boxes repels, every edge
	 * pulls its endpoints together, a gentle gravity keeps the whole compact, and the system cools over a fixed
	 * number of iterations. Seeded from a golden-angle spiral (not randomness) so the result is reproducible.
	 */
	static LayeredLayout.Result force(List<Box> boxes, List<Edge> edges, double gap) {
		int n = boxes.size();
		if (n <= 1) {
			return new LayeredLayout.Result(
					n == 0 ? List.of() : List.of(new Point2D.Double(0, 0)), emptyPaths(edges.size()));
		}
		double cell = maxExtent(boxes);
		double k = cell + gap;   // ideal edge length / separation
		double[] px = new double[n];
		double[] py = new double[n];
		// Deterministic phyllotaxis seed: evenly distributed, no two coincident (which would make repulsion blow up).
		double golden = Math.PI * (3 - Math.sqrt(5));
		for (int i = 0; i < n; i++) {
			double radius = k * Math.sqrt(i + 0.5);
			px[i] = radius * Math.cos(i * golden);
			py[i] = radius * Math.sin(i * golden);
		}
		int iterations = n <= 60 ? 140 : n <= 150 ? 80 : 40;
		double temperature = k * 2;
		double cooling = temperature / (iterations + 1);
		double[] dx = new double[n];
		double[] dy = new double[n];
		for (int iter = 0; iter < iterations; iter++) {
			Arrays.fill(dx, 0);
			Arrays.fill(dy, 0);
			// Repulsion between every pair: f = k²/d.
			for (int i = 0; i < n; i++) {
				for (int j = i + 1; j < n; j++) {
					double ox = px[i] - px[j];
					double oy = py[i] - py[j];
					double dist = Math.max(1e-4, Math.hypot(ox, oy));
					double force = k * k / dist;
					double ux = ox / dist * force;
					double uy = oy / dist * force;
					dx[i] += ux;
					dy[i] += uy;
					dx[j] -= ux;
					dy[j] -= uy;
				}
			}
			// Attraction along edges: f = d²/k.
			for (Edge e : edges) {
				if (e.from() == e.to()) {
					continue;
				}
				double ox = px[e.from()] - px[e.to()];
				double oy = py[e.from()] - py[e.to()];
				double dist = Math.max(1e-4, Math.hypot(ox, oy));
				double force = dist * dist / k;
				double ux = ox / dist * force;
				double uy = oy / dist * force;
				dx[e.from()] -= ux;
				dy[e.from()] -= uy;
				dx[e.to()] += ux;
				dy[e.to()] += uy;
			}
			// Gravity: a gentle pull toward the centroid keeps the whole thing compact — repulsion still holds
			// the ~k minimum spacing, so it packs into a tight organic blob instead of drifting into a sparse,
			// hard-to-navigate scatter (and keeps disconnected pieces from wandering off).
			double cx = 0;
			double cy = 0;
			for (int i = 0; i < n; i++) {
				cx += px[i];
				cy += py[i];
			}
			cx /= n;
			cy /= n;
			for (int i = 0; i < n; i++) {
				dx[i] += (cx - px[i]) * FORCE_GRAVITY;
				dy[i] += (cy - py[i]) * FORCE_GRAVITY;
			}
			// Step each node along its net force, capped by the current temperature, then cool.
			for (int i = 0; i < n; i++) {
				double disp = Math.max(1e-4, Math.hypot(dx[i], dy[i]));
				double step = Math.min(disp, temperature);
				px[i] += dx[i] / disp * step;
				py[i] += dy[i] / disp * step;
			}
			temperature = Math.max(0, temperature - cooling);
		}
		List<Point2D.Double> centres = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			centres.add(new Point2D.Double(px[i], py[i]));
		}
		return new LayeredLayout.Result(centres, emptyPaths(edges.size()));
	}

	// One empty bend-chain per edge — non-layered placements leave routing to the edge router.
	private static List<List<Point2D.Double>> emptyPaths(int count) {
		List<List<Point2D.Double>> paths = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			paths.add(List.of());
		}
		return paths;
	}

	// The largest single-axis box extent across the cluster — the uniform cell/step the placements space by.
	private static double maxExtent(List<Box> boxes) {
		double max = 1;
		for (Box b : boxes) {
			max = Math.max(max, Math.max(b.width(), b.height()));
		}
		return max;
	}

	private static List<List<Integer>> adjacency(int n, List<Edge> edges) {
		List<List<Integer>> adjacency = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			adjacency.add(new ArrayList<>());
		}
		for (Edge e : edges) {
			if (e.from() != e.to() && e.from() >= 0 && e.to() >= 0 && e.from() < n && e.to() < n) {
				adjacency.get(e.from()).add(e.to());
				adjacency.get(e.to()).add(e.from());
			}
		}
		return adjacency;
	}

	private static int highestDegree(List<List<Integer>> adjacency) {
		int hub = 0;
		for (int v = 1; v < adjacency.size(); v++) {
			if (adjacency.get(v).size() > adjacency.get(hub).size()) {
				hub = v;   // strict > keeps the lowest index on a tie, for determinism
			}
		}
		return hub;
	}

	private static double parentAngle(int[] parent, int v, double[] angleOf) {
		return parent[v] >= 0 ? angleOf[parent[v]] : 0;
	}

	/** The default gap the diagram uses when a caller doesn't specify one. */
	static double defaultGap() {
		return GAP;
	}
}
