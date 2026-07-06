package io.github.wesleym.mappa.internal.layout;

import org.junit.jupiter.api.Test;

import java.awt.geom.Point2D;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayoutEngineTest {

	private static final LayoutEngine.Box BOX = new LayoutEngine.Box(160, 100);

	@Test
	void emptyGraphProducesNoPoints() {
		assertTrue(LayoutEngine.layout(List.of(), List.of()).centres().isEmpty());
	}

	@Test
	void singleBoxIsPlacedAtItsHalfExtent() {
		// A lone box is its own cluster, normalised so its top-left sits at the origin.
		List<Point2D.Double> points = LayoutEngine.layout(List.of(BOX), List.of()).centres();
		assertEquals(1, points.size());
		assertEquals(BOX.width() / 2, points.get(0).x);
		assertEquals(BOX.height() / 2, points.get(0).y);
	}

	@Test
	void connectedBoxesDoNotOverlap() {
		List<LayoutEngine.Box> boxes = List.of(BOX, BOX, BOX);
		List<LayoutEngine.Edge> edges = List.of(
				new LayoutEngine.Edge(0, 1),
				new LayoutEngine.Edge(1, 2));

		List<Point2D.Double> points = LayoutEngine.layout(boxes, edges).centres();

		for (int i = 0; i < points.size(); i++) {
			for (int j = i + 1; j < points.size(); j++) {
				assertFalse(overlaps(points.get(i), points.get(j)),
						"boxes " + i + " and " + j + " overlap");
			}
		}
	}

	@Test
	void disconnectedClustersAndLoneBoxesDoNotOverlap() {
		// Two separate 2-box clusters plus one isolated box → three components, shelf-packed apart.
		List<LayoutEngine.Box> boxes = List.of(BOX, BOX, BOX, BOX, BOX);
		List<LayoutEngine.Edge> edges = List.of(
				new LayoutEngine.Edge(0, 1),
				new LayoutEngine.Edge(2, 3));

		List<Point2D.Double> points = LayoutEngine.layout(boxes, edges).centres();

		assertEquals(5, points.size());
		for (int i = 0; i < points.size(); i++) {
			for (int j = i + 1; j < points.size(); j++) {
				assertFalse(overlaps(points.get(i), points.get(j)),
						"boxes " + i + " and " + j + " overlap");
			}
		}
	}

	@Test
	void foreignKeyChainStacksIntoDistinctLayers() {
		// 0 → 1 → 2 (each references the next); the layered layout stacks them on three distinct rows.
		List<LayoutEngine.Box> boxes = List.of(BOX, BOX, BOX);
		List<LayoutEngine.Edge> edges = List.of(
				new LayoutEngine.Edge(0, 1),
				new LayoutEngine.Edge(1, 2));

		Set<Double> rows = new HashSet<>();
		for (Point2D.Double point : LayoutEngine.layout(boxes, edges).centres()) {
			rows.add(point.y);
		}
		assertEquals(3, rows.size());
	}

	@Test
	void edgeSpanningTwoLayersRoutesThroughOneBend() {
		// 0→1→2 stacks across three layers; the extra 0→2 edge spans two layers and gets a dummy bend.
		List<LayoutEngine.Box> boxes = List.of(BOX, BOX, BOX);
		List<LayoutEngine.Edge> edges = List.of(
				new LayoutEngine.Edge(0, 1),
				new LayoutEngine.Edge(1, 2),
				new LayoutEngine.Edge(0, 2));

		List<List<Point2D.Double>> paths = LayoutEngine.layout(boxes, edges).edgePaths();

		assertEquals(3, paths.size());
		assertTrue(paths.get(0).isEmpty(), "adjacent-layer edge needs no bend");
		assertTrue(paths.get(1).isEmpty(), "adjacent-layer edge needs no bend");
		assertEquals(1, paths.get(2).size(), "edge across two layers routes through one bend");
	}

	@Test
	void longChainAlignsVertically() {
		// 0→1→2→3, each a single relationship: Brandes–Köpf aligns the chain into one vertical block.
		List<LayoutEngine.Box> boxes = List.of(BOX, BOX, BOX, BOX);
		List<LayoutEngine.Edge> edges = List.of(
				new LayoutEngine.Edge(0, 1),
				new LayoutEngine.Edge(1, 2),
				new LayoutEngine.Edge(2, 3));

		List<Point2D.Double> points = LayoutEngine.layout(boxes, edges).centres();

		double x = points.get(0).x;
		for (Point2D.Double point : points) {
			assertEquals(x, point.x, 1e-6, "chain boxes should share one x");
		}
	}

	@Test
	void longEdgeRoutesStraightThroughAlignedDummies() {
		// 0→1→2→3 plus a 0→3 edge spanning three layers: its two routing dummies form an inner segment,
		// which Brandes–Köpf keeps straight — both bends share an x.
		List<LayoutEngine.Box> boxes = List.of(BOX, BOX, BOX, BOX);
		List<LayoutEngine.Edge> edges = List.of(
				new LayoutEngine.Edge(0, 1),
				new LayoutEngine.Edge(1, 2),
				new LayoutEngine.Edge(2, 3),
				new LayoutEngine.Edge(0, 3));

		List<List<Point2D.Double>> paths = LayoutEngine.layout(boxes, edges).edgePaths();

		List<Point2D.Double> spanning = paths.get(3);
		assertEquals(2, spanning.size(), "an edge across three layers routes through two bends");
		assertEquals(spanning.get(0).x, spanning.get(1).x, 1e-6, "the inner segment stays vertical");
	}

	@Test
	void tighteningPullsLooseSourceDownToShortenItsEdge() {
		// 0→1→2→3 is a four-layer chain; table 4 also references 3. Longest-path layering would strand 4
		// in the top row beside 0, stretching the 4→3 edge across three layers. Tightening pulls 4 down
		// next to 3, so that edge needs no bends and 4 no longer shares the top row with 0.
		List<LayoutEngine.Box> boxes = List.of(BOX, BOX, BOX, BOX, BOX);
		List<LayoutEngine.Edge> edges = List.of(
				new LayoutEngine.Edge(1, 0),
				new LayoutEngine.Edge(2, 1),
				new LayoutEngine.Edge(3, 2),
				new LayoutEngine.Edge(3, 4));

		LayoutEngine.Result result = LayoutEngine.layout(boxes, edges);

		assertTrue(result.edgePaths().get(3).isEmpty(), "the 4→3 edge should be tightened to adjacent layers");
		assertTrue(result.centres().get(4).y > result.centres().get(0).y, "table 4 should be pulled below the top row");
	}

	@Test
	void variableWidthBoxesInOneLayerDoNotOverlap() {
		// Two children of one parent share a layer; the compaction must honour their differing widths.
		LayoutEngine.Box narrow = new LayoutEngine.Box(100, 80);
		LayoutEngine.Box wide = new LayoutEngine.Box(300, 80);
		List<LayoutEngine.Box> boxes = List.of(BOX, narrow, wide);
		List<LayoutEngine.Edge> edges = List.of(
				new LayoutEngine.Edge(1, 0),
				new LayoutEngine.Edge(2, 0));

		List<Point2D.Double> points = LayoutEngine.layout(boxes, edges).centres();

		double gap = Math.abs(points.get(1).x - points.get(2).x);
		assertTrue(gap >= (narrow.width() + wide.width()) / 2, "the two children must not overlap");
	}

	@Test
	void layoutIsDeterministic() {
		List<LayoutEngine.Box> boxes = List.of(BOX, BOX, BOX, BOX);
		List<LayoutEngine.Edge> edges = List.of(
				new LayoutEngine.Edge(0, 1),
				new LayoutEngine.Edge(2, 3));

		assertEquals(LayoutEngine.layout(boxes, edges), LayoutEngine.layout(boxes, edges));
	}

	@Test
	void everyStylePlacesEveryBoxWithoutOverlapAndDeterministically() {
		// A small hub-and-spoke cluster (0 is the hub) plus a tail, so radial/force/grid each have structure.
		List<LayoutEngine.Box> boxes = List.of(BOX, BOX, BOX, BOX, BOX, BOX);
		List<LayoutEngine.Edge> edges = List.of(
				new LayoutEngine.Edge(0, 1),
				new LayoutEngine.Edge(0, 2),
				new LayoutEngine.Edge(0, 3),
				new LayoutEngine.Edge(0, 4),
				new LayoutEngine.Edge(4, 5));

		for (LayoutStyle style : LayoutStyle.values()) {
			LayoutEngine.Result result =
					LayoutEngine.layout(boxes, edges, LayeredLayout.LAYER_GAP, style);
			List<Point2D.Double> points = result.centres();
			assertEquals(boxes.size(), points.size(), style + " must place every box");
			assertEquals(boxes.size(), result.clusters().size(), style + " must tag every box with a cluster");
			// The geometric placements seat boxes on a regular lattice/rings, so none overlap. Force-directed
			// relaxation makes no such guarantee (the user can drag from there), so it's exempt.
			if (style != LayoutStyle.FORCE) {
				for (int i = 0; i < points.size(); i++) {
					for (int j = i + 1; j < points.size(); j++) {
						assertFalse(overlaps(points.get(i), points.get(j)),
								style + " overlaps boxes " + i + " and " + j);
					}
				}
			}
			// Same inputs, same output — the layout contract every style must honour.
			assertEquals(result, LayoutEngine.layout(boxes, edges, LayeredLayout.LAYER_GAP, style),
					style + " must be deterministic");
		}
	}

	@Test
	void radialPutsTheHubAtTheCentreOfItsSatellites() {
		// 0 is linked to 1..4; in a star the hub should sit (roughly) at the centroid of its ring members.
		List<LayoutEngine.Box> boxes = List.of(BOX, BOX, BOX, BOX, BOX);
		List<LayoutEngine.Edge> edges = List.of(
				new LayoutEngine.Edge(0, 1),
				new LayoutEngine.Edge(0, 2),
				new LayoutEngine.Edge(0, 3),
				new LayoutEngine.Edge(0, 4));

		List<Point2D.Double> points =
				LayoutEngine.layout(boxes, edges, LayeredLayout.LAYER_GAP, LayoutStyle.RADIAL).centres();

		double ringCentreX = 0;
		double ringCentreY = 0;
		for (int i = 1; i < points.size(); i++) {
			ringCentreX += points.get(i).x;
			ringCentreY += points.get(i).y;
		}
		ringCentreX /= points.size() - 1;
		ringCentreY /= points.size() - 1;
		assertEquals(ringCentreX, points.get(0).x, BOX.width(), "hub sits at the centre of its ring (x)");
		assertEquals(ringCentreY, points.get(0).y, BOX.height(), "hub sits at the centre of its ring (y)");
	}

	private static boolean overlaps(Point2D.Double a, Point2D.Double b) {
		return Math.abs(a.x - b.x) < BOX.width() && Math.abs(a.y - b.y) < BOX.height();
	}
}
