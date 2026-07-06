package io.github.wesleym.mappa.internal.render;

import io.github.wesleym.mappa.Mappa;
import io.github.wesleym.mappa.MappaEdges;
import io.github.wesleym.mappa.MappaLayout;
import io.github.wesleym.mappa.MappaMap;
import io.github.wesleym.mappa.MappaTheme;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.geom.Rectangle2D;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Generates the README's vector documentation images (all SVG). Diagrams rendered by the real engine. */
class DocImagesTest {

	private static final MappaTheme PAPER = MappaTheme.light();
	private static final MappaTheme INKWELL = MappaTheme.dark();
	private static final MappaTheme ATLAS = MappaTheme.light()
			.background(new Color(0xEDE1CC)).surface(new Color(0xF2E8D5)).text(new Color(0x3B2F22))
			.line(new Color(0xDFD2B8)).accent(new Color(0xBF6F33)).entityHeader(new Color(0x28568E))
			.viewHeader(new Color(0x007F68)).suggestedReference(new Color(0xBD9A32));

	private static void write(String name, String svg) throws Exception {
		Path dir = Path.of("build");
		Files.createDirectories(dir);
		Path out = dir.resolve(name);
		Files.writeString(out, svg, StandardCharsets.UTF_8);
		assertTrue(Files.size(out) > 0 && svg.startsWith("<svg") && svg.contains("</svg>"), "wrote " + out);
	}

	private static final int HW = 900;
	private static final int HH = 540;
	private static final double ASPECT = HW / (double) HH;

	// The animated hero: a simulated session on a readable schema — the camera flies in, "clicks" a table,
	// its neighbourhood spotlights (rest dimmed, neighbours and flow lit), then moves on. Loops.
	@Test
	void heroTour() throws Exception {
		MappaMap map = commerce();
		DocSvg.Rendered neutral = DocSvg.render(map, MappaLayout.LAYERED, MappaEdges.CURVED, false, false, PAPER, null);
		String[] targets = { "orders", "customers", "products", "reviews" };

		List<DocSvg.SpotLayer> spots = new ArrayList<>();
		// Each spotlight fades in as the camera settles on its table, holds, then fades out before the camera leaves.
		String[] windows = {
				"0;0.13;0.16;0.21;0.235;1", "0;0.29;0.32;0.37;0.395;1",
				"0;0.45;0.48;0.53;0.555;1", "0;0.61;0.64;0.69;0.715;1" };
		for (int i = 0; i < targets.length; i++) {
			DocSvg.Rendered lit = DocSvg.render(map, MappaLayout.LAYERED, MappaEdges.CURVED, false, true, PAPER,
					targets[i]);
			spots.add(new DocSvg.SpotLayer(lit, windows[i], "0;0;1;1;0;0"));
		}

		Map<String, Rectangle2D> boxes = neutral.boxes();
		Rectangle2D world = neutral.world();
		List<String> cam = List.of(
				wide(world), wide(world),
				on(boxes.get("orders"), 0.5), on(boxes.get("orders"), 0.5),
				on(boxes.get("customers"), 0.46), on(boxes.get("customers"), 0.46),
				on(boxes.get("products"), 0.48), on(boxes.get("products"), 0.48),
				on(boxes.get("reviews"), 0.46), on(boxes.get("reviews"), 0.46),
				wide(world), wide(world));
		String camKeyTimes = "0;0.06;0.12;0.22;0.28;0.38;0.44;0.54;0.60;0.70;0.78;1";
		String camSplines = String.join(";", Collections.nCopies(11, "0.42 0 0.58 1"));

		write("hero.svg", DocSvg.spotlightTour(neutral, spots, PAPER, cam, camKeyTimes, camSplines, world, HW, HH, 26));
	}

	// A viewBox rectangle centred on a box, sized so the box fills roughly `fill` of the frame height.
	private static String on(Rectangle2D b, double fill) {
		double vh = b.getHeight() / fill;
		double vw = vh * ASPECT;
		if (vw < b.getWidth() / fill) {
			vw = b.getWidth() / fill;
			vh = vw / ASPECT;
		}
		return box(b.getCenterX(), b.getCenterY(), vw, vh);
	}

	private static String wide(Rectangle2D world) {
		double vw = world.getWidth() * 1.12;
		double vh = vw / ASPECT;
		if (vh < world.getHeight() * 1.12) {
			vh = world.getHeight() * 1.12;
			vw = vh * ASPECT;
		}
		return box(world.getCenterX(), world.getCenterY(), vw, vh);
	}

	private static String box(double cx, double cy, double vw, double vh) {
		return num(cx - vw / 2) + " " + num(cy - vh / 2) + " " + num(vw) + " " + num(vh);
	}

	private static String num(double v) {
		return v == Math.rint(v) ? Long.toString((long) v) : String.format(java.util.Locale.ROOT, "%.1f", v);
	}

	@Test
	void layouts() throws Exception {
		MappaMap map = commerce();
		List<DocSvg.Rendered> tiles = List.of(
				DocSvg.render(map, MappaLayout.LAYERED, MappaEdges.CURVED, true, false, PAPER, null),
				DocSvg.render(map, MappaLayout.RADIAL, MappaEdges.CURVED, true, false, PAPER, null),
				DocSvg.render(map, MappaLayout.FORCE, MappaEdges.CURVED, true, false, PAPER, null),
				DocSvg.render(map, MappaLayout.GRID, MappaEdges.CURVED, true, false, PAPER, null));
		write("showcase-layouts.svg", DocSvg.tiles(tiles,
				List.of("LAYERED — the default for a schema", "RADIAL — a hub and its satellites",
						"FORCE — organic clusters", "GRID — tidy rows"),
				List.of(PAPER, PAPER, PAPER, PAPER), 2, 560, 380, new Color(0x60, 0x63, 0x6A)));
	}

	@Test
	void themes() throws Exception {
		MappaMap map = store();
		List<DocSvg.Rendered> tiles = List.of(
				DocSvg.render(map, MappaLayout.LAYERED, MappaEdges.CURVED, false, false, PAPER, null),
				DocSvg.render(map, MappaLayout.LAYERED, MappaEdges.CURVED, false, false, INKWELL, null),
				DocSvg.render(map, MappaLayout.LAYERED, MappaEdges.CURVED, false, false, ATLAS, null));
		write("showcase-themes.svg", DocSvg.tiles(tiles,
				List.of("Light — the default", "Dark", "Atlas — custom"),
				List.of(PAPER, INKWELL, ATLAS), 2, 560, 380, new Color(0x2A, 0x2C, 0x33)));
	}

	@Test
	void spotlight() throws Exception {
		DocSvg.Rendered r = DocSvg.render(commerce(), MappaLayout.LAYERED, MappaEdges.CURVED, false, true,
				PAPER, "orders");
		write("showcase-spotlight.svg", DocSvg.standalone(r, PAPER, 900, 620));
	}

	// ---- schemas -------------------------------------------------------------------------------------

	private static MappaMap store() {
		return Mappa.schema("Store")
				.table("orders", t -> t.primaryKey("id", "uuid").reference("customer_id", "uuid", "customers", "id")
						.reference("status_id", "int", "order_status", "id").column("total", "decimal"))
				.table("customers", t -> t.primaryKey("id", "uuid").column("email", "text"))
				.table("order_status", t -> t.primaryKey("id", "int").column("name", "text"))
				.build();
	}

	private static MappaMap commerce() {
		return Mappa.schema("Commerce")
				.table("customers", t -> t.primaryKey("id", "uuid").column("email", "text")
						.column("full_name", "text").column("created_at", "timestamp"))
				.table("addresses", t -> t.primaryKey("id", "uuid").reference("customer_id", "uuid", "customers", "id")
						.column("city", "text").column("country", "text"))
				.table("categories", t -> t.primaryKey("id", "int").reference("parent_id", "int", "categories", "id")
						.column("name", "text"))
				.table("products", t -> t.primaryKey("id", "uuid").reference("category_id", "int", "categories", "id")
						.column("sku", "text").column("name", "text").column("price", "decimal"))
				.table("orders", t -> t.primaryKey("id", "uuid").reference("customer_id", "uuid", "customers", "id")
						.reference("address_id", "uuid", "addresses", "id").column("status", "text")
						.column("total", "decimal").column("placed_at", "timestamp"))
				.table("order_items", t -> t.primaryKey("id", "uuid").reference("order_id", "uuid", "orders", "id")
						.reference("product_id", "uuid", "products", "id").column("quantity", "int")
						.column("unit_price", "decimal"))
				.table("payments", t -> t.primaryKey("id", "uuid").reference("order_id", "uuid", "orders", "id")
						.column("method", "text").column("amount", "decimal"))
				.table("shipments", t -> t.primaryKey("id", "uuid").reference("order_id", "uuid", "orders", "id")
						.column("carrier", "text").column("shipped_at", "timestamp"))
				.table("reviews", t -> t.primaryKey("id", "uuid").reference("product_id", "uuid", "products", "id")
						.reference("customer_id", "uuid", "customers", "id").column("rating", "int"))
				.view("revenue_by_day", t -> t.column("day", "date").column("gross", "decimal"))
				.reference("revenue_by_day", "day", "orders", "placed_at", true)
				.build();
	}

}
