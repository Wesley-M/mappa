package io.github.wesleym.mappa.internal.render;

import io.github.wesleym.mappa.internal.layout.EdgeRouter;
import io.github.wesleym.mappa.internal.layout.EdgeRouter.EdgeGeometry;
import io.github.wesleym.mappa.internal.layout.LabelLayout;
import io.github.wesleym.mappa.internal.model.Field;
import io.github.wesleym.mappa.internal.model.Link;
import io.github.wesleym.mappa.internal.model.Scene;
import io.github.wesleym.mappa.internal.model.BoxMetrics;
import io.github.wesleym.mappa.internal.model.EntityBox;
import io.github.wesleym.mappa.MappaTheme;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Paints a {@link Scene} in world coordinates: edges (smooth, crow's-foot annotated) behind the
 * table cards, with hover highlighting, drop shadows, and an accent on the focus/hovered table.
 */
final class SceneRenderer {

	private static final int CULL_PAD = 40;   // world-px margin around the viewport so glow/shadow isn't clipped

	private final MappaTheme theme;
	private final Font titleFont;
	private final Font rowFont;
	private final Font regionLabelFont;
	private Map<Integer, String> clusterNames = Map.of();   // community id → label, drawn on its region
	// When set, a declared edge fades from the outbound hue at the child (FK) end to the inbound hue at the parent
	// (referenced) end, so reference direction reads from across the diagram. Both hues are pulled from the live
	// theme — warm (danger) for outbound, cool (view) for inbound — a pairing that stays distinct on every palette
	// (brands flip warm/cool between themes, but warm-vs-cool never collides).
	private boolean directionalEdges;
	private boolean lightweight;   // drop the decorative passes (glows, shadows, community regions) for speed

	SceneRenderer(MappaTheme theme, Font titleFont, Font rowFont) {
		this.theme = theme;
		this.titleFont = titleFont;
		this.rowFont = rowFont;
		// Keep the title font's own weight (bundled Inter SemiBold carries it in the outlines; the logical
		// fallback carries a BOLD style flag) — re-deriving BOLD here would fake-embolden the bundled face.
		this.regionLabelFont = titleFont.deriveFont(12.5f);
	}

	// MappaTheme carries the same palette QueryTheme did, minus two derived tints the renderer wants:
	// a softened brand for centrality glows, and a fainter line for community-region panels. Both are
	// blended from slots the theme does expose, so a host only ever supplies the core colours.
	private Color brandSoft() {
		return mix(theme.entityHeader(), theme.surface(), 0.55f);
	}

	private Color clusterRegionLine() {
		return mix(theme.clusterRegion(), theme.line(), 0.5f);
	}

	private static Color mix(Color a, Color b, float t) {
		return new Color(
				Math.round(a.getRed() * (1 - t) + b.getRed() * t),
				Math.round(a.getGreen() * (1 - t) + b.getGreen() * t),
				Math.round(a.getBlue() * (1 - t) + b.getBlue() * t));
	}

	/** Community labels keyed by cluster id, painted as a quiet header on each community's region. */
	void setClusterNames(Map<Integer, String> names) {
		this.clusterNames = names == null ? Map.of() : names;
	}

	/** When true, the decorative passes (centrality glows, soft shadows, community region panels) are skipped. */
	void setLightweight(boolean lightweight) {
		this.lightweight = lightweight;
	}

	/** When true, declared edges are colour-graded by reference direction and tables saturate by centrality. */
	void setDirectionalEdges(boolean directional) {
		this.directionalEdges = directional;
	}

	/**
	 * Renders the full static scene (no moving particles). {@code geometry}/{@code paths} are routed by
	 * the caller. {@code excluded} is a table index to skip together with its edges — the caller draws
	 * that one live on top while it's being dragged, so this buffer can be reused for every drag frame.
	 */
	void draw(Graphics2D g2, Scene scene, List<EdgeGeometry> geometry, List<Path2D> paths,
			Map<Integer, LabelLayout.Placed> labelRects, EntityBox hovered, String focusTable,
			int excluded, boolean showJoinLabels, Rectangle2D visible) {
		draw(g2, scene, geometry, paths, labelRects, hovered, focusTable, excluded, showJoinLabels, visible, null);
	}

	/**
	 * As above, but with {@code edgeBounds} (one rect per edge, parallel to {@code paths}) precomputed by the
	 * caller. The banded rasteriser invokes {@code draw} once per band on the SAME edges, so caching the bounds
	 * once (the canvas does, in ensureGeometry) avoids re-running {@code Path2D.getBounds2D()} — which iterates
	 * the curve and allocates a rect — per edge per band. Pass {@code null} to fall back to computing them here.
	 */
	void draw(Graphics2D g2, Scene scene, List<EdgeGeometry> geometry, List<Path2D> paths,
			Map<Integer, LabelLayout.Placed> labelRects, EntityBox hovered, String focusTable,
			int excluded, boolean showJoinLabels, Rectangle2D visible, List<Rectangle2D> edgeBounds) {
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		// Viewport culling: only items overlapping the visible world rect are drawn, so the cost scales with
		// what's on screen — not the whole schema. Padded for the hub glow/shadow that bleeds past a box.
		Rectangle2D clip = visible == null ? null
				: new Rectangle2D.Double(visible.getX() - CULL_PAD, visible.getY() - CULL_PAD,
						visible.getWidth() + 2 * CULL_PAD, visible.getHeight() + 2 * CULL_PAD);

		// Hovering a table lights it up with its relationships and dims everything else.
		int hoveredIndex = hovered == null ? -1 : scene.indexOf(hovered);
		boolean hovering = hoveredIndex >= 0;
		List<Link> edges = scene.edges();
		boolean[] activeEdge = new boolean[edges.size()];
		boolean[] edgeVisible = new boolean[paths.size()];
		for (int i = 0; i < paths.size(); i++) {
			if (clip == null) {
				edgeVisible[i] = true;
				continue;
			}
			// Pad by 1px: a perfectly vertical/horizontal edge has a zero-width/height bounds, and
			// Rectangle2D treats an empty rect as intersecting nothing — which would wrongly cull it.
			Rectangle2D b = edgeBounds != null ? edgeBounds.get(i) : paths.get(i).getBounds2D();
			edgeVisible[i] = clip.intersects(b.getX() - 1, b.getY() - 1, b.getWidth() + 2, b.getHeight() + 2);
		}
		if (!lightweight) {
			paintClusterRegions(g2, scene, clip);   // community panels + labels — decorative, dropped in lightweight
		}

		Set<Integer> activeBoxes = new HashSet<>();
		if (hovering) {
			activeBoxes.add(hoveredIndex);
			for (int i = 0; i < edges.size(); i++) {
				if (edges.get(i).from() == hoveredIndex || edges.get(i).to() == hoveredIndex) {
					activeEdge[i] = true;
					activeBoxes.add(edges.get(i).from());
					activeBoxes.add(edges.get(i).to());
				}
			}
		}

		// Effective zoom for LOD: real zoom only when the schema is large enough to warrant simplifying;
		// otherwise MAX, so small schemas always render full detail (no disappearing columns). A full export
		// (no clip) bypasses LOD entirely — it renders every column at full detail whatever the export scale.
		double zoom = clip == null ? Double.MAX_VALUE : lodZoom(scene, g2);
		// Zoomed far out, the relationship web is just noise over tiny boxes, so drop it and keep the boxes —
		// except the active table's own (highlighted) edges, which always draw so a selection stays legible.
		// Edges return as you zoom in past EDGE_ZOOM. Big win on large schemas: skips hundreds of edge strokes.
		boolean drawEdges = zoom >= EDGE_ZOOM;
		for (int i = 0; i < paths.size(); i++) {
			if (touches(edges.get(i), excluded) || !edgeVisible[i] || (!drawEdges && !activeEdge[i])) {
				continue;
			}
			boolean inferred = edges.get(i).inferred();
			applyEdgeStyle(g2, hovering, activeEdge[i], inferred, inferred);
			// In directional mode a declared edge fades outbound→inbound (its own colour carries the direction);
			// inferred edges stay dashed amber. Otherwise the usual muted/active stroke, with bridges receding.
			if (directionalEdges && !inferred) {
				g2.setPaint(directionalGradient(geometry.get(i), hovering && !activeEdge[i]));
			}
			else if (!hovering && isBridge(scene, edges.get(i))) {
				g2.setColor(alpha(theme.muted(), 70));   // cross-module links recede so the groups read first
			}
			g2.draw(paths.get(i));
		}
		List<EntityBox> tables = scene.tables();
		for (int i = 0; i < tables.size(); i++) {
			if (i == excluded || (clip != null && !tables.get(i).bounds().intersects(clip))) {
				continue;
			}
			paintTable(g2, tables.get(i), !hovering || activeBoxes.contains(i), hovered, focusTable, zoom);
		}
		// Promote a lit table's own edges above the boxes. Every edge — the active one included — was stroked in
		// the pass above, behind the tables; an opaque card (see paintTable's fill) then swallows the highlight
		// wherever a lit relationship runs behind another table. Re-stroking just the active edges here keeps the
		// selection legible over the cards. This is the static counterpart to the flow overlay, which can't stand
		// in for it — it draws only intermittent dashes and switches off when zoomed out or when many edges are lit.
		if (hovering) {
			for (int i = 0; i < paths.size(); i++) {
				if (!activeEdge[i] || touches(edges.get(i), excluded) || !edgeVisible[i]) {
					continue;
				}
				boolean inferred = edges.get(i).inferred();
				applyEdgeStyle(g2, true, true, inferred, inferred);
				if (directionalEdges && !inferred) {
					g2.setPaint(directionalGradient(geometry.get(i), false));
				}
				g2.draw(paths.get(i));
			}
		}
		// Crow's-foot decorations go on top of the boxes so they're never painted over (and follow the same
		// far-zoom edge LOD — no feet where the edge itself isn't drawn).
		for (int i = 0; i < geometry.size(); i++) {
			if (!touches(edges.get(i), excluded) && edgeVisible[i] && (drawEdges || activeEdge[i])) {
				applyEdgeStyle(g2, hovering, activeEdge[i], edges.get(i).inferred(), false);
				paintEndDecorations(g2, geometry.get(i));
			}
		}
		// Join-column labels, only when enabled — positions come from the force pass (LabelLayout).
		// When a table is active, labels for edges that don't touch it are dimmed along with the rest.
		if (showJoinLabels) {
			paintJoinLabels(g2, scene, labelRects, hoveredIndex, clip);
		}
	}

	// Flow level-of-detail. The static highlight of a lit table's edges is baked into the scene buffer
	// (see draw()), so the flow is pure decoration on top. We draw it as a travelling dashed stroke — one
	// stroked path per edge — rather than a particle stream, so the per-frame cost is a constant couple of
	// strokes per edge regardless of how long or how distant the edge is (the old comets paid
	// count * tail-samples antialiased fills, which fell apart on large/far graphs). One bound remains: don't
	// animate at all past a lit-edge count — counted on the edges actually in the viewport, so a hub with
	// hundreds of relationships still flows when you're zoomed in on a handful of them.
	static final int MAX_FLOW_EDGES = 80;

	// How far past the viewport the flow keeps stroking (screen px, scaled to world units where used), so a
	// comet's glow never pops at the boundary — comfortably wider than the widest glow stroke (9px).
	private static final double FLOW_CLIP_PAD = 16;

	// Below this device zoom the flow doesn't animate on a large scene — the same move as the other
	// low-geometry strategies (see the LOD knobs): zoomed far out the edges are hairlines and the whole
	// diagram is in view, so the flow is both at its most expensive and pure visual noise. Sits between
	// EDGE_ZOOM (edges reappear) and COLUMN_ZOOM (rows become readable): once you can nearly read a box,
	// its relationships start moving.
	static final double FLOW_ZOOM = 0.45;

	/**
	 * Whether the flow animation runs at this device zoom (px per world unit). Mirrors {@link #lodZoom}'s
	 * rule: a small scene always animates (it's cheap and nothing should ever visibly switch off); a large
	 * one animates only once zoomed in past {@link #FLOW_ZOOM} — further out, the static highlight stands in.
	 */
	static boolean flowAnimates(Scene scene, double deviceZoom) {
		return scene.tables().size() <= LOD_MIN_TABLES || deviceZoom >= FLOW_ZOOM;
	}

	/**
	 * Overlay: a travelling, glowing dashed highlight along the active table's edges. Only the stretch of
	 * each edge inside {@code visible} is stroked (Java2D would flatten, dash, and cap the whole path before
	 * clipping — on a large diagram most of that work is off-screen), with the dash phase advanced by the
	 * skipped arc length so the clip is invisible. Edges wholly outside the viewport cost one bounds test.
	 */
	void drawFlow(Graphics2D g2, Scene scene, List<double[][]> flats, EntityBox hovered,
			double flowPhase, double scale, Rectangle2D visible, List<Rectangle2D> edgeBounds) {
		if (!flowAnimates(scene, g2.getTransform().getScaleX())) {
			return;   // zoomed far out on a large scene — the buffer's static highlight stands in
		}
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		int index = scene.indexOf(hovered);
		List<Link> edges = scene.edges();
		double inv = 1.0 / Math.max(scale, 0.0001);
		Rectangle2D clip = padded(visible, FLOW_CLIP_PAD * inv);
		List<Integer> animate = new ArrayList<>();
		for (int i = 0; i < flats.size() && i < edges.size(); i++) {
			if ((edges.get(i).from() == index || edges.get(i).to() == index) && inView(clip, edgeBounds, i)) {
				animate.add(i);
			}
		}
		if (animate.isEmpty() || animate.size() > MAX_FLOW_EDGES) {
			return;   // nothing lit on screen, or too many to animate — the buffer's static highlight stands in
		}
		FlowStyle brandStyle = FlowStyle.of(theme.entityHeader(), inv, flowPhase);
		FlowStyle inferredStyle = FlowStyle.of(INFERRED, inv, flowPhase);
		Path2D line = new Path2D.Double();       // one reusable path for every edge this frame
		Path2D chevrons = new Path2D.Double();   // and one for each edge's arrow heads
		for (int i : animate) {
			double[][] flat = flats.get(i);
			if (flat == null || flat[0].length < 2) {
				continue;
			}
			int[] run = visibleRun(flat, clip);
			if (run == null) {
				continue;   // bounds overlapped the viewport but no actual segment does (an L-shaped route)
			}
			polyInto(line, flat, run[0], run[1]);
			double skipped = flat[2][run[0]];   // arc length ahead of the run — keeps the dash phase seamless
			if (directionalEdges && !edges.get(i).inferred()) {
				paintFlowDirectional(g2, line, flat, brandStyle, skipped);   // comet carries the outbound→inbound code
				paintDashArrows(g2, chevrons, flat, run, brandStyle, directionalFlowPaint(flat));
			}
			else {
				FlowStyle style = edges.get(i).inferred() ? inferredStyle : brandStyle;
				paintFlowDash(g2, line, style.colors, style.strokesFrom(skipped));
				paintDashArrows(g2, chevrons, flat, run, style, style.colors[3]);
			}
		}
	}

	// Whether edge i's cached bounds touch the padded viewport (1px-padded: a perfectly straight edge has a
	// zero-area bounds rect, which Rectangle2D would treat as intersecting nothing). No bounds → assume visible.
	private static boolean inView(Rectangle2D clip, List<Rectangle2D> edgeBounds, int i) {
		if (clip == null || edgeBounds == null || i >= edgeBounds.size()) {
			return true;
		}
		Rectangle2D b = edgeBounds.get(i);
		return clip.intersects(b.getX() - 1, b.getY() - 1, b.getWidth() + 2, b.getHeight() + 2);
	}

	private static Rectangle2D padded(Rectangle2D r, double pad) {
		return r == null ? null
				: new Rectangle2D.Double(r.getX() - pad, r.getY() - pad, r.getWidth() + 2 * pad, r.getHeight() + 2 * pad);
	}

	// The arrow heads on the travelling dashes: chevron arm length (screen px) and sweep-back angle.
	private static final double ARROW_WING = 4.8;
	private static final double WING_COS = Math.cos(Math.toRadians(30));
	private static final double WING_SIN = Math.sin(Math.toRadians(30));

	/**
	 * Little arrow heads on the travelling dashes: a chevron at each dash's leading edge, pointing the way
	 * the pulse marches (FK → PK) — so the flow reads as direction even in a still frame. Tip positions are
	 * a global function of arclength and the frame's dash phase, so they land exactly on the dash fronts of
	 * whatever run was stroked, clipped or not. Every chevron on the edge accumulates into one reusable
	 * path, stroked twice to match the dash core's layering (body colour + white-hot centre).
	 */
	private static void paintDashArrows(Graphics2D g2, Path2D chevrons, double[][] flat, int[] run,
			FlowStyle style, Paint body) {
		double inv = style.inv;
		double period = style.dash + style.gap;
		double len = flat[2][flat[2].length - 1];
		double from = flat[2][run[0]];
		double to = Math.min(flat[2][run[1]], len);
		chevrons.reset();
		double[] tip = new double[2];
		double[] back = new double[2];
		double[] ahead = new double[2];
		boolean any = false;
		// The dash occupies pattern positions [0, dash), so its leading (higher-arclength) edge sits where
		// the pattern position equals the dash length: s ≡ dash − phaseOffset (mod period).
		for (double s = from + mod(style.dash - style.phaseOffset - from, period); s <= to; s += period) {
			// The wings grow in over the first few px of the edge and shrink out over the last few, so an
			// arrow wrapping from the PK end back to the FK end never pops between frames. (Distance to the
			// path's true ends, never the viewport's — so clipping can't change how any arrow looks.)
			double wing = ARROW_WING * inv * Math.min(1, Math.min(s, len - s) / (8 * inv));
			if (wing < 0.4 * inv) {
				continue;
			}
			pointAt(flat, s, tip);
			// Orientation from a chord centred on the tip: a longer, symmetric baseline turns the polyline's
			// corner-by-corner direction changes into a gradual rotation — a short trailing chord made the
			// arrow visibly snap each time it crossed a flattening vertex on a curved edge.
			pointAt(flat, Math.max(0, s - 4 * inv), back);
			pointAt(flat, Math.min(len, s + 4 * inv), ahead);
			double dx = ahead[0] - back[0];
			double dy = ahead[1] - back[1];
			double d = Math.hypot(dx, dy);
			if (d < 1e-6) {
				continue;
			}
			double bx = -dx / d;   // unit vector pointing back along the edge
			double by = -dy / d;
			chevrons.moveTo(tip[0] + (bx * WING_COS - by * WING_SIN) * wing,
					tip[1] + (bx * WING_SIN + by * WING_COS) * wing);
			chevrons.lineTo(tip[0], tip[1]);
			chevrons.lineTo(tip[0] + (bx * WING_COS + by * WING_SIN) * wing,
					tip[1] + (by * WING_COS - bx * WING_SIN) * wing);
			any = true;
		}
		if (!any) {
			return;
		}
		g2.setPaint(body);
		g2.setStroke(style.arrowBody);
		g2.draw(chevrons);
		g2.setColor(style.colors[4]);   // the same white-hot centre the dashes carry
		g2.setStroke(style.arrowCore);
		g2.draw(chevrons);
	}

	// The directional-mode arrow colouring: the same outbound→inbound gradient the dash body runs, at full
	// strength. Falls back to the outbound hue for a degenerate (near-zero-span) edge.
	private Paint directionalFlowPaint(double[][] flat) {
		int n = flat[0].length;
		double x0 = flat[0][0];
		double y0 = flat[1][0];
		double x1 = flat[0][n - 1];
		double y1 = flat[1][n - 1];
		if (Math.hypot(x1 - x0, y1 - y0) < 1.0) {
			return outboundColour();
		}
		return new GradientPaint((float) x0, (float) y0, outboundColour(), (float) x1, (float) y1, inboundColour());
	}

	// The point at arclength s along the flattened polyline (x[], y[], cumulative-length[]), clamped to it.
	private static void pointAt(double[][] flat, double s, double[] out) {
		double[] cum = flat[2];
		int hi = Arrays.binarySearch(cum, s);
		if (hi >= 0) {
			out[0] = flat[0][hi];
			out[1] = flat[1][hi];
			return;
		}
		hi = Math.min(-hi - 1, cum.length - 1);
		if (hi == 0) {
			out[0] = flat[0][0];
			out[1] = flat[1][0];
			return;
		}
		int lo = hi - 1;
		double seg = cum[hi] - cum[lo];
		double t = seg <= 0 ? 0 : (s - cum[lo]) / seg;
		out[0] = flat[0][lo] + (flat[0][hi] - flat[0][lo]) * t;
		out[1] = flat[1][lo] + (flat[1][hi] - flat[1][lo]) * t;
	}

	private static double mod(double x, double m) {
		double r = x % m;
		return r < 0 ? r + m : r;
	}

	// The index range [first, last] of the polyline's points whose segments touch the viewport — the only
	// stretch worth stroking. Conservative (segment bounding boxes), so it only ever includes a sliver extra;
	// an edge that leaves and re-enters keeps its (invisible) middle, which is correct just slightly wasteful.
	// Null when nothing touches. An O(points) walk per edge per frame, versus dashing the whole path.
	private static int[] visibleRun(double[][] flat, Rectangle2D clip) {
		int n = flat[0].length;
		if (clip == null) {
			return new int[] { 0, n - 1 };
		}
		double[] xs = flat[0];
		double[] ys = flat[1];
		int first = -1;
		int last = -1;
		for (int i = 0; i < n - 1; i++) {
			double minX = Math.min(xs[i], xs[i + 1]);
			double minY = Math.min(ys[i], ys[i + 1]);
			double w = Math.max(xs[i], xs[i + 1]) - minX;
			double h = Math.max(ys[i], ys[i + 1]) - minY;
			if (clip.intersects(minX - 1, minY - 1, w + 2, h + 2)) {
				if (first < 0) {
					first = i;
				}
				last = i + 1;
			}
		}
		return first < 0 ? null : new int[] { first, last };
	}

	/**
	 * Overlay: a traced join path — the edges on the shortest route between two tables, drawn glowing with
	 * particles flowing along the whole chain and a ring around each endpoint, so the user sees exactly how
	 * to join two distant tables. Drawn every frame on top of the (undimmed) static buffer.
	 */
	void drawPath(Graphics2D g2, Scene scene, List<Path2D> paths, List<double[][]> flats,
			List<Integer> pathEdges, int fromIndex, int toIndex, double flowPhase, double scale,
			Rectangle2D visible, List<Rectangle2D> edgeBounds) {
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		double inv = 1.0 / Math.max(scale, 0.0001);
		Rectangle2D clip = padded(visible, FLOW_CLIP_PAD * inv);
		for (int edge : pathEdges) {
			if (edge < 0 || edge >= paths.size() || !inView(clip, edgeBounds, edge)) {
				continue;
			}
			Path2D path = paths.get(edge);
			g2.setColor(alpha(theme.entityHeader(), 80));                 // soft glow base
			g2.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g2.draw(path);
			g2.setColor(theme.entityHeader());                            // crisp core
			g2.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g2.draw(path);
		}
		// The glow + core above already mark the route; overlay the travelling dashes so the eye follows the
		// flow direction, but only when the route is small enough to animate smoothly and the view is close
		// enough for movement to read (the static glow already marks the route when zoomed far out).
		if (pathEdges.size() <= MAX_FLOW_EDGES && flowAnimates(scene, g2.getTransform().getScaleX())) {
			FlowStyle brandStyle = FlowStyle.of(theme.entityHeader(), inv, flowPhase);
			FlowStyle inferredStyle = FlowStyle.of(INFERRED, inv, flowPhase);
			Path2D line = new Path2D.Double();
			Path2D chevrons = new Path2D.Double();
			for (int edge : pathEdges) {
				if (edge < 0 || edge >= flats.size() || !inView(clip, edgeBounds, edge)) {
					continue;
				}
				double[][] flat = flats.get(edge);
				if (flat == null || flat[0].length < 2) {
					continue;
				}
				int[] run = visibleRun(flat, clip);
				if (run == null) {
					continue;
				}
				polyInto(line, flat, run[0], run[1]);
				double skipped = flat[2][run[0]];
				boolean inferred = edge < scene.edges().size() && scene.edges().get(edge).inferred();
				if (directionalEdges && !inferred) {
					paintFlowDirectional(g2, line, flat, brandStyle, skipped);
					paintDashArrows(g2, chevrons, flat, run, brandStyle, directionalFlowPaint(flat));
				}
				else {
					FlowStyle style = inferred ? inferredStyle : brandStyle;
					paintFlowDash(g2, line, style.colors, style.strokesFrom(skipped));
					paintDashArrows(g2, chevrons, flat, run, style, style.colors[3]);
				}
			}
		}
		paintPathEndpoint(g2, scene, fromIndex);
		paintPathEndpoint(g2, scene, toIndex);
	}

	private void paintPathEndpoint(Graphics2D g2, Scene scene, int index) {
		if (index < 0 || index >= scene.tables().size()) {
			return;
		}
		Rectangle2D b = scene.tables().get(index).bounds();
		g2.setColor(theme.entityHeader());
		g2.setStroke(new BasicStroke(2.5f));
		g2.drawRoundRect((int) b.getX() - 3, (int) b.getY() - 3,
				(int) b.getWidth() + 6, (int) b.getHeight() + 6, BoxMetrics.CORNER, BoxMetrics.CORNER);
	}

	/** Overlay: the table being dragged plus its edges, drawn live over the (excluding) static buffer. */
	void drawDragged(Graphics2D g2, Scene scene, List<EdgeGeometry> geometry, List<Path2D> paths,
			int index, String focusTable) {
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		List<Link> edges = scene.edges();
		for (int i = 0; i < paths.size(); i++) {
			if (touches(edges.get(i), index)) {
				boolean inferred = edges.get(i).inferred();
				applyEdgeStyle(g2, false, false, inferred, inferred);
				g2.draw(paths.get(i));
			}
		}
		EntityBox table = scene.tables().get(index);
		paintTable(g2, table, true, table, focusTable, lodZoom(scene, g2));
		for (int i = 0; i < geometry.size(); i++) {
			if (touches(edges.get(i), index)) {
				applyEdgeStyle(g2, false, false, edges.get(i).inferred(), false);
				paintEndDecorations(g2, geometry.get(i));
			}
		}
	}

	private static boolean touches(Link edge, int index) {
		return index >= 0 && (edge.from() == index || edge.to() == index);
	}

	// An edge between two different communities — drawn quieter so the groups read before the links between them.
	private static boolean isBridge(Scene scene, Link edge) {
		int a = scene.tables().get(edge.from()).clusterId();
		int b = scene.tables().get(edge.to()).clusterId();
		return a >= 0 && b >= 0 && a != b;
	}

	// Paints the join-column pills at the positions resolved by LabelLayout's force pass. When a pill
	// has drifted off its edge, a thin leader (with a dot on the edge) ties it back so it's unambiguous which
	// relationship the hint describes — without bending the edge itself.
	private void paintJoinLabels(Graphics2D g2, Scene scene, Map<Integer, LabelLayout.Placed> labels,
			int activeIndex, Rectangle2D clip) {
		if (labels.isEmpty()) {
			return;
		}
		FontMetrics fm = g2.getFontMetrics(rowFont);
		List<Link> edges = scene.edges();
		Composite base = g2.getComposite();
		for (Map.Entry<Integer, LabelLayout.Placed> entry : labels.entrySet()) {
			int i = entry.getKey();
			if (i < 0 || i >= edges.size() || edges.get(i).joinLabel() == null
					|| (clip != null && !entry.getValue().rect().intersects(clip))) {
				continue;
			}
			// With a table active, only its own relationships' labels stay bright; the rest dim to match.
			boolean relevant = activeIndex < 0 || touches(edges.get(i), activeIndex);
			g2.setComposite(relevant ? base : AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.22f));
			paintOneLabel(g2, edges.get(i).joinLabel(), entry.getValue(), fm);
		}
		g2.setComposite(base);
	}

	/** Overlay: repaints the labels on the given edges on top — so the flow comets sit below the hints. */
	void drawJoinLabelsOver(Graphics2D g2, Scene scene, Map<Integer, LabelLayout.Placed> labels,
			Iterable<Integer> edges) {
		if (labels.isEmpty()) {
			return;
		}
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		FontMetrics fm = g2.getFontMetrics(rowFont);
		List<Link> sceneEdges = scene.edges();
		for (int i : edges) {
			LabelLayout.Placed placed = labels.get(i);
			if (placed != null && i >= 0 && i < sceneEdges.size() && sceneEdges.get(i).joinLabel() != null) {
				paintOneLabel(g2, sceneEdges.get(i).joinLabel(), placed, fm);
			}
		}
	}

	// Draws one pill at its resolved position, with a leader line + dot back to its edge when it's displaced.
	private void paintOneLabel(Graphics2D g2, String text, LabelLayout.Placed placed, FontMetrics fm) {
		g2.setFont(rowFont);
		Rectangle2D rect = placed.rect();
		Point2D anchor = placed.anchor();
		double tx = Math.max(rect.getMinX(), Math.min(anchor.getX(), rect.getMaxX()));
		double ty = Math.max(rect.getMinY(), Math.min(anchor.getY(), rect.getMaxY()));
		if (anchor.distance(tx, ty) > 3) {
			g2.setColor(alpha(brandSoft(), 150));
			g2.setStroke(new BasicStroke(1f));
			g2.draw(new Line2D.Double(anchor.getX(), anchor.getY(), tx, ty));
			g2.fill(new Ellipse2D.Double(anchor.getX() - 1.6, anchor.getY() - 1.6, 3.2, 3.2));
		}
		int x = (int) rect.getX();
		int y = (int) rect.getY();
		int w = (int) rect.getWidth();
		int h = (int) rect.getHeight();
		g2.setColor(theme.surface());
		g2.fillRoundRect(x, y, w, h, 9, 9);
		g2.setColor(brandSoft());
		g2.setStroke(new BasicStroke(1f));
		g2.drawRoundRect(x, y, w, h, 9, 9);
		// The pill's chrome stays soft, but its text is information — full-contrast ink, not the washed-out
		// brand blend it used to share with the border, which made the join columns genuinely hard to read.
		g2.setColor(theme.text());
		g2.drawString(text, x + LabelLayout.PAD_X, y + LabelLayout.PAD_Y + fm.getAscent());
	}

	// Inferred (guessed) edges read in a distinct amber so they're never confused with declared keys.
	private static final Color INFERRED = new Color(0xE0, 0xA1, 0x16);

	private void applyEdgeStyle(Graphics2D g2, boolean hovering, boolean active) {
		applyEdgeStyle(g2, hovering, active, false, false);
	}

	private void applyEdgeStyle(Graphics2D g2, boolean hovering, boolean active, boolean inferred, boolean dashed) {
		boolean emphasised = hovering && active;
		Color colour = inferred ? INFERRED
				: hovering ? (active ? theme.entityHeader() : faded(theme.muted())) : theme.muted();
		if (inferred && hovering && !active) {
			colour = faded(colour);
		}
		g2.setColor(colour);
		float width = emphasised ? 2.2f : 1.4f;
		g2.setStroke(dashed
				? new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[] { 6f, 5f }, 0f)
				: new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
	}

	private static Color faded(Color c) {
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), 45);
	}

	// A travelling, glowing dashed highlight along the edge. Layered for a neon "glow" with high contrast: two
	// soft solid haloes light the whole edge, then a wide brand bloom, a bright brand core and a white-hot
	// highlight all share one dash pattern whose phase advances each frame, so a bright comet-like pulse marches
	// FK → PK. The five strokes/colours are identical for every edge at a given zoom+phase, so the caller builds
	// them once per frame ({@link FlowStyle}) and this just strokes the (reused) path five times.
	private static void paintFlowDash(Graphics2D g2, Path2D line, Color[] colors, Stroke[] strokes) {
		for (int k = 0; k < strokes.length; k++) {
			g2.setColor(colors[k]);
			g2.setStroke(strokes[k]);
			g2.draw(line);
		}
	}

	// The directional-mode flow: the same travelling dashes, but the glow layers run the edge's outbound→inbound
	// gradient (warm at the child it leaves, cool at the parent it reaches) so the comet carries the colour code;
	// the white-hot core stays white. {@code flat} supplies the edge's end points for the gradient.
	private static final int[] FLOW_GLOW_ALPHA = { 48, 95, 170, 255 };

	private void paintFlowDirectional(Graphics2D g2, Path2D line, double[][] flat, FlowStyle style,
			double skippedLength) {
		int n = flat[0].length;
		double x0 = flat[0][0];
		double y0 = flat[1][0];           // child / FK end
		double x1 = flat[0][n - 1];
		double y1 = flat[1][n - 1];       // parent / referenced end
		boolean degenerate = Math.hypot(x1 - x0, y1 - y0) < 1.0;
		Color warm = outboundColour();
		Color cool = inboundColour();
		// The gradient spans the edge's true endpoints even when only a run of it is stroked, so the colour
		// read at any point is identical to the unclipped edge's.
		Stroke[] strokes = style.strokesFrom(skippedLength);
		for (int k = 0; k < strokes.length; k++) {
			if (k < FLOW_GLOW_ALPHA.length && !degenerate) {
				int a = FLOW_GLOW_ALPHA[k];
				g2.setPaint(new GradientPaint((float) x0, (float) y0, alpha(warm, a),
						(float) x1, (float) y1, alpha(cool, a)));
			}
			else {
				g2.setColor(style.colors[k]);   // the white-hot core (and the degenerate fallback)
			}
			g2.setStroke(strokes[k]);
			g2.draw(line);
		}
	}

	/** The dashed-flow strokes + colours for one edge variant at a given zoom and animation phase. Built once
	 *  per frame (they don't vary per edge) so the 33ms animation doesn't allocate five strokes + colours for
	 *  each of up to {@link #MAX_FLOW_EDGES} edges. Widths/dashes are divided by the zoom to stay constant on
	 *  screen; the dash phase counts down within one period so the dashes travel FK → PK. */
	private static final class FlowStyle {

		final Color[] colors;
		private final Stroke[] strokes;   // for a whole edge — dash phase measured from the path's start
		private final Stroke arrowBody;   // the dashes' arrow-head chevrons, matching the dash core layers
		private final Stroke arrowCore;
		private final float dash;
		private final float gap;
		private final float phaseOffset;
		private final double inv;

		private FlowStyle(Stroke[] strokes, Color[] colors, float dash, float gap, float phaseOffset, double inv) {
			this.strokes = strokes;
			this.colors = colors;
			this.dash = dash;
			this.gap = gap;
			this.phaseOffset = phaseOffset;
			this.inv = inv;
			this.arrowBody = new BasicStroke((float) (2.6 * inv), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
			this.arrowCore = new BasicStroke((float) (1.2 * inv), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
		}

		static FlowStyle of(Color base, double inv, double phase) {
			float dash = (float) (11 * inv);
			float gap = (float) (9 * inv);
			float period = dash + gap;
			float phaseOffset = (float) (period - ((phase * 1.8 * inv) % period));
			Stroke[] strokes = strokes(inv, dash, gap, phaseOffset);
			Color[] colors = {
				alpha(base, 48), alpha(base, 95), alpha(base, 170), base, new Color(255, 255, 255, 235),
			};
			return new FlowStyle(strokes, colors, dash, gap, phaseOffset, inv);
		}

		/**
		 * The strokes for a subpath starting {@code startLength} into its edge: the dash phase advances by
		 * the skipped arc length, so a viewport-clipped run's dashes land exactly where the full edge's
		 * would — clipping never makes a comet jump. Zero start returns the per-frame shared strokes.
		 */
		Stroke[] strokesFrom(double startLength) {
			if (startLength <= 0) {
				return strokes;
			}
			return strokes(inv, dash, gap, (float) ((phaseOffset + startLength) % (dash + gap)));
		}

		private static Stroke[] strokes(double inv, float dash, float gap, float phaseOffset) {
			float[] pattern = { dash, gap };
			return new Stroke[] {
				new BasicStroke((float) (9.0 * inv), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND),
				new BasicStroke((float) (5.0 * inv), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND),
				new BasicStroke((float) (4.6 * inv), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, pattern, phaseOffset),
				new BasicStroke((float) (2.8 * inv), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, pattern, phaseOffset),
				new BasicStroke((float) (1.2 * inv), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, pattern, phaseOffset),
			};
		}
	}

	// Rebuilds the reusable polyline path from a flattened curve (x[], y[], …) — reset + retrace, no allocation.
	private static void polyInto(Path2D p, double[][] poly) {
		polyInto(p, poly, 0, poly[0].length - 1);
	}

	// As above, but only the points from..to (inclusive) — the viewport-visible run of a long edge.
	private static void polyInto(Path2D p, double[][] poly, int from, int to) {
		double[] xs = poly[0];
		double[] ys = poly[1];
		p.reset();
		p.moveTo(xs[from], ys[from]);
		for (int i = from + 1; i <= to; i++) {
			p.lineTo(xs[i], ys[i]);
		}
	}

	// Flattens a curve into a polyline (x[], y[], cumulative-length[]); null when degenerate. Computed once
	// per geometry change by the caller and cached, so the animation never re-flattens per frame.
	static double[][] flatten(Path2D path) {
		return flatten(path, 1.5);
	}

	// Flatness is in world units: what reads as sub-pixel at 1:1 becomes a visible gap between the chevron
	// trail and its stroked spline once the view zooms in, so the caller re-flattens finer as it zooms.
	static double[][] flatten(Path2D path, double flatness) {
		List<double[]> points = new ArrayList<>();
		double[] coords = new double[6];
		for (PathIterator it = path.getPathIterator(null, flatness); !it.isDone(); it.next()) {
			if (it.currentSegment(coords) != PathIterator.SEG_CLOSE) {
				points.add(new double[] { coords[0], coords[1] });
			}
		}
		if (points.size() < 2) {
			return null;
		}
		int n = points.size();
		double[] xs = new double[n];
		double[] ys = new double[n];
		double[] cum = new double[n];
		xs[0] = points.get(0)[0];
		ys[0] = points.get(0)[1];
		for (int i = 1; i < n; i++) {
			xs[i] = points.get(i)[0];
			ys[i] = points.get(i)[1];
			cum[i] = cum[i - 1] + Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]);
		}
		return new double[][] { xs, ys, cum };
	}

	// The outbound (child / FK, "references out") and inbound (parent, "is referenced") edge hues, taken from
	// the live theme so they mesh with every palette: warm for outbound, cool for inbound.
	private Color outboundColour() {
		return theme.outbound();
	}

	private Color inboundColour() {
		return theme.viewHeader();
	}

	// A gradient along an edge: the outbound hue at the child (foreign-key) foot, the inbound hue at the parent
	// (referenced) foot — so the colour transition reads the reference direction. {@code faded} dims it for the
	// un-hovered edges when a table is focused. Falls back to a solid colour when the feet coincide (self-loop).
	private Paint directionalGradient(EdgeGeometry edge, boolean faded) {
		int a = faded ? 60 : 255;
		Color out = alpha(outboundColour(), a);
		Color in = alpha(inboundColour(), a);
		Point2D from = edge.start();   // child / FK end
		Point2D to = edge.end();       // parent / referenced end
		if (from.distanceSq(to) < 1.0) {
			return in;
		}
		return new GradientPaint(from, out, to, in);
	}

	private static Color alpha(Color c, int a) {
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
	}

	private static final int REGION_PAD = 26;       // padding from a community's tables to its panel edge
	private static final int REGION_RADIUS = 22;    // panel corner radius

	// Draws a quiet, neutral panel behind each laid-out community so the eye reads them as distinct groups
	// — grouping by soft elevation and whitespace, not colour. Only when there's more than one group.
	private void paintClusterRegions(Graphics2D g2, Scene scene, Rectangle2D clip) {
		Map<Integer, Rectangle2D> bounds = new LinkedHashMap<>();
		for (EntityBox table : scene.tables()) {
			int id = table.clusterId();
			if (id < 0) {
				continue;
			}
			Rectangle2D b = table.bounds();
			bounds.merge(id, new Rectangle2D.Double(b.getX(), b.getY(), b.getWidth(), b.getHeight()),
					(a, c) -> a.createUnion(c));
		}
		if (bounds.size() < 2) {
			return;   // a single group needs no grouping background
		}
		for (Map.Entry<Integer, Rectangle2D> entry : bounds.entrySet()) {
			Rectangle2D r = entry.getValue();
			int x = (int) (r.getX() - REGION_PAD);
			int y = (int) (r.getY() - REGION_PAD);
			int w = (int) (r.getWidth() + 2 * REGION_PAD);
			int h = (int) (r.getHeight() + 2 * REGION_PAD);
			if (clip != null && !clip.intersects(x, y, w, h)) {
				continue;
			}
			g2.setColor(theme.clusterRegion());
			g2.fillRoundRect(x, y, w, h, REGION_RADIUS, REGION_RADIUS);
			g2.setColor(clusterRegionLine());
			g2.setStroke(new BasicStroke(1f));
			g2.drawRoundRect(x, y, w, h, REGION_RADIUS, REGION_RADIUS);
			paintRegionLabel(g2, clusterNames.get(entry.getKey()), x, y, w);
		}
	}

	// A quiet community name sitting just above the region's top-left corner — a group header, not a chip.
	private void paintRegionLabel(Graphics2D g2, String label, int x, int y, int w) {
		if (label == null || label.isBlank()) {
			return;
		}
		g2.setFont(regionLabelFont);
		FontMetrics fm = g2.getFontMetrics();
		String text = label;
		if (fm.stringWidth(text) > w) {
			while (text.length() > 1 && fm.stringWidth(text + "…") > w) {
				text = text.substring(0, text.length() - 1);
			}
			text = text + "…";
		}
		// Quiet, but readable: lean toward the theme's ink rather than pure muted, which sat too close to the
		// background on most palettes to read at a glance.
		g2.setColor(mix(theme.text(), theme.muted(), 0.4f));
		g2.drawString(text, x + 6, y - 7);
	}

	// ---- tables ------------------------------------------------------------------------------------

	// Level-of-detail knobs. LOD only kicks in once a schema is big enough to need it — below LOD_MIN_TABLES
	// every table renders in full detail at any zoom (a handful of tables is cheap, so nothing should ever
	// disappear). Above it, the zoom thresholds (device px per world unit) drop work that isn't visible
	// anyway: below NAME_ZOOM the box is too small to read the name; below COLUMN_ZOOM the rows are; the soft
	// shadow is invisible below SHADOW_ZOOM; below EDGE_ZOOM the relationship web is noise over tiny boxes, so
	// only the boxes (and any highlighted edges) draw. Tune these to taste.
	private static final int LOD_MIN_TABLES = 60;
	private static final double EDGE_ZOOM = 0.3;
	private static final double NAME_ZOOM = 0.33;
	private static final double COLUMN_ZOOM = 0.5;
	private static final double SHADOW_ZOOM = 0.6;

	// The zoom value LOD should react to: the real device zoom for large schemas, or MAX (= always full
	// detail) for small ones that don't need simplifying.
	private static double lodZoom(Scene scene, Graphics2D g2) {
		return scene.tables().size() > LOD_MIN_TABLES ? g2.getTransform().getScaleX() : Double.MAX_VALUE;
	}

	/**
	 * A cheap low-geometry pass: each box as its surface rounded-rect, header strip, and hairline only — no
	 * glow, shadow, text, or columns. Painted under the detailed buffer so a box panned or zoomed into view
	 * shows its structure straight away instead of popping in from nothing; the buffer covers it where baked,
	 * and the settle re-bake resolves it to full detail. Only boxes intersecting {@code clip} are drawn.
	 */
	void drawSilhouettes(Graphics2D g2, Scene scene, Rectangle2D clip) {
		Stroke prevStroke = g2.getStroke();
		g2.setStroke(new BasicStroke(1f));
		for (EntityBox table : scene.tables()) {
			Rectangle2D r = table.bounds();
			if (clip != null && !clip.intersects(r)) {
				continue;
			}
			int x = (int) r.getX();
			int y = (int) r.getY();
			int w = (int) r.getWidth();
			int h = (int) r.getHeight();
			g2.setColor(theme.surface());
			g2.fillRoundRect(x, y, w, h, BoxMetrics.CORNER, BoxMetrics.CORNER);
			g2.setColor(table.isView() ? theme.viewHeader() : theme.entityHeader());
			g2.fillRoundRect(x, y, w, BoxMetrics.HEADER_HEIGHT, BoxMetrics.CORNER, BoxMetrics.CORNER);
			g2.fillRect(x, y + BoxMetrics.HEADER_HEIGHT - BoxMetrics.CORNER, w, BoxMetrics.CORNER);
			g2.setColor(theme.line());
			g2.drawRoundRect(x, y, w, h, BoxMetrics.CORNER, BoxMetrics.CORNER);
		}
		g2.setStroke(prevStroke);
	}

	private void paintTable(Graphics2D g2, EntityBox table, boolean active, EntityBox hovered, String focus,
			double zoom) {
		Rectangle2D r = table.bounds();
		int x = (int) r.getX();
		int y = (int) r.getY();
		int w = (int) r.getWidth();
		int h = (int) r.getHeight();

		Composite previous = g2.getComposite();
		if (!active) {
			g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.22f));
		}

		if (!lightweight) {
			paintHubGlow(g2, table, x, y, w, h);   // the centrality glow — decorative overdraw, dropped in lightweight
		}
		if (zoom >= SHADOW_ZOOM && !lightweight) {
			paintSoftShadow(g2, x, y, w, h);
		}

		g2.setColor(theme.surface());
		g2.fillRoundRect(x, y, w, h, BoxMetrics.CORNER, BoxMetrics.CORNER);
		// Views get a dodger-blue header so they read clearly apart from the brand-coloured tables.
		g2.setColor(table.isView() ? theme.viewHeader() : theme.entityHeader());
		g2.fillRoundRect(x, y, w, BoxMetrics.HEADER_HEIGHT, BoxMetrics.CORNER, BoxMetrics.CORNER);
		g2.fillRect(x, y + BoxMetrics.HEADER_HEIGHT - BoxMetrics.CORNER, w, BoxMetrics.CORNER);

		if (zoom >= NAME_ZOOM) {
			g2.setColor(Color.WHITE);
			g2.setFont(titleFont);
			// Ellipsise so the name never runs under the glyph, even if the box width was capped elsewhere.
			int titleX = x + BoxMetrics.TEXT_PADDING;
			int maxTitleWidth = (x + w - BoxMetrics.KIND_GLYPH_GUTTER) - titleX;
			g2.drawString(ellipsise(g2, table.name(), maxTitleWidth), titleX, y + BoxMetrics.HEADER_HEIGHT - 9);
			paintKindGlyph(g2, table.isView(), x + w - 18, y + BoxMetrics.HEADER_HEIGHT / 2);
		}
		if (zoom >= COLUMN_ZOOM) {
			paintColumns(g2, table, x, y, w, h);
		}

		boolean accent = table == hovered || table.name().equalsIgnoreCase(focus);
		g2.setColor(accent ? theme.entityHeader() : theme.line());
		g2.setStroke(new BasicStroke(accent ? 2f : 1f));
		g2.drawRoundRect(x, y, w, h, BoxMetrics.CORNER, BoxMetrics.CORNER);

		g2.setComposite(previous);
	}

	// A coloured "spotlight" halo whose size and intensity scale with the table's centrality, so the
	// schema's hub tables glow and leaf tables stay clean — teaching structure at a glance. Concentric
	// translucent rounded rects, brightest against the box edge; the opaque box on top leaves only the
	// ring showing. Baked into the static buffer (cheap; ~5 fills per hub table).
	private void paintHubGlow(Graphics2D g2, EntityBox table, int x, int y, int w, int h) {
		double centrality = table.centrality();
		if (centrality <= 0.05) {
			return;   // leaf / lightly-linked tables get no glow
		}
		double strength = centrality * centrality;   // bias the glow toward the most-connected tables
		Color base = table.isView() ? theme.viewHeader() : theme.entityHeader();
		int layers = 5;
		int maxGrow = (int) Math.round(6 + strength * 16);   // 6..22px halo
		for (int layer = layers; layer >= 1; layer--) {
			int grow = Math.max(1, maxGrow * layer / layers);
			int a = (int) Math.round(strength * 30 * (1.0 - (layer - 1.0) / layers));   // fainter further out
			g2.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), Math.max(0, Math.min(255, a))));
			g2.fillRoundRect(x - grow, y - grow, w + grow * 2, h + grow * 2,
					BoxMetrics.CORNER + grow, BoxMetrics.CORNER + grow);
		}
	}

	// A fuzzy drop shadow faked by stacking growing, translucent rounded rects (a cheap penumbra). The
	// box is painted opaque on top, so only the soft halo around/below it shows.
	private void paintSoftShadow(Graphics2D g2, int x, int y, int w, int h) {
		int drop = 2;
		int layerAlpha = theme.isDark() ? 11 : 6;   // low alpha per layer; the overlap is the penumbra
		for (int layer = 6; layer >= 1; layer--) {
			int grow = layer;                      // tight spread — small but soft
			g2.setColor(new Color(0, 0, 0, layerAlpha));
			g2.fillRoundRect(x - grow, y - grow + drop, w + grow * 2, h + grow * 2,
					BoxMetrics.CORNER + grow, BoxMetrics.CORNER + grow);
		}
	}

	// Truncates text with a trailing ellipsis so it fits within maxWidth pixels in the current font.
	private static String ellipsise(Graphics2D g2, String text, int maxWidth) {
		FontMetrics fm = g2.getFontMetrics();
		if (maxWidth <= 0 || fm.stringWidth(text) <= maxWidth) {
			return text;
		}
		int ellipsis = fm.stringWidth("…");
		int end = text.length();
		while (end > 0 && fm.stringWidth(text.substring(0, end)) + ellipsis > maxWidth) {
			end--;
		}
		return text.substring(0, end) + "…";
	}

	// A white header glyph telling tables from views apart: a small grid for a table, an eye for a view.
	private void paintKindGlyph(Graphics2D g2, boolean view, int cx, int cy) {
		g2.setColor(Color.WHITE);
		g2.setStroke(new BasicStroke(1.3f));
		if (view) {
			g2.drawOval(cx - 6, cy - 4, 12, 8);     // eye outline
			g2.fillOval(cx - 2, cy - 2, 4, 4);       // pupil
		}
		else {
			g2.drawRect(cx - 6, cy - 5, 12, 10);     // table frame
			g2.drawLine(cx - 6, cy - 1, cx + 6, cy - 1);   // header rule
			g2.drawLine(cx, cy - 1, cx, cy + 5);           // column divider
		}
	}

	// Draws the column rows clipped to the box body, offset by its scroll, plus a scrollbar thumb.
	private void paintColumns(Graphics2D g2, EntityBox table, int x, int y, int w, int h) {
		int viewportTop = y + BoxMetrics.HEADER_HEIGHT;
		int viewportHeight = h - BoxMetrics.HEADER_HEIGHT - BoxMetrics.BOTTOM_PADDING;
		Shape oldClip = g2.getClip();
		g2.clipRect(x, viewportTop, w, viewportHeight);
		g2.setFont(rowFont);
		int offset = (int) table.scrollOffset();
		List<Field> columns = table.columns();
		for (int i = 0; i < columns.size(); i++) {
			int rowTop = viewportTop - offset + i * BoxMetrics.ROW_HEIGHT;
			if (rowTop + BoxMetrics.ROW_HEIGHT >= viewportTop && rowTop <= viewportTop + viewportHeight) {
				paintColumn(g2, columns.get(i), x, rowTop, w);
			}
		}
		g2.setClip(oldClip);
		paintScrollThumb(g2, table, x, viewportTop, w, viewportHeight);
	}

	private void paintScrollThumb(Graphics2D g2, EntityBox table, int x, int viewportTop, int w, int viewportHeight) {
		double content = table.columns().size() * BoxMetrics.ROW_HEIGHT;
		if (content <= viewportHeight) {
			return;   // everything fits; no scrollbar
		}
		int thumbHeight = (int) Math.max(18, viewportHeight * (viewportHeight / content));
		int travel = viewportHeight - thumbHeight;
		int thumbTop = viewportTop + (int) (travel * (table.scrollOffset() / table.maxScroll()));
		g2.setColor(theme.muted());
		g2.fillRoundRect(x + w - 6, thumbTop, 4, thumbHeight, 4, 4);
	}

	private void paintColumn(Graphics2D g2, Field column, int x, int rowTop, int width) {
		int baseline = rowTop + BoxMetrics.ROW_HEIGHT - 7;
		paintKeyBadge(g2, column, x + BoxMetrics.TEXT_PADDING + 4, rowTop + BoxMetrics.ROW_HEIGHT / 2);
		g2.setColor(column.primaryKey() ? theme.entityHeader() : theme.text());
		g2.drawString(column.name(), x + BoxMetrics.TEXT_PADDING + BoxMetrics.BADGE_GUTTER, baseline);
		if (!column.type().isEmpty()) {
			g2.setColor(theme.muted());
			int typeX = x + width - BoxMetrics.TEXT_PADDING - g2.getFontMetrics(rowFont).stringWidth(column.type());
			g2.drawString(column.type(), typeX, baseline);
		}
	}

	// Painted key icons (robust across fonts): a solid brand key for a PK, a lighter outline key for an FK.
	private void paintKeyBadge(Graphics2D g2, Field column, int cx, int cy) {
		if (column.primaryKey()) {
			paintKey(g2, cx, cy, theme.entityHeader(), true);
		}
		else if (column.foreignKey()) {
			paintKey(g2, cx, cy, brandSoft(), false);
		}
	}

	// A small key: a round bow on the left, a shaft to the right, and two teeth.
	private void paintKey(Graphics2D g2, int cx, int cy, Color colour, boolean filled) {
		g2.setColor(colour);
		g2.setStroke(new BasicStroke(1.3f));
		int bowCx = cx - 3;
		int bow = 3;
		if (filled) {
			g2.fillOval(bowCx - bow, cy - bow, bow * 2, bow * 2);
		}
		else {
			g2.drawOval(bowCx - bow, cy - bow, bow * 2, bow * 2);
		}
		g2.drawLine(bowCx + bow, cy, cx + 6, cy);   // shaft
		g2.drawLine(cx + 6, cy, cx + 6, cy + 3);    // tip tooth
		g2.drawLine(cx + 3, cy, cx + 3, cy + 3);    // inner tooth
	}

	// ---- edge end decorations ----------------------------------------------------------------------

	private void paintEndDecorations(Graphics2D g2, EdgeGeometry edge) {
		paintCrowsFoot(g2, edge.start(), edge.startSide());   // many, at the FK end
		paintOne(g2, edge.end(), edge.endSide());            // one, at the PK end
	}

	// Three prongs fanning from the curve's setback point (one crow's-foot length out along the side normal)
	// onto the box border, spread along the border tangent.
	private void paintCrowsFoot(Graphics2D g2, Point2D atBox, EdgeRouter.Side side) {
		double spread = 6;
		double baseX = atBox.getX() + side.ox * BoxMetrics.CROWS_FOOT_LENGTH;
		double baseY = atBox.getY() + side.oy * BoxMetrics.CROWS_FOOT_LENGTH;
		double tx = side.horizontal() ? 0 : 1;   // border tangent, perpendicular to the outward normal
		double ty = side.horizontal() ? 1 : 0;
		drawLine(g2, baseX, baseY, atBox.getX(), atBox.getY());
		drawLine(g2, baseX, baseY, atBox.getX() + tx * spread, atBox.getY() + ty * spread);
		drawLine(g2, baseX, baseY, atBox.getX() - tx * spread, atBox.getY() - ty * spread);
	}

	// "One": a stub from the curve's setback point to the box, with a bar crossing its middle.
	private void paintOne(Graphics2D g2, Point2D atBox, EdgeRouter.Side side) {
		double baseX = atBox.getX() + side.ox * BoxMetrics.CROWS_FOOT_LENGTH;
		double baseY = atBox.getY() + side.oy * BoxMetrics.CROWS_FOOT_LENGTH;
		drawLine(g2, baseX, baseY, atBox.getX(), atBox.getY());   // stub: setback → box border
		double barX = atBox.getX() + side.ox * (BoxMetrics.CROWS_FOOT_LENGTH / 2.0);
		double barY = atBox.getY() + side.oy * (BoxMetrics.CROWS_FOOT_LENGTH / 2.0);
		double tx = side.horizontal() ? 0 : 1;
		double ty = side.horizontal() ? 1 : 0;
		double half = 6;
		drawLine(g2, barX - tx * half, barY - ty * half, barX + tx * half, barY + ty * half);
	}

	private static void drawLine(Graphics2D g2, double x1, double y1, double x2, double y2) {
		g2.drawLine((int) x1, (int) y1, (int) x2, (int) y2);
	}
}
