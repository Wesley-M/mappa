package io.github.wesleym.mappa.internal.model;

import io.github.wesleym.mappa.MappaMap;
import io.github.wesleym.mappa.internal.layout.LayeredLayout;
import io.github.wesleym.mappa.internal.layout.LayoutEngine;
import io.github.wesleym.mappa.internal.layout.LayoutStyle;

import java.awt.Font;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Turns a {@link MappaMap} into a laid-out {@link Scene}: sized entity boxes and routed links. */
public final class SceneBuilder {

	/** Measures rendered text width; supplied by the canvas (which owns the font metrics). */
	@FunctionalInterface
	public interface TextWidth {
		double of(String text, Font font);
	}

	private SceneBuilder() { }

	public static Scene build(MappaMap map, Font titleFont, Font rowFont, TextWidth textWidth,
			boolean roomForEdgeLabels) {
		return build(map, titleFont, rowFont, textWidth, roomForEdgeLabels, true, false, LayoutStyle.LAYERED);
	}

	public static Scene build(MappaMap map, Font titleFont, Font rowFont, TextWidth textWidth,
			boolean roomForEdgeLabels, boolean capHeights) {
		return build(map, titleFont, rowFont, textWidth, roomForEdgeLabels, capHeights, false, LayoutStyle.LAYERED);
	}

	public static Scene build(MappaMap map, Font titleFont, Font rowFont, TextWidth textWidth,
			boolean roomForEdgeLabels, boolean capHeights, boolean keysOnly) {
		return build(map, titleFont, rowFont, textWidth, roomForEdgeLabels, capHeights, keysOnly, LayoutStyle.LAYERED);
	}

	/**
	 * Builds the scene. {@code capHeights} caps tall boxes at {@link BoxMetrics#MAX_BOX_HEIGHT} so they scroll
	 * their fields on screen; the full-diagram export passes {@code false} so every field shows. {@code keysOnly}
	 * renders only key fields (primary/reference); pass {@code false} to render every field. {@code layoutStyle}
	 * selects how each cluster is arranged. A relationship marked {@link MappaMap.Relationship#suggested()} is
	 * treated as inferred — drawn dashed and used to badge its columns.
	 */
	public static Scene build(MappaMap map, Font titleFont, Font rowFont, TextWidth textWidth,
			boolean roomForEdgeLabels, boolean capHeights, boolean keysOnly, LayoutStyle layoutStyle) {
		List<MappaMap.Relationship> declared = new ArrayList<>();
		List<MappaMap.Relationship> inferred = new ArrayList<>();
		for (MappaMap.Relationship r : map.relationships()) {
			(r.suggested() ? inferred : declared).add(r);
		}

		Map<String, Set<String>> inferredKeys = inferredKeyColumns(inferred);
		List<EntityBox> tables = map.entities().stream()
				.map(e -> new EntityBox(e.name(),
						columnsOf(e, inferredKeys.getOrDefault(key(e.name()), Set.of()), keysOnly),
						e.kind() == MappaMap.EntityKind.VIEW))
				.toList();
		List<Link> edges = resolveEdges(declared, inferred, tables);
		assignCentrality(tables, edges);

		List<LayoutEngine.Box> sizes = tables.stream()
				.map(t -> new LayoutEngine.Box(
						measureWidth(t, titleFont, rowFont, textWidth), measureHeight(t, capHeights)))
				.toList();
		List<LayoutEngine.Edge> layoutEdges = edges.stream()
				.map(e -> new LayoutEngine.Edge(e.from(), e.to()))
				.toList();

		// When edge labels are shown, widen the gap between layers so a label fits in it without overlap.
		double layerGap = roomForEdgeLabels ? LayeredLayout.LAYER_GAP + 46 : LayeredLayout.LAYER_GAP;
		LayoutEngine.Result layout = LayoutEngine.layout(sizes, layoutEdges, layerGap, layoutStyle);
		for (int i = 0; i < tables.size(); i++) {
			tables.get(i).placeCentre(layout.centres().get(i), sizes.get(i).width(), sizes.get(i).height());
			tables.get(i).setClusterId(layout.clusters().get(i));
		}
		return new Scene(tables, edges, layout.edgePaths());
	}

	// An entity's fields, optionally narrowed to keys. A field counts as a foreign key if the map declares it a
	// reference OR inference flagged it (so inferred keys get a badge and survive the keys-only filter). With
	// keysOnly off, every field is kept — a field-heavy box caps its height and scrolls instead of growing.
	private static List<Field> columnsOf(MappaMap.Entity entity, Set<String> inferredKeyColumns, boolean keysOnly) {
		return entity.fields().stream()
				.map(f -> new Field(f.name(), f.type(), f.key(),
						f.reference() || inferredKeyColumns.contains(key(f.name()))))
				.filter(f -> !keysOnly || f.primaryKey() || f.foreignKey())
				.toList();
	}

	// The foreign-key columns discovered by inference, grouped by (lower-cased) entity name — so the builder can
	// both badge them and keep them when rendering keys only. Empty when there are no suggested relationships.
	private static Map<String, Set<String>> inferredKeyColumns(List<MappaMap.Relationship> inferred) {
		Map<String, Set<String>> byTable = new HashMap<>();
		for (MappaMap.Relationship r : inferred) {
			byTable.computeIfAbsent(key(r.fromEntity()), k -> new HashSet<>()).add(key(r.fromField()));
		}
		return byTable;
	}

	// Centrality = an entity's relationship degree normalised by the busiest entity's, so hub entities (those
	// many others point at) glow strongest. Pure topology — no database round-trip.
	private static void assignCentrality(List<EntityBox> tables, List<Link> edges) {
		int[] degree = new int[tables.size()];
		for (Link edge : edges) {
			degree[edge.from()]++;
			if (edge.from() != edge.to()) {
				degree[edge.to()]++;   // a self-loop counts once, not twice, toward the hub glow
			}
		}
		int max = 0;
		for (int d : degree) {
			max = Math.max(max, d);
		}
		if (max == 0) {
			return;
		}
		for (int i = 0; i < tables.size(); i++) {
			tables.get(i).setCentrality((double) degree[i] / max);
		}
	}

	private static double measureWidth(EntityBox table, Font titleFont, Font rowFont, TextWidth textWidth) {
		// The header reserves room for the kind glyph on the right, so a long name never collides with it.
		double widest = textWidth.of(table.name(), titleFont) + BoxMetrics.KIND_GLYPH_GUTTER;
		for (Field column : table.columns()) {
			widest = Math.max(widest, BoxMetrics.BADGE_GUTTER
					+ textWidth.of(column.name(), rowFont) + textWidth.of(column.type(), rowFont) + 24);
		}
		return widest + BoxMetrics.TEXT_PADDING * 2;
	}

	private static double measureHeight(EntityBox table, boolean cap) {
		double full = BoxMetrics.HEADER_HEIGHT + table.columns().size() * BoxMetrics.ROW_HEIGHT
				+ BoxMetrics.BOTTOM_PADDING;
		return cap ? Math.min(full, BoxMetrics.MAX_BOX_HEIGHT) : full;
	}

	private static List<Link> resolveEdges(List<MappaMap.Relationship> declared,
			List<MappaMap.Relationship> inferred, List<EntityBox> tables) {
		Map<String, Integer> indexByName = indexByName(tables);
		// One link per distinct reference — keyed by both the entity pair AND the joining fields — so multiple
		// references into the same entity each draw their own edge. Declared links are added first so an inferred
		// one never overrides a declared one for the same fields. A self-referential link (from == to) is kept and
		// draws as a self-loop.
		Map<String, Link> unique = new LinkedHashMap<>();
		addEdges(declared, false, indexByName, unique);
		addEdges(inferred, true, indexByName, unique);
		return List.copyOf(unique.values());
	}

	private static void addEdges(List<MappaMap.Relationship> relationships, boolean inferred,
			Map<String, Integer> indexByName, Map<String, Link> unique) {
		for (MappaMap.Relationship r : relationships) {
			Integer from = indexByName.get(key(r.fromEntity()));
			Integer to = indexByName.get(key(r.toEntity()));
			if (from != null && to != null) {
				String edgeKey = from + "->" + to + ":" + key(r.fromField()) + "->" + key(r.toField());
				unique.putIfAbsent(edgeKey, new Link(from, to, r.fromField(), r.toField(), inferred));
			}
		}
	}

	private static Map<String, Integer> indexByName(List<EntityBox> tables) {
		Map<String, Integer> index = new HashMap<>();
		for (int i = 0; i < tables.size(); i++) {
			index.putIfAbsent(key(tables.get(i).name()), i);
		}
		return index;
	}

	private static String key(String name) {
		return name == null ? "" : name.toLowerCase(Locale.ROOT);
	}
}
