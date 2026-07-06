package io.github.wesleym.mappa.internal.render;

import io.github.wesleym.mappa.Mappa;
import io.github.wesleym.mappa.MappaMap;
import io.github.wesleym.mappa.MappaTheme;
import io.github.wesleym.mappa.internal.layout.EdgeRouter;
import io.github.wesleym.mappa.internal.layout.EdgeStyle;
import io.github.wesleym.mappa.internal.layout.LayoutStyle;
import io.github.wesleym.mappa.internal.model.Scene;
import io.github.wesleym.mappa.internal.model.SceneBuilder;

import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Not a CI gate — a timing harness (prints to stdout) for the five-hundred-table stress case, to confirm it
 * stays usable: layout, a whole-scene raster (the zoomed-out fit view, where level-of-detail simplifies each
 * box), and a viewport-culled raster (the zoomed-in interactive bake). The live canvas rasterises in parallel
 * bands, so these single-threaded numbers are an upper bound.
 */
class RenderBenchmarkTest {

	private static final Font TITLE = new Font(Font.SANS_SERIF, Font.BOLD, 13);
	private static final Font ROW = new Font(Font.MONOSPACED, Font.PLAIN, 12);
	private static final BufferedImage PROBE = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
	private static final SceneBuilder.TextWidth WIDTH =
			(t, f) -> PROBE.createGraphics().getFontMetrics(f).stringWidth(t);

	private static long millis(Supplier<?> work) {
		work.get();   // warm
		long start = System.nanoTime();
		work.get();
		return (System.nanoTime() - start) / 1_000_000;
	}

	@Test
	void fiveHundredTablesStayUsable() {
		MappaMap map = platform();
		long layout = millis(() -> SceneBuilder.build(map, TITLE, ROW, WIDTH, false, false, true, LayoutStyle.LAYERED));
		Scene scene = SceneBuilder.build(map, TITLE, ROW, WIDTH, false, false, true, LayoutStyle.LAYERED);
		assertTrue(scene.tables().size() >= 500, "500+ tables");

		List<EdgeRouter.EdgeGeometry> geometry = EdgeRouter.route(scene);
		List<Path2D> paths = new ArrayList<>();
		for (EdgeRouter.EdgeGeometry g : geometry) {
			paths.add(EdgeStyle.CURVED.path(g.waypoints(), g.startHorizontal(), g.endHorizontal()));
		}
		SceneRenderer renderer = new SceneRenderer(MappaTheme.light(), TITLE, ROW);
		Rectangle2D world = scene.worldBounds();

		// Fit view: the whole schema scaled into a 1400×900 frame — level-of-detail simplifies every box.
		long fit = millis(() -> renderInto(renderer, scene, geometry, paths, world, 1400, 900,
				Math.min(1400 / world.getWidth(), 900 / world.getHeight()), true));
		// Interactive bake zoomed in: a 1400×900 viewport at 1:1 over one corner — culling drops the rest.
		long culled = millis(() -> renderInto(renderer, scene, geometry, paths,
				new Rectangle2D.Double(world.getX(), world.getY(), 1400, 900), 1400, 900, 1.0, true));

		System.out.printf("%n[500 tables] layout=%dms  fit-raster(LOD, 1 thread)=%dms  culled-bake=%dms%n",
				layout, fit, culled);
		assertTrue(layout < 500 && fit < 1500, "500-table layout and a fit raster complete promptly");
	}

	private static Object renderInto(SceneRenderer renderer, Scene scene, List<EdgeRouter.EdgeGeometry> geometry,
			List<Path2D> paths, Rectangle2D view, int w, int h, double zoom, boolean cull) {
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.scale(zoom, zoom);
		g.translate(-view.getX(), -view.getY());
		renderer.draw(g, scene, geometry, paths, Map.of(), null, null, -1, false,
				cull ? new Rectangle2D.Double(view.getX(), view.getY(), view.getWidth() / zoom, view.getHeight() / zoom)
						: null);
		g.dispose();
		return img;
	}

	private static final String[] NOUNS = { "customer", "product", "order", "payment", "shipment", "invoice",
			"ticket", "review", "campaign", "warehouse", "vendor", "contract", "employee", "account",
			"subscription", "device", "location", "project", "task", "message", "document", "asset", "policy",
			"claim", "booking", "session", "transaction", "notification" };
	private static final String[] SATELLITES = { "item", "status", "note", "event", "tag", "log", "attachment",
			"assignment", "history" };

	private static MappaMap platform() {
		var b = Mappa.schema("Platform");
		for (int d = 0; d < 50; d++) {
			String base = dom(d);
			int p = (d - 1) / 2;
			String prev = d == 0 ? null : dom(p);
			b.table(base, t -> {
				t.primaryKey("id", "uuid").column("name", "text").column("code", "text").column("status", "text")
						.column("amount", "decimal").column("active", "boolean").column("created_at", "timestamp");
				if (prev != null) {
					t.reference(prev + "_id", "uuid", prev, "id");
				}
			});
			for (String s : SATELLITES) {
				b.table(base + "_" + s, t -> t.primaryKey("id", "uuid").reference(base + "_id", "uuid", base, "id")
						.column("label", "text").column("value", "decimal").column("created_at", "timestamp"));
			}
		}
		return b.build();
	}

	private static String dom(int d) {
		return NOUNS[d % NOUNS.length] + (d >= NOUNS.length ? "_" + (d / NOUNS.length + 1) : "");
	}
}
