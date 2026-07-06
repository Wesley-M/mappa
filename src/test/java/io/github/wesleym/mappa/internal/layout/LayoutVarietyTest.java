package io.github.wesleym.mappa.internal.layout;

import io.github.wesleym.mappa.Mappa;
import io.github.wesleym.mappa.MappaMap;
import io.github.wesleym.mappa.internal.model.EntityBox;
import io.github.wesleym.mappa.internal.model.Link;
import io.github.wesleym.mappa.internal.model.Scene;
import io.github.wesleym.mappa.internal.model.SceneBuilder;

import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.image.BufferedImage;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayoutVarietyTest {

	private static final Font FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
	private static final BufferedImage PROBE = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
	private static final SceneBuilder.TextWidth WIDTH =
			(t, f) -> PROBE.createGraphics().getFontMetrics(f).stringWidth(t);
	private static final LayoutEngine.Box BOX = new LayoutEngine.Box(160, 100);

	private static Scene chain() {
		MappaMap map = Mappa.schema("Chain")
				.table("a", t -> t.primaryKey("id", "int").reference("b_id", "int", "b", "id"))
				.table("b", t -> t.primaryKey("id", "int").reference("c_id", "int", "c", "id"))
				.table("c", t -> t.primaryKey("id", "int"))
				.build();
		return SceneBuilder.build(map, FONT, FONT, WIDTH, true, false, false, LayoutStyle.LAYERED);
	}

	@Test
	void everyEdgeStyleShapesARoutedEdge() {
		Scene scene = chain();
		EdgeRouter.EdgeGeometry g = EdgeRouter.route(scene).get(0);
		for (EdgeStyle style : EdgeStyle.values()) {
			Path2D path = style.path(g.waypoints(), g.startHorizontal(), g.endHorizontal());
			assertFalse(path.getCurrentPoint() == null, style + " produced a path");
			assertFalse(style.label().isBlank());
		}
	}

	@Test
	void routerPrimitivesProducePaths() {
		List<Point2D> pts = List.of(new Point2D.Double(0, 0), new Point2D.Double(50, 60), new Point2D.Double(120, 0));
		List<Point2D> one = List.of(new Point2D.Double(0, 0));
		assertDoesNotThrow(() -> {
			EdgeRouter.spline(pts);
			EdgeRouter.spline(pts, true, false);
			EdgeRouter.orthogonal(pts, true, true);
			EdgeRouter.straight(pts);
			// Degenerate inputs: empty and single-point point lists hit the guard branches.
			EdgeRouter.spline(List.of());
			EdgeRouter.orthogonal(List.of(), false, false);
			EdgeRouter.straight(List.of());
			EdgeRouter.spline(one);
			// Coincident points → zero-length tangents, exercising the degenerate-tangent guard.
			EdgeRouter.spline(List.of(new Point2D.Double(5, 5), new Point2D.Double(5, 5),
					new Point2D.Double(5, 5)), true, true);
		});
	}

	@Test
	void labelLayoutPlacesFreeAndPinnedLabels() {
		Scene scene = chain();
		List<Path2D> paths = new java.util.ArrayList<>();
		for (EdgeRouter.EdgeGeometry g : EdgeRouter.route(scene)) {
			paths.add(EdgeStyle.CURVED.path(g.waypoints(), g.startHorizontal(), g.endHorizontal()));
		}
		var fm = PROBE.createGraphics().getFontMetrics(FONT);
		assertFalse(LabelLayout.layout(scene, paths, fm, Map.of()).isEmpty(), "free placement labels the edges");
		// Pin the first edge's label to a fixed point — exercises the pinned branch.
		Map<Integer, LabelLayout.Placed> pinned =
				LabelLayout.layout(scene, paths, fm, Map.of(0, new Point2D.Double(300, 400)));
		assertTrue(pinned.containsKey(0));
	}

	@Test
	void everyStyleHandlesTinyAndSingletonGraphs() {
		for (LayoutStyle style : LayoutStyle.values()) {
			assertEquals(1, LayoutEngine.layout(List.of(BOX), List.of(),
					LayeredLayout.LAYER_GAP, style).centres().size(), style + " places a lone box");
			assertEquals(2, LayoutEngine.layout(List.of(BOX, BOX), List.of(new LayoutEngine.Edge(0, 1)),
					LayeredLayout.LAYER_GAP, style).centres().size(), style + " places a pair");
		}
	}

	@Test
	void everyStyleHandlesADenseHubAndSpoke() {
		// A larger hub-and-spoke (0 links to 1..8) exercises the ring/relaxation/grid-fill branches per style.
		List<LayoutEngine.Box> boxes = new java.util.ArrayList<>();
		List<LayoutEngine.Edge> edges = new java.util.ArrayList<>();
		for (int i = 0; i < 9; i++) {
			boxes.add(BOX);
		}
		for (int i = 1; i < 9; i++) {
			edges.add(new LayoutEngine.Edge(0, i));
		}
		for (LayoutStyle style : LayoutStyle.values()) {
			assertEquals(9, LayoutEngine.layout(boxes, edges, LayeredLayout.LAYER_GAP, style).centres().size(),
					style + " places every node");
		}
	}

	@Test
	void routerAttachesToSidesAndSpreadsParallelEdges() {
		// A hub with two neighbours off to the right: both edges leave the hub's right side at distinct ports.
		EntityBox hub = box(0, 0);
		EntityBox upper = box(420, -90);
		EntityBox lower = box(420, 90);
		Scene scene = new Scene(List.of(hub, upper, lower),
				List.of(new Link(0, 1), new Link(0, 2)), List.of(List.of(), List.of()));

		List<EdgeRouter.EdgeGeometry> routed = EdgeRouter.route(scene);
		assertEquals(2, routed.size());
		assertEquals(EdgeRouter.Side.RIGHT, routed.get(0).startSide(), "edge attaches to the side facing the target");
		assertTrue(routed.get(0).start().getY() != routed.get(1).start().getY(),
				"parallel edges spread to different ports on the shared side");
	}

	@Test
	void routerAttachesTowardTheTargetInEachDirection() {
		// A centre box with neighbours left, above and below → LEFT / TOP / BOTTOM side selection.
		EntityBox centre = box(0, 0);
		Scene s = new Scene(List.of(centre, box(-460, 0), box(0, -380), box(0, 380)),
				List.of(new Link(0, 1), new Link(0, 2), new Link(0, 3)),
				List.of(List.of(), List.of(), List.of()));
		List<EdgeRouter.EdgeGeometry> r = EdgeRouter.route(s);
		assertEquals(3, r.size());
		assertTrue(r.stream().anyMatch(g -> g.startSide() == EdgeRouter.Side.LEFT), "reaches a left neighbour");
		assertTrue(r.stream().anyMatch(g -> g.startSide() == EdgeRouter.Side.TOP
				|| g.startSide() == EdgeRouter.Side.BOTTOM), "reaches a vertical neighbour");
	}

	private static EntityBox box(double cx, double cy) {
		EntityBox b = new EntityBox("t", List.of(), false);
		b.placeCentre(new Point2D.Double(cx, cy), 160, 100);
		return b;
	}
}
