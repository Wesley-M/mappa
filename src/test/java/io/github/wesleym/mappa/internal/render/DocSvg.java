package io.github.wesleym.mappa.internal.render;

import io.github.wesleym.mappa.MappaEdges;
import io.github.wesleym.mappa.MappaLayout;
import io.github.wesleym.mappa.MappaMap;
import io.github.wesleym.mappa.MappaTheme;
import io.github.wesleym.mappa.internal.layout.EdgeRouter;
import io.github.wesleym.mappa.internal.layout.EdgeStyle;
import io.github.wesleym.mappa.internal.layout.LabelLayout;
import io.github.wesleym.mappa.internal.layout.LayoutStyle;
import io.github.wesleym.mappa.internal.model.EntityBox;
import io.github.wesleym.mappa.internal.model.Scene;
import io.github.wesleym.mappa.internal.model.SceneBuilder;

import java.awt.Color;
import java.awt.Font;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the README's documentation images as crisp, compact vector SVG (text emitted as real {@code <text>},
 * not outlines): a single diagram, a grid of tiles, or a cross-fade reel that morphs one schema across layouts
 * and themes. Test-only — the diagrams are rendered by the real engine.
 */
final class DocSvg {

	static final Font TITLE = new Font(Font.SANS_SERIF, Font.BOLD, 13);
	static final Font ROW = new Font(Font.MONOSPACED, Font.PLAIN, 12);
	private static final BufferedImage PROBE = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
	private static final SceneBuilder.TextWidth WIDTH =
			(t, f) -> PROBE.createGraphics().getFontMetrics(f).stringWidth(t);

	private DocSvg() { }

	/** A rendered diagram: its vector content (world coordinates), the bounds it occupies, and each box's
	 *  bounds by name (for aiming a camera). */
	record Rendered(String content, Rectangle2D world, Map<String, Rectangle2D> boxes) { }

	static Rendered render(MappaMap map, MappaLayout layout, MappaEdges edges, boolean keysOnly, boolean labels,
			MappaTheme theme, String active) {
		EdgeStyle edgeStyle = edgeStyle(edges);
		Scene scene = SceneBuilder.build(map, TITLE, ROW, WIDTH, labels, false, keysOnly, layoutStyle(layout));
		List<EdgeRouter.EdgeGeometry> geometry = EdgeRouter.route(scene);
		List<Path2D> paths = new ArrayList<>();
		List<double[][]> flats = new ArrayList<>();
		for (EdgeRouter.EdgeGeometry g : geometry) {
			Path2D p = edgeStyle.path(g.waypoints(), g.startHorizontal(), g.endHorizontal());
			paths.add(p);
			flats.add(SceneRenderer.flatten(p));
		}
		Map<Integer, LabelLayout.Placed> labelRects = labels
				? LabelLayout.layout(scene, paths, PROBE.createGraphics().getFontMetrics(ROW), Map.of())
				: Map.of();

		SvgGraphics2D svg = new SvgGraphics2D(1, 1, true);
		SceneRenderer renderer = new SceneRenderer(theme, TITLE, ROW);
		renderer.setDirectionalEdges(edgeStyle == EdgeStyle.DIRECTIONAL);
		renderer.setClusterNames(StaticRender.clusterNames(scene));
		EntityBox box = active == null ? null
				: scene.tables().stream().filter(t -> t.name().equals(active)).findFirst().orElse(null);
		renderer.draw(svg, scene, geometry, paths, labelRects, box, active, -1, labels, null);
		if (box != null) {
			renderer.drawFlow(svg, scene, flats, box, 34.0, 1.0, null, null);   // a frozen flow, so the spotlight reads as live
		}
		Map<String, Rectangle2D> boxes = new java.util.LinkedHashMap<>();
		for (EntityBox t : scene.tables()) {
			boxes.put(t.name(), t.bounds());
		}
		return new Rendered(svg.content(), scene.worldBounds(), boxes);
	}

	/** A standalone fit-to-frame SVG of one diagram. */
	static String standalone(Rendered r, MappaTheme theme, int w, int h) {
		StringBuilder sb = new StringBuilder();
		open(sb, w, h);
		sb.append("<rect width=\"").append(w).append("\" height=\"").append(h).append("\" fill=\"")
				.append(hex(theme.background())).append("\"/>\n");
		fitted(sb, r, 0, 0, w, h, 40);
		return sb.append("</svg>\n").toString();
	}

	/** A grid of tiles — same or different schemas/themes — each fit into its cell with a label. */
	static String tiles(List<Rendered> rendered, List<String> labels, List<MappaTheme> themes, int cols,
			int tileW, int tileH, Color mat) {
		int rows = (rendered.size() + cols - 1) / cols;
		int gap = 16;
		int labelH = 30;
		int w = cols * tileW + (cols + 1) * gap;
		int h = rows * (tileH + labelH) + (rows + 1) * gap;
		StringBuilder sb = new StringBuilder();
		open(sb, w, h);
		sb.append("<rect width=\"").append(w).append("\" height=\"").append(h).append("\" fill=\"")
				.append(hex(mat)).append("\"/>\n");
		for (int i = 0; i < rendered.size(); i++) {
			int x = gap + (i % cols) * (tileW + gap);
			int y = gap + (i / cols) * (tileH + labelH + gap);
			sb.append("<text x=\"").append(x + 4).append("\" y=\"").append(y + 19)
					.append("\" font-family=\"sans-serif\" font-size=\"14\" font-weight=\"bold\" fill=\"#FFFFFF\" ")
					.append("fill-opacity=\"0.82\">").append(escape(labels.get(i))).append("</text>\n");
			sb.append("<g clip-path=\"inset(0 round 14px)\">");   // (harmless if unsupported; tiles round via bg)
			sb.append("<rect x=\"").append(x).append("\" y=\"").append(y + labelH).append("\" width=\"").append(tileW)
					.append("\" height=\"").append(tileH).append("\" rx=\"14\" fill=\"")
					.append(hex(themes.get(i).background())).append("\"/>\n");
			fitted(sb, rendered.get(i), x, y + labelH, tileW, tileH, 26);
			sb.append("</g>\n");
		}
		return sb.append("</svg>\n").toString();
	}

	/** One spotlight overlay in a tour: the spotlit render plus the SMIL keyframes that fade it in and out. */
	record SpotLayer(Rendered render, String keyTimes, String values) { }

	/**
	 * A simulated interaction: the camera (an animated {@code viewBox}) flies over a diagram in world
	 * coordinates, and as it settles on a table, that table's spotlight render fades in over the neutral base
	 * — the rest dimming, its neighbours and flow lit — then fades back out as the camera moves on. Loops.
	 * Content is drawn in world coordinates, so the camera path is expressed directly as viewBox rectangles.
	 */
	static String spotlightTour(Rendered neutral, List<SpotLayer> spots, MappaTheme theme, List<String> camValues,
			String camKeyTimes, String camSplines, Rectangle2D world, int w, int h, int durSeconds) {
		double bx = world.getX() - world.getWidth();
		double by = world.getY() - world.getHeight();
		double bw = world.getWidth() * 3;
		double bh = world.getHeight() * 3;
		String bg = hex(theme.background());
		StringBuilder sb = new StringBuilder();
		sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(w).append("\" height=\"").append(h)
				.append("\" viewBox=\"").append(camValues.get(0))
				.append("\" preserveAspectRatio=\"xMidYMid slice\" role=\"img\">\n");
		sb.append("<animate attributeName=\"viewBox\" dur=\"").append(durSeconds)
				.append("s\" repeatCount=\"indefinite\" calcMode=\"spline\" keyTimes=\"").append(camKeyTimes)
				.append("\" keySplines=\"").append(camSplines).append("\" values=\"")
				.append(String.join(";", camValues)).append("\"/>\n");
		rect(sb, bx, by, bw, bh, bg);
		sb.append("<g>\n").append(neutral.content()).append("</g>\n");
		for (SpotLayer s : spots) {
			sb.append("<g opacity=\"0\"><animate attributeName=\"opacity\" dur=\"").append(durSeconds)
					.append("s\" repeatCount=\"indefinite\" calcMode=\"linear\" keyTimes=\"").append(s.keyTimes())
					.append("\" values=\"").append(s.values()).append("\"/>\n");
			rect(sb, bx, by, bw, bh, bg);   // the spotlight frame is opaque, so it fully covers the neutral base
			sb.append(s.render().content()).append("</g>\n");
		}
		return sb.append("</svg>\n").toString();
	}

	private static void rect(StringBuilder sb, double x, double y, double w, double h, String fill) {
		sb.append("<rect x=\"").append(num(x)).append("\" y=\"").append(num(y)).append("\" width=\"").append(num(w))
				.append("\" height=\"").append(num(h)).append("\" fill=\"").append(fill).append("\"/>\n");
	}

	// ---- internals -----------------------------------------------------------------------------------

	private static void open(StringBuilder sb, int w, int h) {
		sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(w).append("\" height=\"").append(h)
				.append("\" viewBox=\"0 0 ").append(w).append(' ').append(h).append("\" role=\"img\">\n");
	}

	// Place a rendered diagram, fit into a cell at (cellX,cellY) sized cellW×cellH with margin, centred.
	private static void fitted(StringBuilder sb, Rendered r, int cellX, int cellY, int cellW, int cellH,
			double margin) {
		Rectangle2D world = r.world();
		if (world == null) {
			return;
		}
		double s = Math.min((cellW - 2 * margin) / world.getWidth(), (cellH - 2 * margin) / world.getHeight());
		double tx = cellX + (cellW - world.getWidth() * s) / 2 - world.getX() * s;
		double ty = cellY + (cellH - world.getHeight() * s) / 2 - world.getY() * s;
		sb.append("<g transform=\"translate(").append(num(tx)).append(' ').append(num(ty)).append(") scale(")
				.append(num(s)).append(")\">\n").append(r.content()).append("</g>\n");
	}

	private static LayoutStyle layoutStyle(MappaLayout layout) {
		return switch (layout) {
			case LAYERED, AUTO -> LayoutStyle.LAYERED;
			case RADIAL -> LayoutStyle.RADIAL;
			case FORCE -> LayoutStyle.FORCE;
			case GRID -> LayoutStyle.GRID;
		};
	}

	private static EdgeStyle edgeStyle(MappaEdges edges) {
		return switch (edges) {
			case CURVED, AUTO -> EdgeStyle.CURVED;
			case ORTHOGONAL -> EdgeStyle.ORTHOGONAL;
			case STRAIGHT -> EdgeStyle.STRAIGHT;
			case DIRECTIONAL -> EdgeStyle.DIRECTIONAL;
		};
	}

	private static String hex(Color c) {
		return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
	}

	private static String escape(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static String num(double v) {
		return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%.2f", v);
	}
}
