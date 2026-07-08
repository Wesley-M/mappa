package io.github.wesleym.mappa;

import io.github.wesleym.mappa.internal.render.StaticRender;

import javax.swing.JComponent;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Builder for Swing views and headless renders of a {@link MappaMap}. */
public final class MappaView {
	private final MappaMap map;
	private MappaOptions options = MappaOptions.defaults();
	private MappaTheme theme = MappaTheme.light();
	private final List<EntityAction> actions = new ArrayList<>();
	private Consumer<MappaMap.Entity> onEntitySelected = entity -> { };
	private Consumer<MappaMap> onArranged = m -> { };
	private MappaMinimap minimap = MappaMinimap.AUTO;

	MappaView(MappaMap map) {
		this.map = Objects.requireNonNull(map, "map");
	}

	public MappaView options(MappaOptions value) {
		this.options = value == null ? MappaOptions.defaults() : value;
		return this;
	}

	public MappaView layout(MappaLayout value) {
		return options(options.layout(value));
	}

	public MappaView edges(MappaEdges value) {
		return options(options.edges(value));
	}

	public MappaView detail(MappaDetail value) {
		return options(options.detail(value));
	}

	public MappaView background(MappaBackground value) {
		return options(options.background(value));
	}

	public MappaView relationshipLabels(boolean value) {
		return options(options.relationshipLabels(value));
	}

	public MappaView theme(MappaTheme value) {
		this.theme = value == null ? MappaTheme.light() : value;
		return this;
	}

	/** Adds an entity-level action to the context menu. */
	public MappaView action(String label, Consumer<MappaMap.Entity> handler) {
		if (label != null && !label.isBlank() && handler != null) {
			actions.add(new EntityAction(label, handler));
		}
		return this;
	}

	/** Adds a divider between action groups in the context menu (a leading divider is not drawn). */
	public MappaView actionSeparator() {
		actions.add(EntityAction.SEPARATOR);
		return this;
	}

	/** Receives the active entity, or {@code null} when the selection clears. */
	public MappaView onEntitySelected(Consumer<MappaMap.Entity> handler) {
		this.onEntitySelected = handler == null ? entity -> { } : handler;
		return this;
	}

	/**
	 * Receives a positioned {@link MappaMap} whenever the user drags a box in the live view — the current
	 * hand-arrangement. Persist it (e.g. {@code map.write(path)}) and reopen it to restore the layout exactly.
	 */
	public MappaView onArranged(Consumer<MappaMap> handler) {
		this.onArranged = handler == null ? m -> { } : handler;
		return this;
	}

	/**
	 * Where the live view's overview minimap sits, or whether it shows at all. {@link MappaMinimap#AUTO}
	 * (the default) shows it in the bottom-right once a diagram is large; a corner forces it there at any
	 * size; {@link MappaMinimap#OFF} hides it — useful when the host draws its own chrome over the component.
	 */
	public MappaView minimap(MappaMinimap placement) {
		this.minimap = placement == null ? MappaMinimap.AUTO : placement;
		return this;
	}

	/**
	 * Returns a live Swing component with pan, zoom, drag, hover-spotlight, search, fit, and context actions,
	 * plus host-drivable {@link MappaComponent#zoomIn}/{@code zoomOut}/{@code fitView}/{@code setAnimating}.
	 */
	public MappaComponent component() {
		return new MappaComponent(
				StaticRender.live(map, options, theme, List.copyOf(actions), onEntitySelected, onArranged, minimap));
	}

	/** Renders the full map to a new image over the theme background. */
	public BufferedImage image(int width, int height) {
		return image(width, height, false);
	}

	/** Renders the full map to a new image; {@code transparent} omits the background so it keeps an alpha channel. */
	public BufferedImage image(int width, int height, boolean transparent) {
		BufferedImage image = new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		try {
			renderTo(g, image.getWidth(), image.getHeight(), transparent);
		}
		finally {
			g.dispose();
		}
		return image;
	}

	/** Renders the full map into any {@link Graphics2D} over the theme background. */
	public void renderTo(Graphics2D g, int width, int height) {
		renderTo(g, width, height, false);
	}

	/** Renders the full map into any {@link Graphics2D}; {@code transparent} omits the background fill. */
	public void renderTo(Graphics2D g, int width, int height, boolean transparent) {
		StaticRender.render(g, map, options, theme, width, height, transparent);
	}

	/** Renders the full map to a standalone SVG document (glyphs outlined, so it needs no fonts to view). */
	public String toSvg() {
		return StaticRender.svg(map, options, theme, false);
	}

	/** Writes the map as an SVG document. Pass {@code transparent} to omit the background fill. */
	public void writeSvg(Path file, boolean transparent) throws IOException {
		Files.writeString(file, StaticRender.svg(map, options, theme, transparent));
	}

	/** Renders the map as a single self-contained interactive HTML page (inlined SVG + a pan/zoom/search viewer). */
	public String toInteractiveHtml(String title) {
		return StaticRender.interactiveHtml(map, options, theme, title, false);
	}

	/** Writes the map as a self-contained interactive HTML page that opens in any browser. */
	public void writeInteractiveHtml(Path file, String title) throws IOException {
		Files.writeString(file, StaticRender.interactiveHtml(map, options, theme, title, false));
	}

	/** A host action shown for an entity; {@link #SEPARATOR} marks a divider between action groups. */
	public record EntityAction(String label, Consumer<MappaMap.Entity> handler) {
		/** A group divider in the context menu, not a selectable item (recognised by its null handler). */
		public static final EntityAction SEPARATOR = new EntityAction("", null);
	}
}
