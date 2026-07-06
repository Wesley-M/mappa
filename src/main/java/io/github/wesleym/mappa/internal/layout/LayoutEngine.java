package io.github.wesleym.mappa.internal.layout;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lays out an ER diagram. Each connected cluster of tables is laid out on its own by
 * {@link LayeredLayout} (a Sugiyama layered layout with dummy-routed edges), then the clusters —
 * and the many lone tables — are packed into tidy shelves. Laying out clusters in isolation is what
 * keeps the picture readable: unrelated tables never tangle with each other.
 *
 * <p>Fully deterministic, so the same schema always lays out the same way and the result is
 * unit-testable. (The user can also drag boxes afterwards.)
 */
public final class LayoutEngine {

	// Community-detection resolution (γ): >1 favours smaller, finer communities so two distinct concepts in one
	// over-connected neighbourhood split into separate groups rather than merging. Tune up for finer, toward 1
	// for coarser.
	private static final double COMMUNITY_RESOLUTION = 1.25;

	/** A box to place, sized in pixels. */
	public record Box(double width, double height) { }

	/** A spring between the boxes at indices {@code from} and {@code to}. */
	public record Edge(int from, int to) { }

	/**
	 * Box centres (input order), each edge's bend points (from → to, parallel to the input edges), and the
	 * cluster (community) index each box was placed in (parallel to centres) — for the grouping background.
	 */
	public record Result(List<Point2D.Double> centres, List<List<Point2D.Double>> edgePaths, List<Integer> clusters) { }

	private static final double COMPONENT_GAP = 96;   // gap between packed clusters (room for their grouping panels)
	private static final double PACK_ASPECT = 1.6;    // target shelf width : height ratio
	private static final int CLUSTER_MIN = 40;        // a connected component bigger than this is split into communities
	private static final double CLUSTER_PAD = 90;     // space reserved around a cluster when placing the cluster graph

	private LayoutEngine() { }

	public static Result layout(List<Box> boxes, List<Edge> edges) {
		return layout(boxes, edges, LayeredLayout.LAYER_GAP);
	}

	public static Result layout(List<Box> boxes, List<Edge> edges, double layerGap) {
		return layout(boxes, edges, layerGap, LayoutStyle.LAYERED);
	}

	/**
	 * Lays the diagram out with the given per-cluster placement {@code style}. The clustering and packing are
	 * the same whichever style is chosen; only how a single cluster's boxes are positioned varies.
	 */
	public static Result layout(List<Box> boxes, List<Edge> edges, double layerGap, LayoutStyle style) {
		int count = boxes.size();
		List<List<Point2D.Double>> edgePaths = new ArrayList<>(edges.size());
		for (int i = 0; i < edges.size(); i++) {
			edgePaths.add(List.of());
		}
		if (count == 0) {
			return new Result(List.of(), edgePaths, List.of());
		}
		Point2D.Double[] centres = new Point2D.Double[count];
		Integer[] clusterOf = new Integer[count];
		// A small component lays out as one cluster; a big one is split into communities first, so the picture
		// reads as tidy neighbourhoods instead of one sprawling hierarchy.
		List<List<Integer>> groups = new ArrayList<>();
		for (List<Integer> component : connectedComponents(count, edges)) {
			groups.addAll(subdivide(component, edges));
		}
		// Each cluster's layered layout is independent and pure (no shared state), so a schema with many
		// communities lays them out across cores. The stream preserves encounter order, so the result stays
		// byte-for-byte deterministic; below a couple of clusters the thread hand-off isn't worth it.
		List<Cluster> clusters = groups.size() > 1
				? groups.parallelStream().map(group -> buildCluster(group, boxes, edges, layerGap, style)).toList()
				: groups.stream().map(group -> buildCluster(group, boxes, edges, layerGap, style)).toList();
		for (int ci = 0; ci < clusters.size(); ci++) {
			for (int global : clusters.get(ci).nodeIndices) {
				clusterOf[global] = ci;
			}
		}
		placeClusters(clusters, edges, clusterOf, style);
		for (Cluster cluster : clusters) {
			cluster.writeInto(centres, edgePaths);
		}
		return new Result(Arrays.asList(centres), edgePaths, Arrays.asList(clusterOf));
	}

	/**
	 * Splits a connected component into communities once it's large enough to be unreadable as one
	 * hierarchy; small components stay whole. Edges that cross communities aren't laid out inside any
	 * cluster — the renderer draws them directly between the packed clusters.
	 */
	private static List<List<Integer>> subdivide(List<Integer> component, List<Edge> edges) {
		if (component.size() <= CLUSTER_MIN) {
			return List.of(component);
		}
		Map<Integer, Integer> localIndex = new HashMap<>();
		for (int k = 0; k < component.size(); k++) {
			localIndex.put(component.get(k), k);
		}
		List<Edge> localEdges = new ArrayList<>();
		for (Edge e : edges) {
			Integer from = localIndex.get(e.from());
			Integer to = localIndex.get(e.to());
			if (from != null && to != null) {
				localEdges.add(new Edge(from, to));
			}
		}
		int[] community = GraphCommunities.detect(component.size(), localEdges, COMMUNITY_RESOLUTION);
		Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
		for (int k = 0; k < component.size(); k++) {
			groups.computeIfAbsent(community[k], c -> new ArrayList<>()).add(component.get(k));
		}
		return new ArrayList<>(groups.values());
	}

	/** Lays out one cluster (re-indexed to be self-contained) and normalises its top-left to the origin. */
	private static Cluster buildCluster(List<Integer> nodeIndices, List<Box> boxes, List<Edge> edges, double layerGap,
			LayoutStyle style) {
		Map<Integer, Integer> localIndex = new HashMap<>();
		for (int k = 0; k < nodeIndices.size(); k++) {
			localIndex.put(nodeIndices.get(k), k);
		}
		List<Box> clusterBoxes = nodeIndices.stream().map(boxes::get).toList();
		List<Edge> clusterEdges = new ArrayList<>();
		List<Integer> globalEdgeIndices = new ArrayList<>();
		for (int e = 0; e < edges.size(); e++) {
			Integer from = localIndex.get(edges.get(e).from());
			Integer to = localIndex.get(edges.get(e).to());
			if (from != null && to != null) {   // only edges wholly inside this cluster are laid out here
				clusterEdges.add(new Edge(from, to));
				globalEdgeIndices.add(e);
			}
		}
		LayeredLayout.Result laid = place(style, clusterBoxes, clusterEdges, layerGap);
		return normalise(nodeIndices, clusterBoxes, globalEdgeIndices, laid);
	}

	// Positions one cluster's boxes by the chosen style. Only the layered flow routes edges through bend chains;
	// the others place nodes and leave the edges straight for the router to attach and shape.
	private static LayeredLayout.Result place(LayoutStyle style, List<Box> boxes, List<Edge> edges,
			double layerGap) {
		double gap = ClusterLayouts.defaultGap();
		return switch (style) {
			case LAYERED -> LayeredLayout.layout(boxes, edges, layerGap);
			case RADIAL -> ClusterLayouts.radial(boxes, edges, gap);
			case FORCE -> ClusterLayouts.force(boxes, edges, gap);
			case GRID -> ClusterLayouts.grid(boxes, edges, gap);
		};
	}

	private static Cluster normalise(List<Integer> nodeIndices, List<Box> boxes,
			List<Integer> globalEdgeIndices, LayeredLayout.Result laid) {
		double minX = Double.POSITIVE_INFINITY;
		double minY = Double.POSITIVE_INFINITY;
		double maxX = Double.NEGATIVE_INFINITY;
		double maxY = Double.NEGATIVE_INFINITY;
		for (int k = 0; k < laid.centres().size(); k++) {
			double halfWidth = boxes.get(k).width() / 2;
			double halfHeight = boxes.get(k).height() / 2;
			minX = Math.min(minX, laid.centres().get(k).x - halfWidth);
			maxX = Math.max(maxX, laid.centres().get(k).x + halfWidth);
			minY = Math.min(minY, laid.centres().get(k).y - halfHeight);
			maxY = Math.max(maxY, laid.centres().get(k).y + halfHeight);
		}
		double shiftX = minX;
		double shiftY = minY;
		List<Point2D.Double> centres = shift(laid.centres(), shiftX, shiftY);
		List<List<Point2D.Double>> paths = new ArrayList<>(laid.edgePaths().size());
		for (List<Point2D.Double> path : laid.edgePaths()) {
			paths.add(shift(path, shiftX, shiftY));
		}
		return new Cluster(nodeIndices, centres, globalEdgeIndices, paths, maxX - minX, maxY - minY);
	}

	private static List<Point2D.Double> shift(List<Point2D.Double> points, double dx, double dy) {
		List<Point2D.Double> shifted = new ArrayList<>(points.size());
		for (Point2D.Double p : points) {
			shifted.add(new Point2D.Double(p.x - dx, p.y - dy));
		}
		return shifted;
	}

	/**
	 * Positions the clusters relative to each other. When bridges connect them, the cluster graph (each
	 * cluster a super-node sized to its panel, each bridge a weighted super-edge) is laid out with the same
	 * algorithm one level up — so strongly-linked communities end up adjacent and their bridges stay short
	 * instead of slicing across the picture. Clusters with no bridges between them just shelf-pack.
	 */
	private static void placeClusters(List<Cluster> clusters, List<Edge> edges, Integer[] clusterOf,
			LayoutStyle style) {
		if (clusters.size() <= 1) {
			if (!clusters.isEmpty()) {
				clusters.get(0).offsetX = 0;
				clusters.get(0).offsetY = 0;
			}
			return;
		}
		List<Edge> clusterEdges = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		for (Edge e : edges) {
			int from = clusterOf[e.from()];
			int to = clusterOf[e.to()];
			if (from != to && seen.add((long) from * clusters.size() + to)) {
				clusterEdges.add(new Edge(from, to));
			}
		}
		if (clusterEdges.isEmpty()) {
			packIntoShelves(clusters);   // nothing links the clusters → a plain tidy pack is best
			return;
		}
		List<Box> clusterBoxes = new ArrayList<>(clusters.size());
		for (Cluster c : clusters) {
			clusterBoxes.add(new Box(c.width + CLUSTER_PAD, c.height + CLUSTER_PAD));
		}
		// Lay the cluster meta-graph out in the same style as the diagram, so FORCE/RADIAL/GRID spread the
		// communities organically to all sides instead of a LAYERED stack (terminates: the meta-graph shrinks).
		Result placement = layout(clusterBoxes, clusterEdges, LayeredLayout.LAYER_GAP, style);
		for (int ci = 0; ci < clusters.size(); ci++) {
			Point2D.Double centre = placement.centres().get(ci);
			Cluster cluster = clusters.get(ci);
			cluster.offsetX = centre.x - cluster.width / 2;
			cluster.offsetY = centre.y - cluster.height / 2;
		}
	}

	/** Shelf-packs the clusters left-to-right, wrapping to a new row past a target width. */
	private static void packIntoShelves(List<Cluster> clusters) {
		double totalArea = 0;
		double widest = 0;
		for (Cluster c : clusters) {
			totalArea += c.width * c.height;
			widest = Math.max(widest, c.width);
		}
		double targetWidth = Math.max(Math.sqrt(totalArea) * PACK_ASPECT, widest);

		List<Cluster> tallestFirst = new ArrayList<>(clusters);
		tallestFirst.sort((a, b) -> Double.compare(b.height, a.height));
		double x = 0;
		double y = 0;
		double rowHeight = 0;
		for (Cluster c : tallestFirst) {
			if (x > 0 && x + c.width > targetWidth) {
				x = 0;
				y += rowHeight + COMPONENT_GAP;
				rowHeight = 0;
			}
			c.offsetX = x;
			c.offsetY = y;
			x += c.width + COMPONENT_GAP;
			rowHeight = Math.max(rowHeight, c.height);
		}
	}

	/** Groups box indices into connected clusters by union-find over the edges. */
	private static List<List<Integer>> connectedComponents(int count, List<Edge> edges) {
		int[] parent = new int[count];
		for (int i = 0; i < count; i++) {
			parent[i] = i;
		}
		for (Edge edge : edges) {
			union(parent, edge.from(), edge.to());
		}
		Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
		for (int i = 0; i < count; i++) {
			groups.computeIfAbsent(find(parent, i), root -> new ArrayList<>()).add(i);
		}
		return new ArrayList<>(groups.values());
	}

	private static int find(int[] parent, int i) {
		while (parent[i] != i) {
			parent[i] = parent[parent[i]];   // path halving
			i = parent[i];
		}
		return i;
	}

	private static void union(int[] parent, int a, int b) {
		int rootA = find(parent, a);
		int rootB = find(parent, b);
		if (rootA != rootB) {
			parent[rootA] = rootB;
		}
	}

	/** A laid-out cluster: members and edges by original index, normalised geometry, and shelf offset. */
	private static final class Cluster {

		private final List<Integer> nodeIndices;
		private final List<Point2D.Double> centres;
		private final List<Integer> globalEdgeIndices;
		private final List<List<Point2D.Double>> edgePaths;
		private final double width;
		private final double height;
		private double offsetX;
		private double offsetY;

		Cluster(List<Integer> nodeIndices, List<Point2D.Double> centres, List<Integer> globalEdgeIndices,
				List<List<Point2D.Double>> edgePaths, double width, double height) {
			this.nodeIndices = nodeIndices;
			this.centres = centres;
			this.globalEdgeIndices = globalEdgeIndices;
			this.edgePaths = edgePaths;
			this.width = width;
			this.height = height;
		}

		void writeInto(Point2D.Double[] globalCentres, List<List<Point2D.Double>> globalEdgePaths) {
			for (int k = 0; k < nodeIndices.size(); k++) {
				Point2D.Double c = centres.get(k);
				globalCentres[nodeIndices.get(k)] = new Point2D.Double(c.x + offsetX, c.y + offsetY);
			}
			for (int k = 0; k < globalEdgeIndices.size(); k++) {
				globalEdgePaths.set(globalEdgeIndices.get(k), shift(edgePaths.get(k), -offsetX, -offsetY));
			}
		}
	}
}
