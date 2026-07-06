package io.github.wesleym.mappa.internal.render;

import io.github.wesleym.mappa.Mappa;
import io.github.wesleym.mappa.MappaMap;
import io.github.wesleym.mappa.MappaTheme;
import io.github.wesleym.mappa.internal.layout.EdgeRouter;
import io.github.wesleym.mappa.internal.layout.EdgeStyle;
import io.github.wesleym.mappa.internal.layout.JoinPath;
import io.github.wesleym.mappa.internal.layout.LabelLayout;
import io.github.wesleym.mappa.internal.layout.LayoutStyle;
import io.github.wesleym.mappa.internal.model.EntityBox;
import io.github.wesleym.mappa.internal.model.Scene;
import io.github.wesleym.mappa.internal.model.SceneBuilder;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneRendererTest {

	private static final Font TITLE = new Font(Font.SANS_SERIF, Font.BOLD, 13);
	private static final Font ROW = new Font(Font.MONOSPACED, Font.PLAIN, 12);
	private static final BufferedImage PROBE = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
	private static final SceneBuilder.TextWidth WIDTH =
			(t, f) -> PROBE.createGraphics().getFontMetrics(f).stringWidth(t);

	private static MappaMap commerce() {
		return Mappa.schema("Commerce")
				.table("customers", t -> t.primaryKey("id", "uuid").column("email", "text"))
				.table("categories", t -> t.primaryKey("id", "int").reference("parent_id", "int", "categories", "id"))
				.table("orders", t -> t
						.primaryKey("id", "uuid")
						.reference("customer_id", "uuid", "customers", "id")
						.reference("status_id", "int", "order_status", "id")
						.column("total", "decimal"))
				.table("order_status", t -> t.primaryKey("id", "int").column("label", "text"))
				.view("revenue_by_day", t -> t.column("day", "date").column("gross", "decimal"))
				.reference("revenue_by_day", "day", "orders", "id", true)
				.build();
	}

	// Two disconnected chains → two clusters, so the region panels (drawn only for >=2 groups) render.
	private static MappaMap twoClusters() {
		return Mappa.schema("Two")
				.table("a", t -> t.primaryKey("id", "int").reference("b_id", "int", "b", "id"))
				.table("b", t -> t.primaryKey("id", "int"))
				.table("c", t -> t.primaryKey("id", "int").reference("d_id", "int", "d", "id"))
				.table("d", t -> t.primaryKey("id", "int"))
				.build();
	}

	private static Scene scene(MappaMap map, boolean keysOnly) {
		return SceneBuilder.build(map, TITLE, ROW, WIDTH, true, false, keysOnly, LayoutStyle.LAYERED);
	}

	private record Prepared(Scene scene, List<EdgeRouter.EdgeGeometry> geometry, List<Path2D> paths,
			Map<Integer, LabelLayout.Placed> labels) { }

	private static Prepared prepare(Scene scene) {
		List<EdgeRouter.EdgeGeometry> geometry = EdgeRouter.route(scene);
		List<Path2D> paths = new ArrayList<>();
		for (EdgeRouter.EdgeGeometry g : geometry) {
			paths.add(EdgeStyle.CURVED.path(g.waypoints(), g.startHorizontal(), g.endHorizontal()));
		}
		Map<Integer, LabelLayout.Placed> labels =
				LabelLayout.layout(scene, paths, PROBE.createGraphics().getFontMetrics(ROW), Map.of());
		return new Prepared(scene, geometry, paths, labels);
	}

	private static Graphics2D canvas() {
		BufferedImage img = new BufferedImage(1400, 1000, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.translate(80, 80);
		return g;
	}

	@Test
	void drawsEveryBoxAndEdgeKindInBothThemes() {
		for (MappaTheme theme : List.of(MappaTheme.light(), MappaTheme.dark())) {
			for (boolean keysOnly : new boolean[] { false, true }) {
				Prepared p = prepare(scene(commerce(), keysOnly));
				SceneRenderer renderer = new SceneRenderer(theme, TITLE, ROW);
				assertDoesNotThrow(() -> renderer.draw(canvas(), p.scene(), p.geometry(), p.paths(), p.labels(),
						null, null, -1, true, null));
			}
		}
	}

	// The low-geometry placeholder pass: box bodies and header strips only, no text or columns. Renders a
	// PNG (full detail beside the silhouettes) for a look, and asserts the pass draws structure.
	@Test
	void drawsLowGeometrySilhouettes() throws Exception {
		MappaTheme theme = MappaTheme.light();
		Prepared p = prepare(scene(commerce(), false));
		Rectangle2D world = p.scene().worldBounds();
		int w = 1300;
		int h = (int) (world.getHeight() + 160);
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(theme.background());
		g.fillRect(0, 0, w, h);
		SceneRenderer renderer = new SceneRenderer(theme, TITLE, ROW);

		Graphics2D left = (Graphics2D) g.create();
		left.translate(60 - world.getX(), 80 - world.getY());
		renderer.draw(left, p.scene(), p.geometry(), p.paths(), p.labels(), null, null, -1, false, null);
		left.dispose();

		Graphics2D right = (Graphics2D) g.create();
		right.translate(60 - world.getX() + world.getWidth() + 120, 80 - world.getY());
		assertDoesNotThrow(() -> renderer.drawSilhouettes(right, p.scene(), null));
		right.dispose();
		g.dispose();

		java.io.File out = new java.io.File("build");
		out.mkdirs();
		ImageIO.write(img, "png", new java.io.File(out, "silhouettes.png"));

		// A clip that excludes every box yields an empty pass — a pre-filled pixel stays untouched.
		int sentinel = 0xFF00FF00;
		BufferedImage blank = new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB);
		Graphics2D bg = blank.createGraphics();
		bg.setColor(new Color(sentinel));
		bg.fillRect(0, 0, 50, 50);
		renderer.drawSilhouettes(bg, p.scene(), new Rectangle2D.Double(-9999, -9999, 1, 1));
		bg.dispose();
		assertEquals(sentinel, blank.getRGB(25, 25), "a non-intersecting clip draws nothing");
	}

	@Test
	void directionalAndLightweightPassesRender() {
		Prepared p = prepare(scene(commerce(), false));
		SceneRenderer directional = new SceneRenderer(MappaTheme.dark(), TITLE, ROW);
		directional.setDirectionalEdges(true);
		assertDoesNotThrow(() -> directional.draw(canvas(), p.scene(), p.geometry(), p.paths(), p.labels(),
				null, null, -1, false, null));

		SceneRenderer light = new SceneRenderer(MappaTheme.light(), TITLE, ROW);
		light.setLightweight(true);
		assertDoesNotThrow(() -> light.draw(canvas(), p.scene(), p.geometry(), p.paths(), p.labels(),
				null, null, -1, true, null));
	}

	@Test
	void spotlightFocusAndAnimatedOverlaysRender() {
		Prepared p = prepare(scene(commerce(), false));
		EntityBox active = p.scene().tables().stream().filter(t -> t.name().equals("orders")).findFirst().orElseThrow();
		SceneRenderer renderer = new SceneRenderer(MappaTheme.light(), TITLE, ROW);

		List<double[][]> flats = new ArrayList<>();
		for (Path2D path : p.paths()) {
			flats.add(SceneRenderer.flatten(path));
		}
		List<Integer> pathEdges = JoinPath.shortest(p.scene().edges(), 0, p.scene().tables().size() - 1);

		assertDoesNotThrow(() -> {
			renderer.draw(canvas(), p.scene(), p.geometry(), p.paths(), p.labels(), active, "orders", -1, true, null);
			renderer.drawFlow(canvas(), p.scene(), flats, active, 42.0, 1.0);
			renderer.drawDragged(canvas(), p.scene(), p.geometry(), p.paths(), 0, "orders");
			renderer.drawJoinLabelsOver(canvas(), p.scene(), p.labels(), List.of(0));
			if (!pathEdges.isEmpty()) {
				renderer.drawPath(canvas(), p.scene(), p.paths(), flats, pathEdges, 0,
						p.scene().tables().size() - 1, 42.0, 1.0);
			}
		});
	}

	// A chain of n entities — big enough (>=60) that a low-zoom render trips the level-of-detail tiers.
	private static MappaMap many(int n) {
		var b = Mappa.schema("Many");
		for (int i = 0; i < n; i++) {
			int idx = i;
			b.table("t" + i, t -> {
				t.primaryKey("id", "int");
				if (idx > 0) {
					t.reference("p_id", "int", "t" + (idx - 1), "id");
				}
			});
		}
		return b.build();
	}

	@Test
	void levelOfDetailTiersRenderOnALargeSceneZoomedOut() {
		Prepared p = prepare(scene(many(70), true));
		SceneRenderer renderer = new SceneRenderer(MappaTheme.light(), TITLE, ROW);
		for (double scale : new double[] { 0.15, 0.32, 0.45, 0.7 }) {   // walk down through the LOD thresholds
			BufferedImage img = new BufferedImage(1600, 1200, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = img.createGraphics();
			g.scale(scale, scale);
			assertDoesNotThrow(() -> renderer.draw(g, p.scene(), p.geometry(), p.paths(), p.labels(),
					null, null, -1, false, null));
			g.dispose();
		}
	}

	@Test
	void cappedTallBoxDrawsAScrollThumbAndEllipsisedName() {
		var b = Mappa.schema("Wide");
		b.table("an_unusually_long_entity_name_that_must_be_ellipsised_in_the_header", t -> {
			t.primaryKey("id", "int");
			for (int i = 0; i < 40; i++) {
				t.column("column_number_" + i, "varchar");
			}
		});
		b.table("other", t -> t.primaryKey("id", "int"));
		b.reference("an_unusually_long_entity_name_that_must_be_ellipsised_in_the_header", "id", "other", "id");
		Scene capped = SceneBuilder.build(b.build(), TITLE, ROW, WIDTH, false, true, false, LayoutStyle.LAYERED);
		Prepared p = prepare(capped);
		SceneRenderer renderer = new SceneRenderer(MappaTheme.dark(), TITLE, ROW);
		renderer.setDirectionalEdges(true);
		EntityBox active = capped.tables().get(0);
		assertDoesNotThrow(() -> renderer.draw(canvas(), p.scene(), p.geometry(), p.paths(), p.labels(),
				active, active.name(), -1, true, null));
	}

	// Smoke test of the full spotlight render path — the active box and its neighbours lit, everything else
	// dimmed, flow comets and bright join labels over its relationships — exercising drawFlow and
	// drawJoinLabelsOver together. (The README's spotlight image is the vector one from DocImagesTest.)
	@Test
	void rendersTheSpotlightPath() throws Exception {
		MappaTheme theme = MappaTheme.light();
		Prepared p = prepare(scene(commerce(), false));
		EntityBox active = p.scene().tables().stream()
				.filter(t -> t.name().equals("orders")).findFirst().orElseThrow();
		int activeIndex = p.scene().tables().indexOf(active);
		List<Integer> activeEdges = new ArrayList<>();
		for (int i = 0; i < p.scene().edges().size(); i++) {
			if (p.scene().edges().get(i).from() == activeIndex || p.scene().edges().get(i).to() == activeIndex) {
				activeEdges.add(i);
			}
		}
		List<double[][]> flats = new ArrayList<>();
		for (Path2D path : p.paths()) {
			flats.add(SceneRenderer.flatten(path));
		}

		int w = 1120;
		int h = 720;
		int margin = 64;
		Rectangle2D world = p.scene().worldBounds();
		double zoom = Math.min(1.0, Math.min((w - 2.0 * margin) / world.getWidth(),
				(h - 2.0 * margin) / world.getHeight()));
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setColor(theme.background());
		g.fillRect(0, 0, w, h);
		g.translate((w - world.getWidth() * zoom) / 2, (h - world.getHeight() * zoom) / 2);
		g.scale(zoom, zoom);
		g.translate(-world.getX(), -world.getY());

		SceneRenderer renderer = new SceneRenderer(theme, TITLE, ROW);
		renderer.draw(g, p.scene(), p.geometry(), p.paths(), p.labels(), active, "orders", -1, true, null);
		renderer.drawFlow(g, p.scene(), flats, active, 60.0, zoom);
		renderer.drawJoinLabelsOver(g, p.scene(), p.labels(), activeEdges);
		g.dispose();

		File dir = new File("build");
		dir.mkdirs();
		ImageIO.write(img, "png", new File(dir, "spotlight-smoke.png"));
	}

	@Test
	void communityRegionPanelsAndLabelsRender() {
		Scene scene = scene(twoClusters(), false);
		assertTrue(scene.tables().stream().mapToInt(EntityBox::clusterId).distinct().count() >= 2,
				"the disconnected map yields at least two clusters");
		Prepared p = prepare(scene);
		SceneRenderer renderer = new SceneRenderer(MappaTheme.light(), TITLE, ROW);
		renderer.setClusterNames(Map.of(0, "Alpha", 1, "Beta"));
		assertDoesNotThrow(() -> renderer.draw(canvas(), p.scene(), p.geometry(), p.paths(), p.labels(),
				null, null, -1, false, null));
	}
}
