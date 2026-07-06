package io.github.wesleym.mappa.internal.layout;

import io.github.wesleym.mappa.internal.model.Link;
import io.github.wesleym.mappa.internal.model.Scene;
import io.github.wesleym.mappa.internal.model.EntityBox;
import org.junit.jupiter.api.Test;

import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdgeRouterTest {

	@Test
	void edgeAttachesAtVerticalPortsFacingTheOtherTable() {
		Scene scene = new Scene(
				List.of(table(0, 0, 100, 60), table(0, 200, 100, 60)),
				List.of(new Link(0, 1)),
				List.of(List.of()));

		EdgeRouter.EdgeGeometry edge = EdgeRouter.route(scene).get(0);

		// Table 0 is above table 1, so the edge leaves 0's bottom and enters 1's top.
		assertEquals(EdgeRouter.Side.BOTTOM, edge.startSide(), "leaves the upper table from its bottom edge");
		assertEquals(EdgeRouter.Side.TOP, edge.endSide(), "enters the lower table at its top edge");
		assertEquals(30, edge.start().getY(), 0.001);    // bottom of table 0 (centre 0, half-height 30)
		assertEquals(170, edge.end().getY(), 0.001);     // top of table 1 (centre 200, half-height 30)
	}

	@Test
	void parallelEdgesSpreadAcrossTheSharedSide() {
		// Two edges leave table 0's bottom toward tables on the left and right; their ports must differ.
		Scene scene = new Scene(
				List.of(table(0, 0, 120, 60), table(-100, 200, 100, 60), table(100, 200, 100, 60)),
				List.of(new Link(0, 1), new Link(0, 2)),
				List.of(List.of(), List.of()));

		List<EdgeRouter.EdgeGeometry> routed = EdgeRouter.route(scene);

		double leftPortX = routed.get(0).start().getX();
		double rightPortX = routed.get(1).start().getX();
		assertNotEquals(leftPortX, rightPortX, "parallel edges should fan out, not stack");
		assertTrue(leftPortX < rightPortX, "ports order by the other end's x");
	}

	@Test
	void horizontallySeparatedTablesAttachOnTheSides() {
		// Two side-by-side tables in the same row: the edge should leave the left one's right side and enter
		// the right one's left side, not loop over the top/bottom.
		Scene scene = new Scene(
				List.of(table(0, 0, 100, 60), table(300, 0, 100, 60)),
				List.of(new Link(0, 1)),
				List.of(List.of()));

		EdgeRouter.EdgeGeometry edge = EdgeRouter.route(scene).get(0);

		assertEquals(EdgeRouter.Side.RIGHT, edge.startSide(), "leaves the left table's right side");
		assertEquals(EdgeRouter.Side.LEFT, edge.endSide(), "enters the right table's left side");
		assertEquals(50, edge.start().getX(), 0.001);    // right edge of table 0 (centre 0, half-width 50)
		assertEquals(250, edge.end().getX(), 0.001);     // left edge of table 1 (centre 300, half-width 50)
	}

	@Test
	void splineThroughTwoPointsProducesADrawablePath() {
		Scene scene = new Scene(
				List.of(table(0, 0, 100, 60), table(0, 200, 100, 60)),
				List.of(new Link(0, 1)),
				List.of(List.of()));

		var path = EdgeRouter.spline(EdgeRouter.route(scene).get(0).waypoints());
		assertTrue(path.getCurrentPoint() != null, "spline should have drawable geometry");
	}

	@Test
	void everyEdgeStyleProducesADrawablePath() {
		Scene scene = new Scene(
				List.of(table(0, 0, 100, 60), table(260, 140, 100, 60)),
				List.of(new Link(0, 1)),
				List.of(List.of()));
		EdgeRouter.EdgeGeometry edge = EdgeRouter.route(scene).get(0);

		for (EdgeStyle style : EdgeStyle.values()) {
			Path2D path = style.path(edge.waypoints(), edge.startHorizontal(), edge.endHorizontal());
			assertNotNull(path.getCurrentPoint(), style + " should produce drawable geometry");
		}
	}

	@Test
	void orthogonalUsesOnlyAxisAlignedSegments() {
		// Two diagonally-offset tables: the Manhattan path between them must turn only at right angles.
		Scene scene = new Scene(
				List.of(table(0, 0, 100, 60), table(300, 180, 100, 60)),
				List.of(new Link(0, 1)),
				List.of(List.of()));
		EdgeRouter.EdgeGeometry edge = EdgeRouter.route(scene).get(0);

		Path2D path = EdgeRouter.orthogonal(edge.waypoints(), edge.startHorizontal(),
				edge.endHorizontal());
		double[] coords = new double[6];
		double px = 0;
		double py = 0;
		boolean first = true;
		for (PathIterator it = path.getPathIterator(null); !it.isDone(); it.next()) {
			it.currentSegment(coords);
			if (!first) {
				assertTrue(Math.abs(coords[0] - px) < 1e-6 || Math.abs(coords[1] - py) < 1e-6,
						"every Manhattan segment must be horizontal or vertical");
			}
			first = false;
			px = coords[0];
			py = coords[1];
		}
	}

	private static EntityBox table(double cx, double cy, double width, double height) {
		EntityBox table = new EntityBox("t", List.of(), false);
		table.placeCentre(new Point2D.Double(cx, cy), width, height);
		return table;
	}
}
