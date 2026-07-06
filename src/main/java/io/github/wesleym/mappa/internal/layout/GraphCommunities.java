package io.github.wesleym.mappa.internal.layout;

import io.github.wesleym.mappa.internal.layout.LayoutEngine.Edge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Modularity-based community detection (Leiden) used to split a large, single connected schema into readable
 * neighbourhoods before layout. Modularity rewards keeping densely-linked tables together while penalising
 * oversized communities, so it doesn't collapse everything around a hub the way label propagation would.
 *
 * <p>Leiden runs three phases per level: <b>local moving</b> (move each node to its best neighbour community),
 * <b>refinement</b> (split each community into connected sub-communities), then <b>aggregation</b> on the
 * refined partition. The refinement phase is what Leiden adds over plain Louvain: a community can only grow by
 * merging along edges, so every community it produces is internally connected — Louvain occasionally yields a
 * community whose tables aren't actually linked, which reads as a misleading group on the diagram.
 *
 * <p>Deterministic: singleton start, nodes visited in index order, ties broken by smallest community index — the
 * same schema always partitions the same way (and it's unit-testable). The {@code θ} randomness from the paper is
 * dropped in favour of a greedy, deterministic refinement so the diagram is stable across runs.
 */
final class GraphCommunities {

	private static final int MAX_PASSES = 20;   // local-moving sweeps per level
	private static final int MAX_LEVELS = 50;   // safety cap on aggregation levels (converges well before this)

	private GraphCommunities() { }

	/** Standard modularity ({@code γ = 1}). */
	static int[] detect(int n, List<Edge> edges) {
		return detect(n, edges, 1.0);
	}

	/**
	 * A community label per node (contiguous, 0-based). Nodes with no edges each get their own community.
	 * {@code resolution} (γ) tunes community granularity: above 1 yields smaller, finer communities (it raises
	 * the bar for keeping nodes together), which helps split two distinct concepts that modularity's resolution
	 * limit would otherwise merge into one oversized community.
	 */
	static int[] detect(int n, List<Edge> edges, double resolution) {
		List<Map<Integer, Double>> adjacency = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			adjacency.add(new HashMap<>());
		}
		double[] degree = new double[n];
		double twoM = 0;
		for (Edge e : edges) {
			if (e.from() == e.to() || e.from() < 0 || e.to() < 0 || e.from() >= n || e.to() >= n) {
				continue;
			}
			adjacency.get(e.from()).merge(e.to(), 1.0, Double::sum);
			adjacency.get(e.to()).merge(e.from(), 1.0, Double::sum);
			degree[e.from()]++;
			degree[e.to()]++;
			twoM += 2;
		}
		if (twoM == 0) {
			return identity(n);   // no edges → every node its own community
		}

		int[] origToLevel = identity(n);                   // original node → its node in the current aggregate level
		List<Map<Integer, Double>> levelAdjacency = adjacency;
		double[] levelDegree = degree;
		int levelCount = n;
		int[] partition = identity(levelCount);            // start from singletons

		for (int level = 0; level < MAX_LEVELS; level++) {
			partition = localMoving(levelCount, levelAdjacency, levelDegree, twoM, resolution, partition);
			int[] refined = refine(levelCount, levelAdjacency, levelDegree, twoM, resolution, partition);
			int refinedCount = max(refined) + 1;
			if (refinedCount == levelCount) {
				break;   // refinement consolidated nothing — aggregating wouldn't change anything; converged
			}

			// Aggregate on the REFINED partition: each next-level node is a connected sub-community. An edge
			// between two refined communities is the sum of the crossing edge weights.
			List<Map<Integer, Double>> nextAdjacency = new ArrayList<>(refinedCount);
			for (int i = 0; i < refinedCount; i++) {
				nextAdjacency.add(new HashMap<>());
			}
			double[] nextDegree = new double[refinedCount];
			for (int u = 0; u < levelCount; u++) {
				nextDegree[refined[u]] += levelDegree[u];
				for (Map.Entry<Integer, Double> nb : levelAdjacency.get(u).entrySet()) {
					int ru = refined[u];
					int rv = refined[nb.getKey()];
					if (ru != rv) {
						nextAdjacency.get(ru).merge(rv, nb.getValue(), Double::sum);
					}
				}
			}
			// The partition carried to the next level is the (coarser) community of each refined sub-community —
			// every node in a refined community shares its community, so any member's label is the right one.
			int[] nextPartition = new int[refinedCount];
			for (int u = 0; u < levelCount; u++) {
				nextPartition[refined[u]] = partition[u];
			}
			for (int orig = 0; orig < n; orig++) {
				origToLevel[orig] = refined[origToLevel[orig]];
			}
			levelAdjacency = nextAdjacency;
			levelDegree = nextDegree;
			levelCount = refinedCount;
			partition = nextPartition;
		}

		int[] membership = new int[n];
		for (int orig = 0; orig < n; orig++) {
			membership[orig] = partition[origToLevel[orig]];
		}
		return relabel(membership);
	}

	/** Leiden phase 1: from {@code initial}, move each node to the neighbour community with the best modularity gain. */
	private static int[] localMoving(int n, List<Map<Integer, Double>> adjacency, double[] degree, double twoM,
			double resolution, int[] initial) {
		int[] community = initial.clone();
		double[] communityDegree = new double[n];   // Σ_tot per community, from the initial partition
		for (int v = 0; v < n; v++) {
			communityDegree[community[v]] += degree[v];
		}
		boolean improved = true;
		for (int pass = 0; pass < MAX_PASSES && improved; pass++) {
			improved = false;
			for (int v = 0; v < n; v++) {
				int from = community[v];
				communityDegree[from] -= degree[v];

				Map<Integer, Double> weightToCommunity = new HashMap<>();
				for (Map.Entry<Integer, Double> nb : adjacency.get(v).entrySet()) {
					weightToCommunity.merge(community[nb.getKey()], nb.getValue(), Double::sum);
				}

				int best = from;
				double bestGain = weightToCommunity.getOrDefault(from, 0.0)
						- resolution * degree[v] * communityDegree[from] / twoM;
				List<Integer> candidates = new ArrayList<>(weightToCommunity.keySet());
				Collections.sort(candidates);   // deterministic tie-break: smaller community index wins
				for (int c : candidates) {
					double gain = weightToCommunity.get(c) - resolution * degree[v] * communityDegree[c] / twoM;
					if (gain > bestGain) {
						bestGain = gain;
						best = c;
					}
				}

				communityDegree[best] += degree[v];
				if (best != from) {
					community[v] = best;
					improved = true;
				}
			}
		}
		return relabel(community);
	}

	/**
	 * Leiden phase 2: a connected sub-partition of {@code partition}. Each refined community grows only by merging
	 * a node along an edge inside the same community, so it is always connected. Only well-connected singleton
	 * nodes initiate a merge (a node already absorbed into a community never moves again), which keeps the result
	 * deterministic and prevents the orphaning a naive merge would cause.
	 */
	private static int[] refine(int n, List<Map<Integer, Double>> adjacency, double[] degree, double twoM,
			double resolution, int[] partition) {
		int[] refined = identity(n);
		int[] size = new int[n];
		Arrays.fill(size, 1);
		double[] communityDegree = degree.clone();   // Σ_tot per refined community
		int communities = max(partition) + 1;
		double[] partitionDegree = new double[communities];   // Σ_tot per (coarse) community S
		for (int v = 0; v < n; v++) {
			partitionDegree[partition[v]] += degree[v];
		}

		for (int v = 0; v < n; v++) {
			if (refined[v] != v || size[v] != 1) {
				continue;   // only a node that is still its own singleton community may initiate
			}
			int s = partition[v];
			double weightToOwnCommunity = 0;
			for (Map.Entry<Integer, Double> nb : adjacency.get(v).entrySet()) {
				if (partition[nb.getKey()] == s) {
					weightToOwnCommunity += nb.getValue();
				}
			}
			// Well-connected to S? (the Leiden connectivity criterion at this resolution). A barely-attached node
			// stays its own refined community rather than being pulled in.
			if (weightToOwnCommunity < resolution * degree[v] * (partitionDegree[s] - degree[v]) / twoM) {
				continue;
			}

			Map<Integer, Double> weightToRefined = new HashMap<>();
			for (Map.Entry<Integer, Double> nb : adjacency.get(v).entrySet()) {
				if (partition[nb.getKey()] == s) {
					weightToRefined.merge(refined[nb.getKey()], nb.getValue(), Double::sum);
				}
			}
			int best = v;
			double bestGain = 0;
			List<Integer> candidates = new ArrayList<>(weightToRefined.keySet());
			Collections.sort(candidates);
			for (int t : candidates) {
				double gain = weightToRefined.get(t) - resolution * degree[v] * communityDegree[t] / twoM;
				if (gain > bestGain) {
					bestGain = gain;
					best = t;
				}
			}
			if (best != v) {
				communityDegree[best] += degree[v];
				communityDegree[v] -= degree[v];
				size[best]++;
				size[v]--;
				refined[v] = best;
			}
		}
		return relabel(refined);
	}

	private static int[] identity(int n) {
		int[] id = new int[n];
		for (int i = 0; i < n; i++) {
			id[i] = i;
		}
		return id;
	}

	private static int max(int[] values) {
		int m = 0;
		for (int v : values) {
			m = Math.max(m, v);
		}
		return m;
	}

	/** Renumbers labels to a contiguous 0..k-1 in first-seen order. */
	private static int[] relabel(int[] labels) {
		Map<Integer, Integer> dense = new LinkedHashMap<>();
		int[] out = new int[labels.length];
		for (int i = 0; i < labels.length; i++) {
			out[i] = dense.computeIfAbsent(labels[i], k -> dense.size());
		}
		return out;
	}
}
