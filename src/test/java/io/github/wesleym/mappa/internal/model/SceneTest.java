package io.github.wesleym.mappa.internal.model;

import io.github.wesleym.mappa.Mappa;
import io.github.wesleym.mappa.MappaMap;

import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneTest {

	private static final Font FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
	private static final BufferedImage PROBE = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);

	private static Scene built() {
		MappaMap map = Mappa.schema("S")
				.table("a", t -> t.primaryKey("id", "int").reference("b_id", "int", "b", "id"))
				.table("b", t -> t.primaryKey("id", "int"))
				.build();
		return SceneBuilder.build(map, FONT, FONT, (t, f) -> PROBE.createGraphics().getFontMetrics(f).stringWidth(t),
				false);
	}

	@Test
	void emptySceneHasNoTablesOrBounds() {
		Scene empty = Scene.empty();
		assertTrue(empty.isEmpty());
		assertTrue(empty.tables().isEmpty());
		assertNull(empty.worldBounds());
		assertEquals(-1, empty.indexOf(new EntityBox("x", java.util.List.of(), false)));
		assertNull(empty.tableAt(new Point2D.Double(0, 0)));
	}

	@Test
	void indexOfIsIdentityBased() {
		Scene scene = built();
		EntityBox first = scene.tables().get(0);
		assertEquals(0, scene.indexOf(first));
		assertEquals(-1, scene.indexOf(new EntityBox("ghost", java.util.List.of(), false)));
	}

	@Test
	void tableAtHitsTheBoxUnderThePointAndMissesEmptySpace() {
		Scene scene = built();
		EntityBox box = scene.tables().get(0);
		Point2D inside = new Point2D.Double(box.bounds().getCenterX(), box.bounds().getCenterY());
		assertSame(box, scene.tableAt(inside));
		assertNull(scene.tableAt(new Point2D.Double(-10_000, -10_000)));
	}

	@Test
	void worldBoundsEnclosesEveryBox() {
		Scene scene = built();
		var world = scene.worldBounds();
		for (EntityBox box : scene.tables()) {
			assertTrue(world.contains(box.bounds()), "world bounds enclose each box");
		}
	}
}
