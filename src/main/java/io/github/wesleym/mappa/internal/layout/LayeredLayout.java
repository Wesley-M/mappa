package io.github.wesleym.mappa.internal.layout;

import io.github.wesleym.mappa.internal.layout.LayoutEngine.Box;
import io.github.wesleym.mappa.internal.layout.LayoutEngine.Edge;

import java.awt.geom.Point2D;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A Sugiyama-style layered layout for one connected cluster of tables — the layout schema tools
 * (SchemaSpy via Graphviz {@code dot}, DBeaver) use, and the one comprehension studies favour for
 * relational data. Referenced (parent) tables float toward the top, the tables that reference them sit
 * below, and edges flow in one direction.
 *
 * <p>The classic phases: break cycles into a DAG, assign layers by longest path, insert <em>dummy</em>
 * vertices so every edge only spans one layer (the dummies reserve routing channels, so long edges bend
 * around boxes instead of slicing through them), reduce crossings by repeated median ordering, then
 * assign x-coordinates by barycenter alignment. Fully deterministic, so the result is unit-testable.
 *
 * <p>Returns box centres plus, for each input edge, the chain of bend points (dummy positions) it routes
 * through — empty when the edge connects adjacent layers and needs no bend.
 */
public final class LayeredLayout {

	public static final double LAYER_GAP = 80;
	private static final double NODE_GAP = 44;
	private static final double DUMMY_WIDTH = 14;   // routing channel width reserved for a through-edge
	private static final double DUMMY_HEIGHT = 8;
	private static final int CROSSING_SWEEPS = 8;
	private static final int TIGHTEN_PASSES = 6;

	private LayeredLayout() { }

	/** Box centres (input order) and, parallel to {@code edges}, each edge's bend points (from → to). */
	record Result(List<Point2D.Double> centres, List<List<Point2D.Double>> edgePaths) { }

	/**
	 * The size-independent layout structure for a cluster: the layered, crossing-reduced node order plus the
	 * dummy routing chains. It depends only on the topology (node count + edges), NOT on box sizes or the layer
	 * gap — so it's cached and reused across cosmetic re-layouts (keys-only / join-labels toggles), where only
	 * the boxes resize. {@link #place} turns a plan + the current sizes into the final coordinates. Read-only,
	 * so it's safe to share across the parallel per-cluster placement.
	 */
	private record Plan(int realCount, int count, int[] layer, List<List<Integer>> layers,
			List<List<Integer>> adjacency, List<List<Integer>> edgeChains) { }

	// Topology → plan. Identical topology (even across clusters/graphs) yields an identical plan, so a cosmetic
	// re-layout reuses it instead of re-running the expensive layering + crossing reduction. Bounded; cleared
	// wholesale past the cap (a coarse but cheap backstop — entries are tiny and the working set is small).
	private static final int PLAN_CACHE_MAX = 2_000;
	private static final Map<String, Plan> PLAN_CACHE = new ConcurrentHashMap<>();

	static Result layout(List<Box> boxes, List<Edge> edges, double layerGap) {
		int n = boxes.size();
		if (n <= 1) {
			List<Point2D.Double> centres = n == 0 ? List.of() : List.of(new Point2D.Double(0, 0));
			return new Result(centres, emptyPaths(edges.size()));
		}
		if (PLAN_CACHE.size() > PLAN_CACHE_MAX) {
			PLAN_CACHE.clear();
		}
		Plan plan = PLAN_CACHE.computeIfAbsent(planKey(n, edges), k -> new Augmented(boxes, edges).plan());
		return place(plan, boxes, layerGap);
	}

	// A stable signature of the topology (node count + edge endpoints, in order). Box sizes and the layer gap
	// are deliberately excluded — they don't change layering or ordering, only the final coordinates.
	private static String planKey(int nodeCount, List<Edge> edges) {
		StringBuilder key = new StringBuilder(16 + edges.size() * 6).append(nodeCount);
		for (Edge e : edges) {
			key.append(';').append(e.from()).append('>').append(e.to());
		}
		return key.toString();
	}

	/** Turns a cached topology plan + the current box sizes + layer gap into box centres and edge routes. */
	private static Result place(Plan plan, List<Box> boxes, double layerGap) {
		double[] width = new double[plan.count()];
		double[] height = new double[plan.count()];
		for (int i = 0; i < plan.count(); i++) {
			boolean real = i < plan.realCount();
			width[i] = real ? boxes.get(i).width() : DUMMY_WIDTH;
			height[i] = real ? boxes.get(i).height() : DUMMY_HEIGHT;
		}
		double[] y = rowCentres(plan, height, layerGap);
		boolean[] dummy = new boolean[plan.count()];
		for (int i = plan.realCount(); i < plan.count(); i++) {
			dummy[i] = true;
		}
		double[] x = LayeredCoordinateAssignment.assign(plan.layers(), plan.layer(), width, dummy, plan.adjacency(), NODE_GAP);
		return new Result(realCentres(plan, x, y), edgeRoutes(plan, x, y));
	}

	// y-centre per node: each layer's row height is its tallest box; rows stack with layerGap between them.
	private static double[] rowCentres(Plan plan, double[] height, double layerGap) {
		double[] rowY = new double[plan.count()];
		double top = 0;
		for (List<Integer> layerNodes : plan.layers()) {
			double rowHeight = layerNodes.stream().mapToDouble(node -> height[node]).max().orElse(0);
			double centre = top + rowHeight / 2;
			for (int node : layerNodes) {
				rowY[node] = centre;
			}
			top += rowHeight + layerGap;
		}
		return rowY;
	}

	private static List<Point2D.Double> realCentres(Plan plan, double[] x, double[] y) {
		List<Point2D.Double> centres = new ArrayList<>(plan.realCount());
		for (int node = 0; node < plan.realCount(); node++) {
			centres.add(new Point2D.Double(x[node], y[node]));
		}
		return centres;
	}

	private static List<List<Point2D.Double>> edgeRoutes(Plan plan, double[] x, double[] y) {
		List<List<Point2D.Double>> routes = new ArrayList<>(plan.edgeChains().size());
		for (List<Integer> chain : plan.edgeChains()) {
			List<Point2D.Double> bends = new ArrayList<>(chain.size());
			for (int dummy : chain) {
				bends.add(new Point2D.Double(x[dummy], y[dummy]));
			}
			routes.add(bends);
		}
		return routes;
	}

	private static List<List<Point2D.Double>> emptyPaths(int edgeCount) {
		List<List<Point2D.Double>> paths = new ArrayList<>(edgeCount);
		for (int i = 0; i < edgeCount; i++) {
			paths.add(List.of());
		}
		return paths;
	}

	/**
	 * The cluster expanded with dummy vertices: real boxes keep indices {@code [0, n)}, dummies follow. Builds
	 * the size-independent topology (layers, dummy chains, crossing-reduced order) — the coordinates are added
	 * later by the pure {@link #place} from the current box sizes, so this part can be cached and reused.
	 */
	private static final class Augmented {

		private final int realCount;
		private final List<Edge> edges;
		private final List<List<Integer>> edgeChains;   // dummy ids from edge.from() to edge.to()

		private int count;            // total vertices (reals + dummies)
		private int[] layer;
		private List<List<Integer>> adjacency;   // undirected, between consecutive layers only
		private List<List<Integer>> layers;       // vertex ids per layer

		Augmented(List<Box> boxes, List<Edge> edges) {
			this.realCount = boxes.size();
			this.edges = edges;
			this.edgeChains = new ArrayList<>(edges.size());
			int[] realLayers = layerReals(boxes.size(), edges);
			expandWithDummies(realLayers);
		}

		/** Computes the size-independent plan: layer assignment, dummy chains, and the crossing-reduced order. */
		Plan plan() {
			layers = groupByLayer();
			reduceCrossings();
			return new Plan(realCount, count, layer, layers, adjacency, edgeChains);
		}

		// ---- layer the real nodes (cycles broken, longest path) --------------------------------------

		private static int[] layerReals(int n, List<Edge> edges) {
			List<List<Integer>> dag = breakCycles(n, edges);
			int[] layer = longestPathLayers(n, dag);
			tighten(n, dag, layer);
			compactLayers(layer);
			return layer;
		}

		/** Renumbers the used layers to a contiguous 0..k-1, so tightening never leaves a blank band. */
		private static void compactLayers(int[] layer) {
			int[] sorted = layer.clone();
			Arrays.sort(sorted);
			Map<Integer, Integer> rank = new HashMap<>();
			for (int value : sorted) {
				rank.putIfAbsent(value, rank.size());
			}
			for (int i = 0; i < layer.length; i++) {
				layer[i] = rank.get(layer[i]);
			}
		}

		/**
		 * Pulls nodes toward their neighbours within each node's feasible band — above its lowest parent,
		 * below its highest child. Longest-path layering top-justifies every node, so source tables pile
		 * into the first row and edges run needlessly long; nudging each node to the median of its
		 * neighbours' layers shortens edges (fewer dummies, fewer crossings) without breaking the
		 * top-to-bottom flow. The cheap 80% of network-simplex layering (Gansner et al.).
		 */
		private static void tighten(int n, List<List<Integer>> childrenOf, int[] layer) {
			List<List<Integer>> parentsOf = emptyLists(n);
			for (int parent = 0; parent < n; parent++) {
				for (int child : childrenOf.get(parent)) {
					parentsOf.get(child).add(parent);
				}
			}
			for (int pass = 0; pass < TIGHTEN_PASSES; pass++) {
				boolean moved = false;
				for (int v = 0; v < n; v++) {
					List<Integer> parents = parentsOf.get(v);
					List<Integer> children = childrenOf.get(v);
					if (parents.isEmpty() && children.isEmpty()) {
						continue;
					}
					int floor = 0;          // can't sit on or above a parent
					for (int p : parents) {
						floor = Math.max(floor, layer[p] + 1);
					}
					int ceiling = children.isEmpty() ? layer[v] : Integer.MAX_VALUE;   // childless: don't drop lower
					for (int c : children) {
						ceiling = Math.min(ceiling, layer[c] - 1);
					}
					int target = Math.min(ceiling, Math.max(floor, medianLayer(parents, children, layer)));
					if (target != layer[v]) {
						layer[v] = target;
						moved = true;
					}
				}
				if (!moved) {
					break;
				}
			}
		}

		/** The median of the neighbours' layers — the position that minimises total incident edge length. */
		private static int medianLayer(List<Integer> parents, List<Integer> children, int[] layer) {
			List<Integer> neighbourLayers = new ArrayList<>(parents.size() + children.size());
			for (int p : parents) {
				neighbourLayers.add(layer[p]);
			}
			for (int c : children) {
				neighbourLayers.add(layer[c]);
			}
			neighbourLayers.sort(Integer::compareTo);
			int mid = neighbourLayers.size() / 2;
			return neighbourLayers.size() % 2 == 1
					? neighbourLayers.get(mid)
					: (neighbourLayers.get(mid - 1) + neighbourLayers.get(mid)) / 2;
		}

		/**
		 * Orients the FK graph (referenced table = parent, on top) into a DAG with as few edges fighting the
		 * flow as possible. We compute a low-feedback vertex order with the greedy Eades–Lin–Smyth heuristic,
		 * then keep forward arcs as-is and <em>reverse</em> the few backward ones — so a cycle's odd edge runs
		 * a short hop upward instead of a long one, rather than the arbitrary set a DFS would drop.
		 */
		private static List<List<Integer>> breakCycles(int n, List<Edge> edges) {
			List<List<Integer>> children = emptyLists(n);   // parent → children (downward)
			List<List<Integer>> parents = emptyLists(n);    // child → parents (upward)
			for (Edge e : edges) {
				if (e.from() != e.to()) {
					children.get(e.to()).add(e.from());     // referenced table is the parent
					parents.get(e.from()).add(e.to());
				}
			}
			int[] position = eadesLinSmythOrder(n, children, parents);
			List<List<Integer>> dag = emptyLists(n);
			for (int parent = 0; parent < n; parent++) {
				for (int child : children.get(parent)) {
					if (position[parent] <= position[child]) {
						dag.get(parent).add(child);     // forward: parent stays above child
					}
					else {
						dag.get(child).add(parent);     // backward arc reversed to keep the graph acyclic
					}
				}
			}
			return dag;
		}

		/**
		 * Eades–Lin–Smyth: peel sinks to the right and sources to the left, and when only a tangle of cycles
		 * remains, peel the vertex with the largest out-minus-in degree to the left. The resulting order has a
		 * small feedback arc set (the backward arcs). Ties break on the lowest index, so it's deterministic.
		 */
		private static int[] eadesLinSmythOrder(int n, List<List<Integer>> children, List<List<Integer>> parents) {
			boolean[] removed = new boolean[n];
			int[] outDegree = new int[n];
			int[] inDegree = new int[n];
			for (int v = 0; v < n; v++) {
				outDegree[v] = children.get(v).size();
				inDegree[v] = parents.get(v).size();
			}
			Deque<Integer> left = new ArrayDeque<>();    // sources / picks, in sequence
			Deque<Integer> right = new ArrayDeque<>();   // sinks, prepended so they end up at the tail
			int remaining = n;
			while (remaining > 0) {
				boolean peeled = true;
				while (peeled) {
					peeled = false;
					for (int v = 0; v < n; v++) {
						if (!removed[v] && outDegree[v] == 0) {
							removed[v] = true;
							remaining--;
							right.addFirst(v);
							for (int p : parents.get(v)) {
								outDegree[p]--;
							}
							peeled = true;
						}
					}
					for (int v = 0; v < n; v++) {
						if (!removed[v] && inDegree[v] == 0) {
							removed[v] = true;
							remaining--;
							left.addLast(v);
							for (int c : children.get(v)) {
								inDegree[c]--;
							}
							peeled = true;
						}
					}
				}
				if (remaining > 0) {
					int pick = -1;
					int bestDelta = Integer.MIN_VALUE;
					for (int v = 0; v < n; v++) {
						if (!removed[v] && outDegree[v] - inDegree[v] > bestDelta) {
							bestDelta = outDegree[v] - inDegree[v];
							pick = v;
						}
					}
					removed[pick] = true;
					remaining--;
					left.addLast(pick);
					for (int c : children.get(pick)) {
						inDegree[c]--;
					}
					for (int p : parents.get(pick)) {
						outDegree[p]--;
					}
				}
			}
			int[] position = new int[n];
			int index = 0;
			for (int v : left) {
				position[v] = index++;
			}
			for (int v : right) {
				position[v] = index++;
			}
			return position;
		}

		private static int[] longestPathLayers(int n, List<List<Integer>> dag) {
			int[] inDegree = new int[n];
			for (int u = 0; u < n; u++) {
				for (int v : dag.get(u)) {
					inDegree[v]++;
				}
			}
			Deque<Integer> ready = new ArrayDeque<>();
			for (int u = 0; u < n; u++) {
				if (inDegree[u] == 0) {
					ready.add(u);
				}
			}
			int[] layer = new int[n];
			while (!ready.isEmpty()) {
				int u = ready.poll();
				for (int v : dag.get(u)) {
					layer[v] = Math.max(layer[v], layer[u] + 1);
					if (--inDegree[v] == 0) {
						ready.add(v);
					}
				}
			}
			return layer;
		}

		// ---- expand with dummy vertices so each edge spans a single layer ----------------------------

		private void expandWithDummies(int[] realLayers) {
			int dummies = 0;
			for (Edge e : edges) {
				dummies += dummyCount(realLayers, e);
			}
			count = realCount + dummies;
			layer = new int[count];
			adjacency = emptyLists(count);
			for (int i = 0; i < realCount; i++) {
				layer[i] = realLayers[i];
			}

			int nextDummy = realCount;
			for (Edge e : edges) {
				edgeChains.add(buildChain(e, realLayers, nextDummy));
				nextDummy += dummyCount(realLayers, e);
			}
		}

		private static int dummyCount(int[] realLayers, Edge e) {
			if (e.from() == e.to()) {
				return 0;
			}
			return Math.max(0, Math.abs(realLayers[e.from()] - realLayers[e.to()]) - 1);
		}

		// Allocates this edge's dummy vertices, links the chain layer-by-layer, and returns the
		// dummy ids ordered from edge.from() to edge.to() (for routing).
		private List<Integer> buildChain(Edge e, int[] realLayers, int firstDummyId) {
			if (e.from() == e.to() || realLayers[e.from()] == realLayers[e.to()]) {
				if (e.from() != e.to()) {
					link(e.from(), e.to());   // same-layer edge: a direct (possibly flat) link
				}
				return List.of();
			}
			boolean fromOnTop = realLayers[e.from()] < realLayers[e.to()];
			int top = fromOnTop ? e.from() : e.to();
			int bottom = fromOnTop ? e.to() : e.from();

			List<Integer> midTopToBottom = new ArrayList<>();
			int id = firstDummyId;
			for (int l = realLayers[top] + 1; l < realLayers[bottom]; l++) {
				layer[id] = l;
				midTopToBottom.add(id++);
			}
			linkChain(top, midTopToBottom, bottom);

			List<Integer> fromTo = new ArrayList<>(midTopToBottom);
			if (!fromOnTop) {
				Collections.reverse(fromTo);   // route is listed from edge.from() downward/upward
			}
			return fromTo;
		}

		private void linkChain(int top, List<Integer> middle, int bottom) {
			int previous = top;
			for (int mid : middle) {
				link(previous, mid);
				previous = mid;
			}
			link(previous, bottom);
		}

		private void link(int a, int b) {
			adjacency.get(a).add(b);
			adjacency.get(b).add(a);
		}

		// ---- ordering + coordinates ------------------------------------------------------------------

		private List<List<Integer>> groupByLayer() {
			int layerCount = 0;
			for (int l : layer) {
				layerCount = Math.max(layerCount, l + 1);
			}
			List<List<Integer>> grouped = emptyLists(layerCount);
			for (int node = 0; node < count; node++) {
				grouped.get(layer[node]).add(node);
			}
			return grouped;
		}

		/**
		 * Orders the vertices within each layer to reduce edge crossings. Each sweep runs the median
		 * heuristic, then a {@code transpose} pass that swaps adjacent vertices whenever it lowers the
		 * crossing count (Gansner et al.) — median alone gets stuck, transpose clears the remainder. The
		 * ordering with the fewest crossings across all sweeps wins, rather than blindly taking the last.
		 */
		private void reduceCrossings() {
			int[] position = positions();
			List<List<Integer>> best = snapshotOrder();
			int bestCrossings = totalCrossings(position);
			for (int sweep = 0; sweep < CROSSING_SWEEPS && bestCrossings > 0; sweep++) {
				boolean downward = sweep % 2 == 0;
				if (downward) {
					for (int r = 1; r < layers.size(); r++) {
						orderByMedian(layers.get(r), r - 1, position);
					}
				}
				else {
					for (int r = layers.size() - 2; r >= 0; r--) {
						orderByMedian(layers.get(r), r + 1, position);
					}
				}
				transpose(position);
				int crossings = totalCrossings(position);
				if (crossings < bestCrossings) {
					bestCrossings = crossings;
					best = snapshotOrder();
				}
			}
			layers = best;
		}

		/** Repeatedly swaps adjacent same-layer vertices while doing so reduces crossings (terminates: each swap strictly lowers the total). */
		private void transpose(int[] position) {
			boolean improved = true;
			while (improved) {
				improved = false;
				for (List<Integer> row : layers) {
					for (int i = 0; i + 1 < row.size(); i++) {
						int left = row.get(i);
						int right = row.get(i + 1);
						if (pairCrossings(left, right, position) > pairCrossings(right, left, position)) {
							row.set(i, right);
							row.set(i + 1, left);
							position[left] = i + 1;
							position[right] = i;
							improved = true;
						}
					}
				}
			}
		}

		/** Crossings among the edges of {@code left} and {@code right} (with {@code left} ordered first). */
		private int pairCrossings(int left, int right, int[] position) {
			int crossings = 0;
			for (int a : adjacency.get(left)) {
				for (int b : adjacency.get(right)) {
					if (layer[a] == layer[b] && position[a] > position[b]) {
						crossings++;
					}
				}
			}
			return crossings;
		}

		private int totalCrossings(int[] position) {
			int total = 0;
			for (int r = 0; r + 1 < layers.size(); r++) {
				total += bilayerCrossings(layers.get(r), position);
			}
			return total;
		}

		/**
		 * Crossings between an (ordered) layer and the one below it, via the south-sequence inversion count
		 * of Barth, Jünger &amp; Mutzel (2002): list each upper vertex's lower-neighbour positions left to
		 * right, then count inversions in that sequence — O(E log V).
		 */
		private int bilayerCrossings(List<Integer> upper, int[] position) {
			List<Integer> southPositions = new ArrayList<>();
			int lowerLayer = -1;
			for (int u : upper) {
				List<Integer> down = new ArrayList<>();
				for (int neighbour : adjacency.get(u)) {
					if (layer[neighbour] == layer[u] + 1) {
						down.add(position[neighbour]);
						lowerLayer = layer[neighbour];
					}
				}
				down.sort(Integer::compareTo);
				southPositions.addAll(down);
			}
			return lowerLayer < 0 ? 0 : inversions(southPositions, layers.get(lowerLayer).size());
		}

		/** Number of inverted pairs in {@code sequence} (values in {@code [0, span)}), counted with a Fenwick tree. */
		private static int inversions(List<Integer> sequence, int span) {
			int[] tree = new int[span + 1];   // 1-indexed
			int crossings = 0;
			int placed = 0;
			for (int value : sequence) {
				crossings += placed - prefixCount(tree, value + 1);   // already-placed positions greater than this one
				for (int i = value + 1; i <= span; i += i & -i) {
					tree[i]++;
				}
				placed++;
			}
			return crossings;
		}

		private static int prefixCount(int[] tree, int index) {
			int sum = 0;
			for (int i = index; i > 0; i -= i & -i) {
				sum += tree[i];
			}
			return sum;
		}

		private List<List<Integer>> snapshotOrder() {
			List<List<Integer>> copy = new ArrayList<>(layers.size());
			for (List<Integer> row : layers) {
				copy.add(new ArrayList<>(row));
			}
			return copy;
		}

		private void orderByMedian(List<Integer> layerNodes, int referenceLayer, int[] position) {
			List<double[]> keyed = new ArrayList<>(layerNodes.size());   // {node, sortKey}
			for (int i = 0; i < layerNodes.size(); i++) {
				int node = layerNodes.get(i);
				double median = neighbourMedian(node, referenceLayer, position);
				keyed.add(new double[] { node, median >= 0 ? median : i });   // no neighbour → hold place
			}
			keyed.sort((a, b) -> Double.compare(a[1], b[1]));
			for (int i = 0; i < layerNodes.size(); i++) {
				int node = (int) keyed.get(i)[0];
				layerNodes.set(i, node);
				position[node] = i;
			}
		}

		private double neighbourMedian(int node, int referenceLayer, int[] position) {
			List<Integer> neighbourPositions = new ArrayList<>();
			for (int neighbour : adjacency.get(node)) {
				if (layer[neighbour] == referenceLayer) {
					neighbourPositions.add(position[neighbour]);
				}
			}
			if (neighbourPositions.isEmpty()) {
				return -1;
			}
			neighbourPositions.sort(Integer::compareTo);
			int mid = neighbourPositions.size() / 2;
			return neighbourPositions.size() % 2 == 1
					? neighbourPositions.get(mid)
					: (neighbourPositions.get(mid - 1) + neighbourPositions.get(mid)) / 2.0;
		}

		private int[] positions() {
			int[] position = new int[count];
			for (List<Integer> layerNodes : layers) {
				for (int i = 0; i < layerNodes.size(); i++) {
					position[layerNodes.get(i)] = i;
				}
			}
			return position;
		}

		private static List<List<Integer>> emptyLists(int n) {
			List<List<Integer>> lists = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				lists.add(new ArrayList<>());
			}
			return lists;
		}
	}
}
