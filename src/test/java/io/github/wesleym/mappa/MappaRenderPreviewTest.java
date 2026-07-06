package io.github.wesleym.mappa;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The render-and-look gate: every layout, edge style, background, and detail level rendered headless to
 * {@code build/mappa-*.png} so a human can eyeball them, and asserted non-blank so a broken renderer
 * fails the build rather than shipping an empty diagram.
 */
class MappaRenderPreviewTest {

	@Test
	void everyLayoutRenders() throws Exception {
		for (MappaLayout layout : MappaLayout.values()) {
			write("layout-" + layout.name().toLowerCase(),
					Mappa.view(Fixtures.commerce()).layout(layout).theme(MappaTheme.light()).image(1000, 680));
		}
	}

	@Test
	void everyEdgeStyleRenders() throws Exception {
		for (MappaEdges edges : MappaEdges.values()) {
			write("edges-" + edges.name().toLowerCase(), Mappa.view(Fixtures.commerce())
					.layout(MappaLayout.LAYERED).edges(edges).relationshipLabels(true).image(1000, 680));
		}
	}

	@Test
	void everyBackgroundRenders() throws Exception {
		for (MappaBackground background : MappaBackground.values()) {
			write("background-" + background.name().toLowerCase(),
					Mappa.view(Fixtures.store()).background(background).theme(MappaTheme.dark()).image(760, 520));
		}
	}

	@Test
	void detailLevelsRender() throws Exception {
		for (MappaDetail detail : MappaDetail.values()) {
			write("detail-" + detail.name().toLowerCase(),
					Mappa.view(Fixtures.commerce()).detail(detail).image(1000, 680));
		}
	}

	@Test
	void darkThemeRendersWithCustomAccent() throws Exception {
		write("theme-dark", Mappa.view(Fixtures.commerce())
				.theme(MappaTheme.dark())
				.layout(MappaLayout.FORCE)
				.image(1000, 680));
	}

	@Test
	void emptyMapRendersItsPlaceholderNotACrash() throws Exception {
		BufferedImage image = Mappa.view(new MappaMap("Empty", java.util.List.of(), java.util.List.of()))
				.image(480, 320);
		assertTrue(image.getWidth() == 480, "empty map still produces an image");
	}

	static void write(String name, BufferedImage image) throws Exception {
		File dir = new File("build");
		dir.mkdirs();
		File out = new File(dir, "mappa-" + name + ".png");
		ImageIO.write(image, "png", out);
		assertTrue(out.length() > 0, "wrote " + out);
		assertTrue(nonBackgroundPixels(image) > 500, name + " rendered visible content");
	}

	private static int nonBackgroundPixels(BufferedImage image) {
		int bg = image.getRGB(2, 2);
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
