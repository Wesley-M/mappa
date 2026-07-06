package io.github.wesleym.mappa.internal.render;

import io.github.wesleym.mappa.Mappa;
import io.github.wesleym.mappa.MappaMap;
import io.github.wesleym.mappa.MappaMinimap;
import io.github.wesleym.mappa.MappaTheme;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The minimap visibility decision: AUTO gates on size, a corner forces it on, OFF always hides it. */
class MinimapPlacementTest {

	// A laid-out canvas with a chain of {@code tables} tables. Forces the minimap on to detect when the
	// off-thread layout has produced a scene, then leaves it in that state for the caller to re-decide.
	private static MappaCanvas laidOut(int tables) throws Exception {
		MappaCanvas canvas = new MappaCanvas(MappaTheme.light());
		canvas.setSize(1000, 700);
		canvas.setMinimap(MappaMinimap.BOTTOM_RIGHT);
		canvas.setMap(chain(tables));
		for (int i = 0; i < 40 && !canvas.minimapVisible(); i++) {
			Thread.sleep(20);
			SwingUtilities.invokeAndWait(() -> { });
		}
		assertTrue(canvas.minimapVisible(), "scene laid out and a forced corner is showing");
		return canvas;
	}

	@Test
	void autoGatesOnDiagramSize() throws Exception {
		MappaCanvas big = laidOut(30);
		big.setMinimap(MappaMinimap.AUTO);
		assertTrue(big.minimapVisible(), "AUTO shows on a large diagram");

		MappaCanvas small = laidOut(4);
		small.setMinimap(MappaMinimap.AUTO);
		assertFalse(small.minimapVisible(), "AUTO hides on a small diagram");
	}

	@Test
	void aCornerForcesItOnAtAnySize() throws Exception {
		MappaCanvas small = laidOut(4);
		small.setMinimap(MappaMinimap.TOP_LEFT);
		assertTrue(small.minimapVisible(), "a corner shows even on a small diagram");
	}

	@Test
	void offAlwaysHides() throws Exception {
		MappaCanvas big = laidOut(30);
		big.setMinimap(MappaMinimap.OFF);
		assertFalse(big.minimapVisible(), "OFF hides even on a large diagram");
	}

	private static MappaMap chain(int n) {
		var b = Mappa.schema("Chain");
		for (int i = 0; i < n; i++) {
			int idx = i;
			b.table("t" + i, t -> {
				t.primaryKey("id", "uuid");
				if (idx > 0) {
					t.reference("prev_id", "uuid", "t" + (idx - 1), "id");
				}
			});
		}
		return b.build();
	}
}
