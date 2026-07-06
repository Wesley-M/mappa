package io.github.wesleym.mappa.internal.layout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Horizontal coordinate assignment for a layered drawing — Brandes &amp; Köpf, "Fast and Simple
 * Horizontal Coordinate Assignment" (Graph Drawing 2002). It aligns vertices into vertical
 * <em>blocks</em>, giving the inner segments of long edges (dummy → dummy) priority so multi-layer
 * relationships come out straight, then balances four candidate layouts — left/right bias crossed with
 * up/down alignment — by taking, per vertex, the median of the two middle coordinates.
 *
 * <p>The four runs are produced from a single "align-to-upper, leftmost, pack-left" kernel by mirroring
 * the layered ordering vertically (swap up/down) and/or horizontally (swap left/right). Type-1 conflicts
 * (a non-inner segment crossing an inner one) are marked once and resolved in favour of the inner
 * segment. Separation respects each box's width, so variable-width tables never overlap.
 *
 * <p>Fully deterministic. Operates on geometry only (layers, sizes, adjacency) so it's unit-testable.
 */
final class LayeredCoordinateAssignment {

	private LayeredCoordinateAssignment() { }

	/**
	 * Assigns an x-centre to every vertex (real boxes and routing dummies alike).
	 *
	 * @param layers    vertex ids per layer, each layer in left-to-right order
	 * @param layerOf   each vertex's layer index
	 * @param width     each vertex's width
	 * @param dummy     whether each vertex is a routing dummy (both ends dummy ⇒ inner segment)
	 * @param adjacency undirected adjacency, between consecutive layers only
	 * @param nodeGap   minimum empty gap between two boxes sharing a layer
	 */
	static double[] assign(List<List<Integer>> layers, int[] layerOf, double[] width, boolean[] dummy,
			List<List<Integer>> adjacency, double nodeGap) {
		int count = layerOf.length;
		boolean[][] marked = markType1Conflicts(orient(layers, count, false, false), adjacency, dummy);

		// Four candidate layouts: {down,up} alignment × {left,right} bias, via vertical/horizontal mirroring.
		double[][] candidates = new double[4][];
		boolean[] leftBiased = { true, false, true, false };
		int k = 0;
		for (boolean verticalFlip : new boolean[] { false, true }) {
			for (boolean horizontalFlip : new boolean[] { false, true }) {
				Oriented o = orient(layers, count, verticalFlip, horizontalFlip);
				int[][] block = align(o, adjacency, marked, horizontalFlip);
				double[] x = compact(o, adjacency, block[0], block[1], width, nodeGap);
				if (horizontalFlip) {
					negate(x);   // bring the mirrored layout back to a left-to-right frame
				}
				candidates[k++] = x;
			}
		}
		return balance(candidates, leftBiased, count);
	}

	// ---- orientation -------------------------------------------------------------------------------

	/** The layered ordering optionally mirrored, with each vertex's (layer, position) in that frame. */
	private record Oriented(List<List<Integer>> rows, int[] layer, int[] position) { }

	private static Oriented orient(List<List<Integer>> layers, int count, boolean verticalFlip,
			boolean horizontalFlip) {
		int h = layers.size();
		List<List<Integer>> rows = new ArrayList<>(h);
		for (int i = 0; i < h; i++) {
			List<Integer> row = new ArrayList<>(layers.get(verticalFlip ? h - 1 - i : i));
			if (horizontalFlip) {
				Collections.reverse(row);
			}
			rows.add(row);
		}
		int[] layer = new int[count];
		int[] position = new int[count];
		for (int i = 0; i < h; i++) {
			List<Integer> row = rows.get(i);
			for (int p = 0; p < row.size(); p++) {
				layer[row.get(p)] = i;
				position[row.get(p)] = p;
			}
		}
		return new Oriented(rows, layer, position);
	}

	// ---- type-1 conflict marking -------------------------------------------------------------------

	/**
	 * Marks each segment that crosses an inner segment (Brandes &amp; Köpf, Algorithm 1). Computed once in
	 * the natural orientation; a crossing is symmetric under mirroring, so the same marks serve all four
	 * runs. Indexed {@code marked[upperVertex][positionInLowerLayer]} via the lower endpoint's adjacency.
	 */
	private static boolean[][] markType1Conflicts(Oriented o, List<List<Integer>> adjacency, boolean[] dummy) {
		int count = o.layer().length;
		boolean[][] marked = new boolean[count][];   // marked[w] over w's upper neighbours, by neighbour rank
		for (int w = 0; w < count; w++) {
			marked[w] = new boolean[adjacency.get(w).size()];
		}
		int h = o.rows().size();
		for (int i = 1; i < h - 1; i++) {
			List<Integer> lower = o.rows().get(i + 1);
			int k0 = 0;
			int first = 0;
			for (int l1 = 0; l1 < lower.size(); l1++) {
				int v = lower.get(l1);
				int innerUpper = innerUpperNeighbour(v, o, adjacency, dummy);
				boolean atBoundary = l1 == lower.size() - 1 || innerUpper >= 0;
				if (atBoundary) {
					int k1 = innerUpper >= 0 ? o.position()[innerUpper] : o.rows().get(i).size() - 1;
					for (; first <= l1; first++) {
						int w = lower.get(first);
						List<Integer> neighbours = adjacency.get(w);
						for (int r = 0; r < neighbours.size(); r++) {
							int u = neighbours.get(r);
							if (o.layer()[u] == i && (o.position()[u] < k0 || o.position()[u] > k1)) {
								marked[w][r] = true;
							}
						}
					}
					k0 = k1;
				}
			}
		}
		return marked;
	}

	/** The upper neighbour of a dummy {@code v} that is itself a dummy (its inner segment), or -1. */
	private static int innerUpperNeighbour(int v, Oriented o, List<List<Integer>> adjacency, boolean[] dummy) {
		if (!dummy[v]) {
			return -1;
		}
		for (int u : adjacency.get(v)) {
			if (o.layer()[u] == o.layer()[v] - 1 && dummy[u]) {
				return u;
			}
		}
		return -1;
	}

	// ---- vertical alignment ------------------------------------------------------------------------

	/** Aligns each vertex to the median of its upper neighbours, forming blocks. Returns {root[], align[]}. */
	private static int[][] align(Oriented o, List<List<Integer>> adjacency, boolean[][] marked,
			boolean horizontalFlip) {
		int count = o.layer().length;
		int[] root = new int[count];
		int[] align = new int[count];
		for (int v = 0; v < count; v++) {
			root[v] = v;
			align[v] = v;
		}
		for (int i = 1; i < o.rows().size(); i++) {
			int lastUsed = -1;   // upper position last consumed, keeping the alignment monotonic (crossing-free)
			for (int w : o.rows().get(i)) {
				List<int[]> uppers = upperNeighbours(w, o, adjacency);   // {position, vertex, markRank}
				int d = uppers.size();
				if (d == 0) {
					continue;
				}
				for (int m = (d - 1) / 2; m <= d / 2; m++) {
					if (align[w] != w) {
						break;   // already aligned this vertex
					}
					int[] candidate = uppers.get(m);
					int u = candidate[1];
					boolean isMarked = marked[w][candidate[2]];
					if (!isMarked && lastUsed < candidate[0]) {
						align[u] = w;
						root[w] = root[u];
						align[w] = root[u];
						lastUsed = candidate[0];
					}
				}
			}
		}
		return new int[][] { root, align };
	}

	/** {@code w}'s neighbours one layer up, sorted by position, each carrying its rank in {@code w}'s adjacency. */
	private static List<int[]> upperNeighbours(int w, Oriented o, List<List<Integer>> adjacency) {
		List<int[]> uppers = new ArrayList<>();
		List<Integer> neighbours = adjacency.get(w);
		for (int r = 0; r < neighbours.size(); r++) {
			int u = neighbours.get(r);
			if (o.layer()[u] == o.layer()[w] - 1) {
				uppers.add(new int[] { o.position()[u], u, r });
			}
		}
		uppers.sort((a, b) -> Integer.compare(a[0], b[0]));
		return uppers;
	}

	// ---- horizontal compaction ---------------------------------------------------------------------

	/** Places the blocks left-to-right with width-aware separation (Brandes &amp; Köpf, Algorithm 3). */
	private static double[] compact(Oriented o, List<List<Integer>> adjacency, int[] root, int[] align,
			double[] width, double nodeGap) {
		int count = o.layer().length;
		int[] sink = new int[count];
		double[] shift = new double[count];
		double[] x = new double[count];
		for (int v = 0; v < count; v++) {
			sink[v] = v;
			shift[v] = Double.POSITIVE_INFINITY;
			x[v] = Double.NaN;
		}
		for (int v = 0; v < count; v++) {
			if (root[v] == v) {
				placeBlock(v, o, root, align, sink, shift, x, width, nodeGap);
			}
		}
		for (int v = 0; v < count; v++) {
			x[v] = x[root[v]];
			double classShift = shift[sink[root[v]]];
			if (classShift < Double.POSITIVE_INFINITY) {
				x[v] += classShift;
			}
		}
		return x;
	}

	private static void placeBlock(int v, Oriented o, int[] root, int[] align, int[] sink, double[] shift,
			double[] x, double[] width, double nodeGap) {
		if (!Double.isNaN(x[v])) {
			return;
		}
		x[v] = 0;
		int w = v;
		do {
			int pos = o.position()[w];
			if (pos > 0) {
				int leftNeighbour = o.rows().get(o.layer()[w]).get(pos - 1);
				int u = root[leftNeighbour];
				placeBlock(u, o, root, align, sink, shift, x, width, nodeGap);
				if (sink[v] == v) {
					sink[v] = sink[u];
				}
				double separation = (width[leftNeighbour] + width[w]) / 2 + nodeGap;
				if (sink[v] != sink[u]) {
					shift[sink[u]] = Math.min(shift[sink[u]], x[v] - x[u] - separation);
				}
				else {
					x[v] = Math.max(x[v], x[u] + separation);
				}
			}
			w = align[w];
		}
		while (w != v);
	}

	// ---- balancing ---------------------------------------------------------------------------------

	/**
	 * Combines the four layouts into one. Each is shifted to share the extent of the narrowest layout
	 * (left-biased ones aligned at the minimum, right-biased at the maximum), then every vertex takes the
	 * average of the two middle coordinates — the robust median that gives the method its symmetry.
	 */
	private static double[] balance(double[][] candidates, boolean[] leftBiased, int count) {
		int narrowest = 0;
		double minWidth = width(candidates[0]);
		for (int k = 1; k < candidates.length; k++) {
			double w = width(candidates[k]);
			if (w < minWidth) {
				minWidth = w;
				narrowest = k;
			}
		}
		double refMin = min(candidates[narrowest]);
		double refMax = max(candidates[narrowest]);
		for (int k = 0; k < candidates.length; k++) {
			double shift = leftBiased[k] ? refMin - min(candidates[k]) : refMax - max(candidates[k]);
			for (int v = 0; v < count; v++) {
				candidates[k][v] += shift;
			}
		}

		double[] result = new double[count];
		for (int v = 0; v < count; v++) {
			double[] four = { candidates[0][v], candidates[1][v], candidates[2][v], candidates[3][v] };
			Arrays.sort(four);
			result[v] = (four[1] + four[2]) / 2;
		}
		double base = min(result);
		for (int v = 0; v < count; v++) {
			result[v] -= base;
		}
		return result;
	}

	private static double width(double[] x) {
		return max(x) - min(x);
	}

	private static double min(double[] x) {
		double m = Double.POSITIVE_INFINITY;
		for (double v : x) {
			m = Math.min(m, v);
		}
		return m;
	}

	private static double max(double[] x) {
		double m = Double.NEGATIVE_INFINITY;
		for (double v : x) {
			m = Math.max(m, v);
		}
		return m;
	}

	private static void negate(double[] x) {
		for (int i = 0; i < x.length; i++) {
			x[i] = -x[i];
		}
	}
}
