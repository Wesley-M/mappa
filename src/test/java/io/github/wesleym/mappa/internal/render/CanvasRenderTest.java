package io.github.wesleym.mappa.internal.render;

import io.github.wesleym.mappa.Mappa;
import io.github.wesleym.mappa.MappaMap;
import io.github.wesleym.mappa.MappaTheme;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the canvas's own synchronous render pipeline (buildExportContent + SceneRenderer.draw via
 * {@link MappaCanvas#renderImage}) — proving the interactive component renders the real diagram, not only
 * the static {@link StaticRender} path.
 */
class CanvasRenderTest {

	private static MappaMap store() {
		return Mappa.schema("Store")
				.table("orders", t -> t
						.primaryKey("id", "uuid")
						.reference("customer_id", "uuid", "customers", "id")
						.column("total", "decimal"))
				.table("customers", t -> t.primaryKey("id", "uuid").column("email", "text"))
				.build();
	}

	@Test
	void restoresStoredPositions() {
		MappaMap map = store().withPositions(Map.of(
				"orders", new MappaMap.Position(-100, 200),
				"customers", new MappaMap.Position(300, 50)));
		MappaCanvas canvas = new MappaCanvas(MappaTheme.light());
		canvas.setMap(map);

		MappaCanvas.ExportContent content = canvas.buildExportContent();
		var orders = content.scene().tables().stream()
				.filter(t -> t.name().equals("orders")).findFirst().orElseThrow();
		assertEquals(-100, orders.bounds().getCenterX(), 0.5, "box restored to its saved centre x");
		assertEquals(200, orders.bounds().getCenterY(), 0.5, "box restored to its saved centre y");
	}

	@Test
	void renderImageProducesTheDiagram() {
		MappaCanvas canvas = new MappaCanvas(MappaTheme.light());
		canvas.setShowJoinColumns(true);
		canvas.setMap(store());

		BufferedImage image = canvas.renderImage(false, 1.0);

		assertNotNull(image, "canvas rendered an image");
		assertTrue(nonBackground(image) > 500, "canvas drew visible diagram content");
	}

	private static int nonBackground(BufferedImage image) {
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
