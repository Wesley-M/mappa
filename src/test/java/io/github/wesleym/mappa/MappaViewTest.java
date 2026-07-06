package io.github.wesleym.mappa;

import org.junit.jupiter.api.Test;

import javax.swing.JComponent;

import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MappaViewTest {

	@Test
	void createsASwingComponent() {
		JComponent component = Mappa.view(MappaBuilderTest.sample())
				.layout(MappaLayout.LAYERED)
				.edges(MappaEdges.DIRECTIONAL)
				.detail(MappaDetail.KEYS)
				.background(MappaBackground.GRID)
				.component();

		assertNotNull(component);
	}

	@Test
	void rendersANonBlankImage() {
		BufferedImage image = Mappa.view(MappaBuilderTest.sample())
				.theme(MappaTheme.light().accent(new Color(0x7C3AED)))
				.relationshipLabels(true)
				.image(720, 480);

		assertTrue(nonBackgroundPixels(image) > 1000);
	}

	private static int nonBackgroundPixels(BufferedImage image) {
		int bg = image.getRGB(0, 0);
		int count = 0;
		for (int y = 0; y < image.getHeight(); y += 3) {
			for (int x = 0; x < image.getWidth(); x += 3) {
				if (image.getRGB(x, y) != bg) {
					count++;
				}
			}
		}
		return count;
	}
}
