package io.github.wesleym.mappa.internal.render;

import io.github.wesleym.mappa.Mappa;
import io.github.wesleym.mappa.MappaMap;
import io.github.wesleym.mappa.MappaTheme;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives a large diagram through the live canvas, zooms in, and paints it — so the overview minimap (and its
 * viewport frame) can be eyeballed in {@code build/minimap.png}. The canvas lays out off-thread, so this
 * waits for the scene before painting.
 */
class MinimapPreviewTest {

	private static MappaMap many(int n) {
		var b = Mappa.schema("Many");
		for (int i = 0; i < n; i++) {
			int idx = i;
			b.table("table_" + i, t -> {
				t.primaryKey("id", "uuid");
				t.column("name", "text");
				if (idx > 0) {
					t.reference("parent_id", "uuid", "table_" + (idx - 1), "id");
				}
				if (idx > 3) {
					t.reference("owner_id", "uuid", "table_" + (idx / 2), "id");
				}
			});
		}
		return b.build();
	}

	@Test
	void paintsTheMinimapOnALargeZoomedDiagram() throws Exception {
		int w = 1040;
		int h = 700;
		MappaCanvas canvas = new MappaCanvas(MappaTheme.light());
		canvas.setSize(w, h);
		canvas.setMap(many(45));

		// Let the off-thread layout + EDT apply settle, then zoom in so the viewport is a sub-region.
		for (int i = 0; i < 30; i++) {
			Thread.sleep(30);
			SwingUtilities.invokeAndWait(() -> { });
		}
		canvas.zoomIn();
		canvas.zoomIn();
		canvas.zoomIn();
		SwingUtilities.invokeAndWait(() -> { });

		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		canvas.paint(g);
		g.dispose();

		File dir = new File("build");
		dir.mkdirs();
		File out = new File(dir, "minimap.png");
		ImageIO.write(img, "png", out);
		assertTrue(out.length() > 0, "wrote " + out);
	}
}
