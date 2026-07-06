package io.github.wesleym.mappa.internal.render;

import io.github.wesleym.mappa.MappaDetail;
import io.github.wesleym.mappa.MappaEdges;
import io.github.wesleym.mappa.MappaLayout;
import io.github.wesleym.mappa.MappaMap;
import io.github.wesleym.mappa.MappaMinimap;
import io.github.wesleym.mappa.MappaOptions;
import io.github.wesleym.mappa.MappaTheme;
import io.github.wesleym.mappa.internal.layout.EdgeRouter;
import io.github.wesleym.mappa.internal.layout.EdgeStyle;
import io.github.wesleym.mappa.internal.layout.LabelLayout;
import io.github.wesleym.mappa.internal.layout.LayoutStyle;
import io.github.wesleym.mappa.internal.community.CommunityNames;
import io.github.wesleym.mappa.internal.model.EntityBox;
import io.github.wesleym.mappa.internal.model.Link;
import io.github.wesleym.mappa.internal.model.Scene;
import io.github.wesleym.mappa.internal.model.SceneBuilder;

import io.github.wesleym.mappa.MappaBackground;

import javax.swing.JComponent;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The headless render path: turns a {@link MappaMap} into a laid-out {@link Scene}, routes its edges, and
 * paints the whole diagram fit to a frame. This is the same pipeline the interactive canvas runs — scene,
 * geometry, paths, labels, {@link SceneRenderer#draw} — with no viewport, so an export is always the full map.
 */
public final class StaticRender {

	private static final int MARGIN = 48;
	static final Font TITLE_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 13);
	static final Font ROW_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 12);

	private StaticRender() { }

	public static void render(Graphics2D g, MappaMap map, MappaOptions options, MappaTheme theme, int width,
			int height) {
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setColor(theme.background());
		g.fillRect(0, 0, width, height);

		SceneBuilder.TextWidth textWidth = (t, f) -> g.getFontMetrics(f).stringWidth(t);
		boolean labels = options.relationshipLabels();
		boolean keysOnly = keysOnly(map, options.detail());
		Scene scene = SceneBuilder.build(map, TITLE_FONT, ROW_FONT, textWidth, labels, false, keysOnly,
				layoutStyle(map, options.layout()));

		List<EdgeRouter.EdgeGeometry> geometry = EdgeRouter.route(scene);
		EdgeStyle edgeStyle = edgeStyle(options.edges());
		List<Path2D> paths = new ArrayList<>(geometry.size());
		for (EdgeRouter.EdgeGeometry eg : geometry) {
			paths.add(edgeStyle.path(eg.waypoints(), eg.startHorizontal(), eg.endHorizontal()));
		}
		Map<Integer, LabelLayout.Placed> labelRects = labels
				? LabelLayout.layout(scene, paths, g.getFontMetrics(ROW_FONT), Map.of())
				: Map.of();

		Rectangle2D world = scene.worldBounds();
		if (world == null) {
			return;   // an empty map: just the background
		}
		double zoom = Math.min(1.0, Math.min((width - MARGIN * 2) / Math.max(1, world.getWidth()),
				(height - MARGIN * 2) / Math.max(1, world.getHeight())));

		SceneRenderer renderer = new SceneRenderer(theme, TITLE_FONT, ROW_FONT);
		renderer.setDirectionalEdges(edgeStyle == EdgeStyle.DIRECTIONAL);
		renderer.setClusterNames(clusterNames(scene));   // named community regions on large schemas

		Graphics2D g2 = (Graphics2D) g.create();
		g2.translate((width - world.getWidth() * zoom) / 2, (height - world.getHeight() * zoom) / 2);
		g2.scale(zoom, zoom);
		g2.translate(-world.getX(), -world.getY());
		renderer.draw(g2, scene, geometry, paths, labelRects, null, null, -1, labels, world);
		g2.dispose();
	}

	// Export margin, kept equal to the canvas's so the interactive-HTML overlay JSON aligns with the SVG.
	private static final int EXPORT_MARGIN = 24;

	private record Export(Scene scene, List<EdgeRouter.EdgeGeometry> geometry, List<Path2D> paths,
			Map<Integer, LabelLayout.Placed> labelRects, Rectangle2D world, boolean labels, EdgeStyle edgeStyle) { }

	// The full-map export scene: every field shown (capHeights = false), edges routed, world grown to include
	// edge curves (self-loops dip past the box bounds) so nothing is clipped.
	private static Export prepareExport(MappaMap map, MappaOptions options) {
		BufferedImage probe = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
		Graphics2D pg = probe.createGraphics();
		try {
			SceneBuilder.TextWidth textWidth = (t, f) -> pg.getFontMetrics(f).stringWidth(t);
			boolean labels = options.relationshipLabels();
			Scene scene = SceneBuilder.build(map, TITLE_FONT, ROW_FONT, textWidth, labels, false,
					keysOnly(map, options.detail()), layoutStyle(map, options.layout()));
			List<EdgeRouter.EdgeGeometry> geometry = EdgeRouter.route(scene);
			EdgeStyle edgeStyle = edgeStyle(options.edges());
			List<Path2D> paths = new ArrayList<>(geometry.size());
			Rectangle2D world = scene.worldBounds();
			if (world == null) {
				world = new Rectangle2D.Double(0, 0, 1, 1);
			}
			for (EdgeRouter.EdgeGeometry eg : geometry) {
				Path2D p = edgeStyle.path(eg.waypoints(), eg.startHorizontal(), eg.endHorizontal());
				paths.add(p);
				world = world.createUnion(p.getBounds2D());
			}
			Map<Integer, LabelLayout.Placed> labelRects = labels
					? LabelLayout.layout(scene, paths, pg.getFontMetrics(ROW_FONT), Map.of())
					: Map.of();
			return new Export(scene, geometry, paths, labelRects, world, labels, edgeStyle);
		}
		finally {
			pg.dispose();
		}
	}

	/** Renders the full map to a standalone SVG document string (glyphs outlined; no font dependency). */
	public static String svg(MappaMap map, MappaOptions options, MappaTheme theme, boolean transparent) {
		Export e = prepareExport(map, options);
		Rectangle2D world = e.world();
		int width = (int) Math.ceil(world.getWidth()) + EXPORT_MARGIN * 2;
		int height = (int) Math.ceil(world.getHeight()) + EXPORT_MARGIN * 2;
		SvgGraphics2D svg = new SvgGraphics2D(width, height);
		if (!transparent) {
			svg.setColor(theme.background());
			svg.fillRect(0, 0, width, height);
		}
		svg.translate(EXPORT_MARGIN - world.getX(), EXPORT_MARGIN - world.getY());
		SceneRenderer renderer = new SceneRenderer(theme, TITLE_FONT, ROW_FONT);
		renderer.setDirectionalEdges(e.edgeStyle() == EdgeStyle.DIRECTIONAL);
		renderer.setClusterNames(clusterNames(e.scene()));
		renderer.draw(svg, e.scene(), e.geometry(), e.paths(), e.labelRects(), null, null, -1, e.labels(), null);
		return svg.document();
	}

	/** Renders the full map as a single self-contained interactive HTML page (inlined SVG + pan/zoom viewer). */
	public static String interactiveHtml(MappaMap map, MappaOptions options, MappaTheme theme, String title,
			boolean transparent) {
		Export e = prepareExport(map, options);
		Rectangle2D world = e.world();
		int width = (int) Math.ceil(world.getWidth()) + EXPORT_MARGIN * 2;
		int height = (int) Math.ceil(world.getHeight()) + EXPORT_MARGIN * 2;
		SvgGraphics2D svg = new SvgGraphics2D(width, height);
		if (!transparent) {
			svg.setColor(theme.background());
			svg.fillRect(0, 0, width, height);
		}
		svg.translate(EXPORT_MARGIN - world.getX(), EXPORT_MARGIN - world.getY());
		SceneRenderer renderer = new SceneRenderer(theme, TITLE_FONT, ROW_FONT);
		renderer.setDirectionalEdges(e.edgeStyle() == EdgeStyle.DIRECTIONAL);
		renderer.setClusterNames(clusterNames(e.scene()));
		renderer.draw(svg, e.scene(), e.geometry(), e.paths(), e.labelRects(), null, null, -1, e.labels(), null);
		String background = transparent ? null : String.format("#%06X", theme.background().getRGB() & 0xFFFFFF);
		return HtmlExport.wrap(svg.document(), title, background,
				MappaCanvas.sceneData(e.scene(), e.world()));
	}

	/** Builds and configures the live interactive canvas for {@code map} — the {@code component()} entry point. */
	public static JComponent live(MappaMap map, MappaOptions options, MappaTheme theme,
			Consumer<MappaMap.Entity> onSelected, Consumer<MappaMap> onArranged, MappaMinimap minimap) {
		MappaCanvas canvas = new MappaCanvas(theme);
		canvas.setLayoutStyle(layoutStyle(map, options.layout()));
		canvas.setEdgeStyle(edgeStyle(options.edges()));
		canvas.setBackgroundStyle(backdrop(options.background()));
		canvas.setKeysOnly(keysOnly(map, options.detail()));
		canvas.setShowJoinColumns(options.relationshipLabels());
		canvas.setMinimap(minimap);
		Consumer<MappaMap.Entity> sink = onSelected == null ? e -> { } : onSelected;
		canvas.setActiveHandler(name -> sink.accept(name == null ? null : entityByName(map, name)));
		canvas.setArrangedHandler(onArranged);
		canvas.setMap(map);
		return canvas;
	}

	private static MappaMap.Entity entityByName(MappaMap map, String name) {
		String key = name.toLowerCase(Locale.ROOT);
		return map.entities().stream()
				.filter(e -> e.name().toLowerCase(Locale.ROOT).equals(key))
				.findFirst().orElse(null);
	}

	private static Backdrop backdrop(MappaBackground background) {
		return switch (background) {
			case DOTS -> Backdrop.DOTS;
			case GRID -> Backdrop.GRID;
			case HEXAGONS -> Backdrop.HEXAGONS;
			case PLAIN -> Backdrop.PLAIN;
		};
	}

	// Names each community from its tables (hub-weighted, connectors excluded), so a large clustered schema
	// draws labelled region panels in a headless render and export — not only in the live canvas.
	static Map<Integer, String> clusterNames(Scene scene) {
		List<EntityBox> tables = scene.tables();
		int[] within = new int[tables.size()];
		int[] cross = new int[tables.size()];
		for (Link e : scene.edges()) {
			int a = e.from();
			int b = e.to();
			if (a < 0 || b < 0 || a >= tables.size() || b >= tables.size() || a == b) {
				continue;
			}
			if (tables.get(a).clusterId() == tables.get(b).clusterId()) {
				within[a]++;
				within[b]++;
			}
			else {
				cross[a]++;
				cross[b]++;
			}
		}
		Map<Integer, List<CommunityNames.TableNode>> byCluster = new LinkedHashMap<>();
		for (int i = 0; i < tables.size(); i++) {
			EntityBox t = tables.get(i);
			if (t.clusterId() >= 0) {
				byCluster.computeIfAbsent(t.clusterId(), k -> new ArrayList<>())
						.add(new CommunityNames.TableNode(t.name(), within[i], cross[i]));
			}
		}
		return CommunityNames.nameAll(byCluster);
	}

	private static boolean keysOnly(MappaMap map, MappaDetail detail) {
		return switch (detail) {
			case KEYS -> true;
			case ALL_FIELDS -> false;
			case AUTO -> map.entities().size() > 10;
		};
	}

	private static LayoutStyle layoutStyle(MappaMap map, MappaLayout layout) {
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
}
