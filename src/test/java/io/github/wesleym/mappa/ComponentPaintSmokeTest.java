package io.github.wesleym.mappa;

import org.junit.jupiter.api.Test;

import javax.swing.JComponent;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The interactive component is the transplanted canvas. Its layout runs off-thread, so a headless paint may
 * catch the "laying out" hint rather than the finished scene — this smoke only guards the wiring: the canvas
 * constructs from the public API and paints without throwing.
 */
class ComponentPaintSmokeTest {

	@Test
	void liveComponentConstructsAndPaints() {
		JComponent component = Mappa.view(Fixtures.commerce())
				.layout(MappaLayout.LAYERED)
				.edges(MappaEdges.DIRECTIONAL)
				.background(MappaBackground.DOTS)
				.relationshipLabels(true)
				.theme(MappaTheme.dark())
				.onEntitySelected(entity -> { })
				.component();
		assertNotNull(component);
		component.setSize(1000, 700);

		BufferedImage image = new BufferedImage(1000, 700, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		try {
			assertDoesNotThrow(() -> component.paint(g));
		}
		finally {
			g.dispose();
		}
	}
}
