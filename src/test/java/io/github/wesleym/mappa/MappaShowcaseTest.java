package io.github.wesleym.mappa;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gallery pieces the README leads with — a full-bleed hero, the same schema across the four layouts,
 * and across the theme family — each composed headless by Mappa itself, so the showcase is also a test of
 * {@link MappaView#renderTo} onto arbitrary graphics. Writes {@code build/showcase-*.png}.
 */
class MappaShowcaseTest {

	// A custom theme, defined exactly as the README shows it: an old atlas — aged chart-paper tan, a
	// cartographer's navy for the headers, sienna keys, and hand-coloured teal and gold inks.
	static final MappaTheme ATLAS = MappaTheme.light()
			.background(new Color(0xED, 0xE1, 0xCC))
			.surface(new Color(0xF2, 0xE8, 0xD5))
			.text(new Color(0x3B, 0x2F, 0x22))
			.muted(new Color(0x8F, 0x7F, 0x66))
			.line(new Color(0xDF, 0xD2, 0xB8))
			.accent(new Color(0xBF, 0x6F, 0x33))
			.entityHeader(new Color(0x28, 0x56, 0x8E))
			.viewHeader(new Color(0x00, 0x7F, 0x68))
			.reference(new Color(0x28, 0x56, 0x8E))
			.suggestedReference(new Color(0xBD, 0x9A, 0x32));

	@Test
	void hero() throws Exception {
		BufferedImage image = Mappa.view(Fixtures.commerce())
				.layout(MappaLayout.LAYERED)
				.edges(MappaEdges.CURVED)
				.detail(MappaDetail.ALL_FIELDS)
				.background(MappaBackground.DOTS)
				.theme(ATLAS)
				.image(1280, 760);
		write("showcase-hero", image);
	}

	@Test
	void fourLayoutsOneSchema() throws Exception {
		record Named(String name, MappaLayout layout) { }
		Named[] tiles = {
				new Named("LAYERED — the default for a schema", MappaLayout.LAYERED),
				new Named("RADIAL — a hub and its satellites", MappaLayout.RADIAL),
				new Named("FORCE — organic clusters", MappaLayout.FORCE),
				new Named("GRID — tidy rows", MappaLayout.GRID) };
		compose("showcase-layouts", tiles.length, (i, w, h) -> Mappa.view(Fixtures.commerce())
				.layout(tiles[i].layout())
				.detail(MappaDetail.KEYS)
				.theme(MappaTheme.light())
				.image(w, h), i -> tiles[i].name(), new Color(0x60, 0x63, 0x6A));
	}

	@Test
	void themeFamily() throws Exception {
		record Named(String name, MappaTheme theme) { }
		Named[] tiles = {
				new Named("Light — the default", MappaTheme.light()),
				new Named("Dark", MappaTheme.dark()),
				new Named("Atlas — custom", ATLAS) };
		compose("showcase-themes", tiles.length, (i, w, h) -> Mappa.view(Fixtures.store())
				.layout(MappaLayout.LAYERED)
				.theme(tiles[i].theme())
				.image(w, h), i -> tiles[i].name(), new Color(0x2A, 0x2C, 0x33));
	}

	// ---- tiling helper -------------------------------------------------------------------------------

	private interface TileRenderer {
		BufferedImage render(int index, int width, int height);
	}

	private interface TileLabel {
		String of(int index);
	}

	private static void compose(String name, int count, TileRenderer renderer, TileLabel label, Color mat)
			throws Exception {
		int cols = count <= 2 ? count : 2;
		int rows = (count + cols - 1) / cols;
		int tileW = 640;
		int tileH = 440;
		int gap = 16;
		int label_h = 30;
		int w = cols * tileW + (cols + 1) * gap;
		int h = rows * (tileH + label_h) + (rows + 1) * gap;

		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setColor(mat);
		g.fillRect(0, 0, w, h);
		for (int i = 0; i < count; i++) {
			int col = i % cols;
			int row = i / cols;
			int x = gap + col * (tileW + gap);
			int y = gap + row * (tileH + label_h + gap);
			g.setColor(new Color(0xFF, 0xFF, 0xFF, 210));
			g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
			g.drawString(label.of(i), x + 4, y + 20);
			Graphics2D tg = (Graphics2D) g.create();
			tg.translate(x, y + label_h);
			tg.setClip(new RoundRectangle2D.Double(0, 0, tileW, tileH, 18, 18));
			tg.drawImage(renderer.render(i, tileW, tileH), 0, 0, null);
			tg.dispose();
		}
		g.dispose();
		write(name, img);
	}

	static void write(String name, BufferedImage image) throws Exception {
		File dir = new File("build");
		dir.mkdirs();
		File out = new File(dir, name + ".png");
		ImageIO.write(image, "png", out);
		assertTrue(out.length() > 0, "wrote " + out);
	}
}
