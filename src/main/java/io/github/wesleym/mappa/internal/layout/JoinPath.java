package io.github.wesleym.mappa.internal.layout;

import io.github.wesleym.mappa.internal.model.Link;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Shortest join path between two tables, over the diagram's foreign-key edges treated as undirected — the
 * model behind the "trace a join path" interaction. Pure and unit-tested; the canvas only animates the
 * edge indices this returns.
 */
public final class JoinPath {

	private JoinPath() { }

	/**
	 * The edges (by index into {@code edges}) on a fewest-hop path from table {@code from} to table
	 * {@code to}, or an empty list when the two are the same table or no path connects them. Breadth-first,
	 * so the result is a minimal-hop route; ties are broken by edge declaration order.
	 */
	public static List<Integer> shortest(List<Link> edges, int from, int to) {
		if (from == to) {
			return List.of();
		}
		Map<Integer, List<int[]>> adjacency = new HashMap<>();   // node -> list of {neighbour, edgeIndex}
		for (int i = 0; i < edges.size(); i++) {
			Link edge = edges.get(i);
			adjacency.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(new int[] { edge.to(), i });
			adjacency.computeIfAbsent(edge.to(), k -> new ArrayList<>()).add(new int[] { edge.from(), i });
		}
		Map<Integer, Integer> cameFromNode = new HashMap<>();
		Map<Integer, Integer> cameFromEdge = new HashMap<>();
		Deque<Integer> queue = new ArrayDeque<>();
		queue.add(from);
		cameFromNode.put(from, from);   // mark visited (its own predecessor)
		while (!queue.isEmpty()) {
			int node = queue.poll();
			if (node == to) {
				break;
			}
			for (int[] step : adjacency.getOrDefault(node, List.of())) {
				int neighbour = step[0];
				if (cameFromNode.putIfAbsent(neighbour, node) == null) {
					cameFromEdge.put(neighbour, step[1]);
					queue.add(neighbour);
				}
			}
		}
		if (!cameFromNode.containsKey(to)) {
			return List.of();   // disconnected
		}
		LinkedList<Integer> path = new LinkedList<>();
		for (int node = to; node != from; node = cameFromNode.get(node)) {
			path.addFirst(cameFromEdge.get(node));
		}
		return path;
	}
}
