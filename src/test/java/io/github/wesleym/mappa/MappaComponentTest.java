package io.github.wesleym.mappa;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The host-drivable viewport controls and the transparent-image export added to the public surface. */
class MappaComponentTest {

	@Test
	void componentExposesViewportControls() {
		MappaComponent component = Mappa.view(Fixtures.commerce()).component();
		assertNotNull(component);
		component.setSize(1000, 700);
		// The controls drive the live viewport; they must be safe to call at any time (before a first paint too).
		assertDoesNotThrow(() -> {
			component.zoomIn();
			component.zoomOut();
			component.fitView();
			component.setAnimating(false);
			component.setAnimating(true);
		});
	}

	@Test
	void liveSettersAndRevealDoNotThrow() {
		MappaComponent component = Mappa.view(Fixtures.commerce()).component();
		component.setSize(1000, 700);
		// The layout runs off-thread, so a headless call may land before the scene exists — these must all be
		// safe to call at any time (reveal simply reports no match until a scene is ready).
		assertDoesNotThrow(() -> {
			component.setLayout(MappaLayout.RADIAL);
			component.setEdges(MappaEdges.ORTHOGONAL);
			component.setDetail(MappaDetail.ALL_FIELDS);
			component.setDetail(MappaDetail.KEYS);
			component.setBackground(MappaBackground.GRID);
			component.setRelationshipLabels(true);
			component.setInferredOnly(true);
			component.setInferredOnly(false);
			component.setMap(Fixtures.commerce().focus("orders"));
			component.reveal("orders");
		});
	}

	@Test
	void transparentImageKeepsAnAlphaChannel() {
		MappaView view = Mappa.view(Fixtures.commerce()).theme(MappaTheme.light());
		BufferedImage opaque = view.image(600, 400, false);
		BufferedImage transparent = view.image(600, 400, true);

		// A corner is background in both: opaque fills it with the theme colour (alpha 255); transparent leaves it clear.
		assertEquals255(alpha(opaque.getRGB(2, 2)));
		assertTrue(alpha(transparent.getRGB(2, 2)) == 0, "transparent export leaves the background clear");
	}

	private static int alpha(int argb) {
		return (argb >> 24) & 0xFF;
	}

	private static void assertEquals255(int value) {
		assertTrue(value == 255, "opaque export fills the background: expected alpha 255 but was " + value);
	}
}
