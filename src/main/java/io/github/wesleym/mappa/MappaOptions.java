package io.github.wesleym.mappa;

import java.util.Objects;

/** Curated behavior and presentation options for a Mappa view or export. */
public final class MappaOptions {
	private final MappaLayout layout;
	private final MappaEdges edges;
	private final MappaDetail detail;
	private final MappaBackground background;
	private final boolean relationshipLabels;

	private MappaOptions(MappaLayout layout, MappaEdges edges, MappaDetail detail, MappaBackground background,
			boolean relationshipLabels) {
		this.layout = Objects.requireNonNullElse(layout, MappaLayout.AUTO);
		this.edges = Objects.requireNonNullElse(edges, MappaEdges.AUTO);
		this.detail = Objects.requireNonNullElse(detail, MappaDetail.AUTO);
		this.background = Objects.requireNonNullElse(background, MappaBackground.DOTS);
		this.relationshipLabels = relationshipLabels;
	}

	public static MappaOptions defaults() {
		return new MappaOptions(MappaLayout.AUTO, MappaEdges.AUTO, MappaDetail.AUTO, MappaBackground.DOTS, false);
	}

	public MappaLayout layout() {
		return layout;
	}

	public MappaEdges edges() {
		return edges;
	}

	public MappaDetail detail() {
		return detail;
	}

	public MappaBackground background() {
		return background;
	}

	public boolean relationshipLabels() {
		return relationshipLabels;
	}

	public MappaOptions layout(MappaLayout value) {
		return new MappaOptions(value, edges, detail, background, relationshipLabels);
	}

	public MappaOptions edges(MappaEdges value) {
		return new MappaOptions(layout, value, detail, background, relationshipLabels);
	}

	public MappaOptions detail(MappaDetail value) {
		return new MappaOptions(layout, edges, value, background, relationshipLabels);
	}

	public MappaOptions background(MappaBackground value) {
		return new MappaOptions(layout, edges, detail, value, relationshipLabels);
	}

	public MappaOptions relationshipLabels(boolean value) {
		return new MappaOptions(layout, edges, detail, background, value);
	}
}
