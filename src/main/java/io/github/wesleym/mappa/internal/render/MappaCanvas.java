package io.github.wesleym.mappa.internal.render;

import io.github.wesleym.mappa.internal.common.Fonts;
import io.github.wesleym.mappa.internal.common.LightweightMode;
import io.github.wesleym.mappa.internal.common.ScrollAxisLock;
import io.github.wesleym.mappa.internal.common.WheelStream;
import io.github.wesleym.mappa.internal.community.CommunityNames;
import io.github.wesleym.mappa.internal.layout.EdgeRouter;
import io.github.wesleym.mappa.internal.layout.EdgeStyle;
import io.github.wesleym.mappa.internal.layout.LabelLayout;
import io.github.wesleym.mappa.internal.layout.LayoutStyle;
import io.github.wesleym.mappa.internal.layout.JoinPath;
import io.github.wesleym.mappa.internal.model.Link;
import io.github.wesleym.mappa.internal.model.Scene;
import io.github.wesleym.mappa.internal.model.SceneBuilder;
import io.github.wesleym.mappa.internal.model.BoxMetrics;
import io.github.wesleym.mappa.internal.model.EntityBox;
import io.github.wesleym.mappa.internal.common.Style;
import io.github.wesleym.mappa.MappaMap;
import io.github.wesleym.mappa.MappaMinimap;
import io.github.wesleym.mappa.MappaTheme;
import io.github.wesleym.mappa.MappaView;

import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.TexturePaint;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.IntStream;

/**
 * The interactive surface for the ER diagram: owns the viewport (pan/zoom/fit) and pointer interaction
 * (drag a table, scroll its columns, hover to highlight, right-click for the host's actions). The scene model, layout,
 * edge routing, and painting live in {@link Scene}, {@link SceneBuilder},
 * {@link EdgeRouter}, and {@link SceneRenderer}; this class just wires them to the screen.
 */
final class MappaCanvas extends JComponent {

	private static final double MIN_SCALE = 0.05;
	private static final double MAX_SCALE = 3.0;
	private static final double ZOOM_STEP = 1.1;
	private static final int SIDEWAYS_PAN_UNIT = 26;   // px per wheel unit when a sideways scroll pans the canvas
	private static final double FIT_MARGIN = 48;
	private static final double FOCUS_MAX_SCALE = 1.25;   // clicking a table never zooms in closer than this
	private static final double FOCUS_MAX_NEIGHBOUR_DIAGONAL = 3.5;
	private static final double FOCUS_MIN_NEIGHBOUR_DIAGONAL = 900;
	private static final int FOCUS_MAX_NEIGHBOURS = 10;
	private static final double CAMERA_JUMP_VIEWPORTS = 1.8;
	private static final double CAMERA_JUMP_SCALE_RATIO = 2.4;
	private static final int EXPORT_MARGIN = 24;
	private static final double EXPORT_MAX_PIXELS = 48_000_000;   // cap export image to ~48 MP (~192 MB) so it can't OOM
	// Render the PNG above 1:1 so it stays crisp when zoomed in or printed (text/edges are vector and sharpen at
	// scale). This is the "standard" (1×) supersample; the export dialog can multiply it for a higher-resolution
	// PNG. Bounded by the pixel budget above, so a large schema scales back toward 1:1 instead of OOM-ing.
	private static final double EXPORT_SUPERSAMPLE = 3.0;
	private static final int CLICK_SLOP = 4;   // pointer move under this (px) counts as a click, not a drag
	private static final int MINIMAP_MIN_TABLES = 20;   // the overview minimap appears once a diagram is this large
	private static final int MINIMAP_W = 208;
	private static final int MINIMAP_H = 148;
	private static final int MINIMAP_MARGIN = 16;
	private static final int MINIMAP_PAD = 10;
	private static final int GRID_SPACING = 26;   // muted dot grid backdrop

	private final MappaTheme theme;
	private SceneRenderer renderer;
	private Font titleFont;
	private Font rowFont;
	private final JPopupMenu tableMenu = new JPopupMenu();
	private final Runnable lightweightListener = this::onLightweightChanged;

	private MappaMap graph;             // last map set, kept so a label toggle can re-lay-out
	private boolean inferredOnly;       // show only the suggested (inferred) relationships, hiding declared ones
	private boolean showJoinColumns;    // off by default — join-column edge labels can be distracting
	private boolean keysOnly = true;    // on by default — show only key columns until the user asks for all
	private LayoutStyle layoutStyle = LayoutStyle.LAYERED;   // how each cluster is arranged
	private EdgeStyle edgeStyle = EdgeStyle.CURVED;          // how each edge is drawn
	private Scene scene = Scene.empty();
	// The Sugiyama layout (SceneBuilder.build) is the diagram's one heavy computation. It runs on this
	// single-thread executor, never the EDT, so opening a large schema or toggling keys-only doesn't freeze the
	// UI. buildGeneration discards a layout whose inputs were superseded before it finished; building drives the
	// "laying out…" hint while the first scene is still empty.
	private final ExecutorService layoutExecutor =
			Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "diagram-layout"); t.setDaemon(true); return t; });
	private int buildGeneration;
	private boolean building;
	private boolean lightweightListening;
	private volatile boolean disposed;
	private SceneBuilder.TextWidth textWidth;
	// Edge routing/splines are cached and only recomputed when tables move — not on every paint frame.
	private List<EdgeRouter.EdgeGeometry> geometry = List.of();
	private List<Path2D> paths = List.of();
	// Flattened polylines for the paths, cached so the comet animation never re-flattens per frame.
	private List<double[][]> flatPaths = List.of();
	// Per-edge bounding boxes, cached so the banded re-bake doesn't recompute getBounds2D() per edge per band.
	private List<Rectangle2D> edgeBounds = List.of();
	private boolean geometryDirty = true;
	// Placed join labels by edge index, resolved by the force pass when labels are on (empty otherwise).
	private Map<Integer, LabelLayout.Placed> joinLabelRects = Map.of();
	// The static scene is rendered once into this buffer; animation frames just blit it and draw the
	// moving part on top (particles when hovering, the dragged table + its edges when dragging).
	private BufferedImage buffer;
	private boolean bufferDirty = true;
	// What the buffer was baked at — so a pan within the margin re-blits the cached pixels instead of redrawing.
	private double bufferPanX;
	private double bufferPanY;
	private double bufferScale;
	private double bufferDpr;
	private static final int BUFFER_MARGIN = 256;   // floor for the logical-px halo baked around the viewport
	private static final int BUFFER_MARGIN_MAX = 720;   // cap so a maximised window doesn't allocate an enormous buffer
	private Backdrop background = Backdrop.DOTS;   // backdrop pattern behind the diagram
	private BufferedImage gridTile;       // cached one-cell tile for the backdrop TexturePaint
	private Backdrop gridTileStyle;   // pattern the tile was built for, so it rebuilds on a switch
	private boolean gridTileDark;         // theme the tile was built for, so it rebuilds on light/dark switch
	private int draggingIndex = -1;
	private double scale = 1.0;
	private double offsetX;
	private double offsetY;
	private boolean navigatingMinimap;   // the pointer is dragging inside the overview minimap
	private MappaMinimap minimap = MappaMinimap.AUTO;
	private static final double FOLLOW_EASE = 0.20;   // per-frame glide toward a minimap target — cinematic, not a snap
	private double followTargetX;
	private double followTargetY;
	private final Timer cameraFollow = new Timer(16, e -> stepCameraFollow());
	private final ScrollAxisLock axisLock = new ScrollAxisLock();   // keeps a zoom gesture from wobbling sideways
	// Wheel-device stream, used ONLY to pick zoom smoothing (glide a notch, apply fractional deltas
	// direct) — both branches zoom, so a misclassified high-resolution mouse wheel still behaves.
	private final WheelStream wheelDevice = new WheelStream();
	// Glides discrete zoom steps (mouse notches, the zoom buttons) toward an accumulating target scale
	// anchored at a pivot, the same approach-per-frame model the scroll panes use for notch glides.
	private final Timer zoomGlide = new Timer(8, e -> tickZoomGlide());
	private static final double ZOOM_EASE = 0.35;   // per-frame approach toward the target scale
	private double zoomTargetScale;
	private Point zoomPivot = new Point();
	private boolean fitPending;

	private Point lastDragPoint;        // set while panning the canvas
	private double grabOffsetX;         // table centre minus grab point, in world units
	private double grabOffsetY;
	private EntityBox active;        // clicked table — lit up with its relationships + flowing edges
	private EntityBox pressedTable;  // table the mouse went down on (to tell a click from a drag)
	private Point pressPoint;
	private EntityBox menuTable;     // table the context menu was opened on
	private String focusTable;          // single-table exploration subject, drawn with an accent
	// A traced join path: the edge indices on the shortest route between two shift-clicked tables, lit and
	// flowing over the (undimmed) scene; empty when no path is being traced.
	private List<Integer> pathEdges = List.of();
	private int pathFrom = -1;
	private int pathTo = -1;
	// User-pinned join labels (edge index → world point): the label sits here and its edge bends through it.
	private final Map<Integer, Point2D> labelOverrides = new HashMap<>();
	private int draggingLabel = -1;     // edge index of the label being dragged, or -1
	private double labelGrabX;          // label centre minus grab point, in world units
	private double labelGrabY;
	private Consumer<String> onActiveTable = name -> { };   // notified with the selected table (or null)
	private Consumer<MappaMap> onArranged = m -> { };       // notified with the positioned map after a box drag
	private Runnable onViewChanged = () -> { };             // fired when the viewport pans/zooms/refits
	private List<MappaView.EntityAction> entityActions = List.of();   // host actions for the right-click menu
	// Drives the flowing-particle animation along the active table's edges; only ticks while one is active.
	// The phase advances by real elapsed time, not by tick count: Swing timers jitter under EDT load, and a
	// late tick that still advanced a full step read as a stutter (most visibly on the dash arrow heads).
	// Clamped so a long stall (or the first tick after a pause) glides on rather than leaping.
	private double flowPhase;
	private long flowTickNanos;
	private final Timer flowTimer = new Timer(33, e -> {
		long now = System.nanoTime();
		double ticks = flowTickNanos == 0 ? 1.0 : Math.min(3.0, (now - flowTickNanos) / 33_000_000.0);
		flowTickNanos = now;
		flowPhase += ticks;   // gentle drift
		repaint();
	});
	// Coalesces rapid pan/zoom into one crisp re-bake: while panning/zooming we blit the cached buffer
	// transformed (instant), and only re-render the scene once motion has paused for a beat.
	private final Timer viewSettle = new Timer(110, e -> {
		bufferDirty = true;   // re-bake crisp once a pan/zoom stops; the blit shows the moved view until then
		updateFlowTimer();    // the visible lit-edge count changed with the view — re-gate the flow animation
		repaint();
	});
	// A resize/sidebar-drag is handled differently: trying to redraw the diagram mid-drag flickers, jumps and
	// glitches no matter how it's blitted, so while resizing we cross-fade to a calm placeholder and only
	// re-render the real diagram once the drag has been still for a good beat (debounced) — no rush, rock-steady.
	private static final int RESIZE_DEBOUNCE_MS = 250;   // wait this long after the last resize event before redrawing
	private boolean resizing;
	private final Timer resizeRedraw = new Timer(RESIZE_DEBOUNCE_MS, e -> {
		resizing = false;
		computeFit();             // frame the diagram to the settled size
		bufferDirty = true;       // re-bake it crisp — one clean render, no mid-drag churn
		placeholderTarget = 0f;   // …then fade the placeholder out, revealing the freshly drawn diagram
		startFade();
		repaint();
		onViewChanged.run();      // anchored chrome (note) follows the new frame
	});
	// Cross-fade between the diagram and the placeholder: 0 = diagram shown, 1 = placeholder fully covering it.
	private float placeholderAlpha;
	private float placeholderTarget;
	private final Timer placeholderFade = new Timer(16, e -> stepFade());
	// Opening a diagram fires a burst of layout-settling resize events; we must NOT flash the placeholder for those.
	// The placeholder arms a fixed beat after the diagram first paints — NOT restarted by resizes, so a continuous
	// resize that begins before it arms still flips to the placeholder on time (an idle-based arm could starve and
	// leave every frame doing a full re-bake — the sluggishness). Until armed, the open's resizes just render.
	private boolean resizeArmed;
	private boolean armScheduled;
	private final Timer armResize = new Timer(400, e -> resizeArmed = true);
	private Timer cameraTimer;   // eases the viewport when framing a clicked table's neighbourhood
	private double cameraTargetScale;
	private double cameraTargetX;
	private double cameraTargetY;

	MappaCanvas(MappaTheme theme) {
		this.theme = theme;
		setOpaque(true);
		// Match the backdrop so the EDT's clear-before-paint (and any region exposed mid-resize) is the diagram's
		// own colour, not the look-and-feel grey — that grey flash is what reads as flicker while resizing.
		setBackground(theme.background());
		refreshFontsAndRenderer();
		viewSettle.setRepeats(false);
		resizeRedraw.setRepeats(false);
		armResize.setRepeats(false);
		rebuildMenu();
		installInteraction();
	}

	// Lightweight mode toggled: drop the diagram's smarts/decoration (grid layout, straight edges, plain backdrop,
	// no glow/shadow/community/flow) or restore them. Re-lay-out and re-bake once.
	private void onLightweightChanged() {
		renderer.setLightweight(LightweightMode.isOn());
		updateFlowTimer();   // stop the flow animation when entering lightweight (or resume if leaving)
		if (graph != null) {
			rebuildScene(false);   // the effective layout (grid vs the user's pick) changes
		}
		else {
			bufferDirty = true;
			repaint();
		}
	}

	/** The table to highlight as the subject of a single-table exploration (null for the full diagram). */
	void setFocusTable(String focusTable) {
		this.focusTable = focusTable;
	}

	/** Called with the selected (clicked) table's name (or null when the selection is cleared). */
	void setActiveHandler(Consumer<String> onActiveTable) {
		this.onActiveTable = onActiveTable;
	}

	/** Notified with the positioned map whenever the user drags a box — the arrangement to persist. */
	void setArrangedHandler(Consumer<MappaMap> onArranged) {
		this.onArranged = onArranged == null ? m -> { } : onArranged;
	}

	/** Called whenever the viewport changes (pan, zoom, fit, table drag) so anchored chrome can follow. */
	void setViewChangedHandler(Runnable onViewChanged) {
		this.onViewChanged = onViewChanged;
	}

	/** The host actions offered on a table's right-click menu, in order; a null-handler entry is a divider. */
	void setEntityActions(List<MappaView.EntityAction> actions) {
		this.entityActions = actions == null ? List.of() : List.copyOf(actions);
		rebuildMenu();
	}

	/** Visible for testing: the table right-click menu, so its items can be asserted. */
	JPopupMenu tableMenu() {
		return tableMenu;
	}

	/** Rebuilds and re-lays-out the diagram for {@code map}; once laid out, the next paint fits it to the viewport. */
	void setMap(MappaMap map) {
		this.graph = map;
		refreshFontsAndRenderer();   // the bundled-vs-fallback font choice is per map (glyph coverage)
		rebuildScene(true);
	}

	/** The map currently shown — lets the view resolve AUTO options (detail, layout) against its size. */
	MappaMap map() {
		return graph;
	}

	// The map keeping only its suggested (inferred) relationships — declared ones dropped for "only inferred".
	private static MappaMap suggestedOnly(MappaMap map) {
		return new MappaMap(map.title(), map.entities(),
				map.relationships().stream().filter(MappaMap.Relationship::suggested).toList());
	}

	/** Toggles the join-column edge labels; re-lays-out so the diagram makes room for (or reclaims) them. */
	void setShowJoinColumns(boolean show) {
		if (show == showJoinColumns) {
			return;
		}
		showJoinColumns = show;
		if (graph != null) {
			// The layer gap changes, so the layout must actually re-run. A hand-arrangement (saved box centres)
			// would otherwise pin the boxes and skip the layout entirely, defeating the toggle — so drop it.
			graph = graph.withPositions(java.util.Map.of());
			rebuildScene(false);
		}
	}

	/** Renders only key columns (PK/FK, plus inferred FKs when inference is on) when true, or every column when
	 *  false; re-lays-out since the box heights change. */
	void setKeysOnly(boolean keysOnly) {
		if (keysOnly == this.keysOnly) {
			return;
		}
		this.keysOnly = keysOnly;
		if (graph != null) {
			// Box heights change wholesale, so a hand-arrangement no longer fits — replaying its saved centres
			// would overlap the resized boxes and skip the community regions. Drop it and re-lay-out cleanly.
			graph = graph.withPositions(java.util.Map.of());
			rebuildScene(false);
		}
	}

	/** The current per-cluster placement algorithm. */
	LayoutStyle layoutStyle() {
		return layoutStyle;
	}

	/** Switches how each cluster is arranged; re-runs the layout (off the EDT), keeping the current view. */
	void setLayoutStyle(LayoutStyle style) {
		if (style == null || style == layoutStyle) {
			return;
		}
		layoutStyle = style;
		if (graph != null) {
			graph = graph.withPositions(java.util.Map.of());   // a new layout style discards the hand-arrangement
			rebuildScene(true);   // positions change wholesale — refit so the new arrangement is framed
		}
	}

	/** The current edge wire style. */
	EdgeStyle edgeStyle() {
		return edgeStyle;
	}

	/** Switches how edges are drawn; just re-strokes the cached routes (no relayout) and re-bakes. */
	void setEdgeStyle(EdgeStyle style) {
		if (style == null || style == edgeStyle) {
			return;
		}
		edgeStyle = style;
		renderer.setDirectionalEdges(style == EdgeStyle.DIRECTIONAL);
		geometryDirty = true;   // the routes are the same; only the path shape through them changes
		bufferDirty = true;
		repaint();
	}

	/** The current backdrop pattern. */
	Backdrop backgroundStyle() {
		return background;
	}

	/** Switches the backdrop pattern; just re-bakes (the tile rebuilds on the next paint). */
	void setBackgroundStyle(Backdrop style) {
		if (style == null || style == background) {
			return;
		}
		background = style;
		bufferDirty = true;
		repaint();
	}

	// Lays the scene out off the EDT and applies the result back on it. Snapshots the inputs now (they're EDT
	// state), runs the layout on the layout thread, then hands the finished scene to applyScene on the EDT. The
	// old scene stays on screen until the new one is ready, so a re-layout never blanks the diagram.
	private void rebuildScene(boolean refit) {
		if (graph == null || disposed) {
			return;
		}
		int gen = ++buildGeneration;
		// "Only inferred" drops the declared relationships from the laid-out graph so just the inferred edges draw;
		// the columns' own FK badges are untouched (they come from the column flags, not these edges).
		MappaMap g = inferredOnly ? suggestedOnly(graph) : graph;
		boolean joins = showJoinColumns;
		boolean kOnly = keysOnly;
		// Lightweight: a cheap grid (skip the Sugiyama/force/community work), the user's pick otherwise.
		LayoutStyle style = LightweightMode.isOn() ? LayoutStyle.GRID : layoutStyle;
		building = true;
		repaint();   // show the "laying out…" hint while the first scene is still empty
		try {
			layoutExecutor.execute(() -> {
				Scene built;
				try {
					built = SceneBuilder.build(g, titleFont, rowFont, textWidth, joins, true, kOnly, style);
				}
				catch (Throwable failed) {
					built = null;   // a layout failure shouldn't kill the thread; keep the previous scene
				}
				Scene result = built;
				SwingUtilities.invokeLater(() -> applyScene(result, gen, refit));
			});
		}
		catch (RejectedExecutionException closed) {
			building = false;
			repaint();
		}
	}

	private void applyScene(Scene built, int gen, boolean refit) {
		if (disposed || gen != buildGeneration) {
			return;   // a newer rebuild has been requested — that one owns the state and the spinner
		}
		building = false;
		if (built == null) {
			repaint();   // this layout failed; leave the previous scene in place
			return;
		}
		scene = built;
		updateClusterNames();             // name the communities from their tables for the region headers
		pathEdges = List.of();   // table indices changed — any traced path is now stale
		pathFrom = -1;
		pathTo = -1;
		labelOverrides.clear();            // edge indices changed — pinned labels no longer map cleanly
		geometryDirty = true;
		bufferDirty = true;
		if (refit) {
			fitPending = true;
		}
		repaint();
	}

	// Label each community from its tables, weighting the hub (most FK-connected) tables and excluding connectors.
	// All communities are named together so a token common to the whole schema can be filtered out.
	private void updateClusterNames() {
		// Regions aren't drawn in lightweight mode — skip the naming heuristics entirely.
		renderer.setClusterNames(LightweightMode.isOn() ? Map.of() : StaticRender.clusterNames(scene));
	}

	/** When true, hides the declared (existing) FK edges and draws only the inferred ones; re-lays-out. */
	void setInferredOnly(boolean inferredOnly) {
		if (inferredOnly == this.inferredOnly) {
			return;
		}
		this.inferredOnly = inferredOnly;
		if (graph != null) {
			rebuildScene(false);
		}
	}

	/** Discards the cached buffer so the next paint re-renders with the current theme colours. */
	void refreshTheme() {
		setBackground(theme.background());
		boolean metricsChanged = refreshFontsAndRenderer();
		gridTile = null;   // the backdrop tile's faint tone is theme.muted()-derived; a same-darkness switch
		                   // changes muted() without flipping the dark flag, so force a rebuild on next paint
		bufferDirty = true;
		if (metricsChanged && graph != null) {
			rebuildScene(false);
			return;
		}
		repaint();
	}

	private boolean refreshFontsAndRenderer() {
		StaticRender.DiagramFonts fonts = StaticRender.DiagramFonts.of(graph);
		Font nextTitle = fonts.title();
		Font nextRow = fonts.row();
		boolean changed = renderer == null || !nextTitle.equals(titleFont) || !nextRow.equals(rowFont);
		setFont(Style.unscaledFont(Style.BODY, Font.PLAIN));
		if (!changed) {
			return false;
		}
		titleFont = nextTitle;
		rowFont = nextRow;
		renderer = new SceneRenderer(theme, titleFont, rowFont);
		renderer.setDirectionalEdges(edgeStyle == EdgeStyle.DIRECTIONAL);
		renderer.setLightweight(LightweightMode.isOn());
		// Capture the two font metrics on the EDT and measure through them from the layout thread. FontMetrics is
		// resolved here (the metrics cache is warmed below), so the background layout never touches the component's
		// metrics machinery concurrently with painting; stringWidth on the captured instances is a pure read.
		FontMetrics titleMetrics = getFontMetrics(titleFont);
		FontMetrics rowMetrics = getFontMetrics(rowFont);
		titleMetrics.stringWidth("Mg");   // force the Latin-1 advance cache to populate now, on the EDT
		rowMetrics.stringWidth("Mg");
		textWidth = (text, font) -> (font == titleFont ? titleMetrics : rowMetrics).stringWidth(text);
		if (scene != null && !scene.tables().isEmpty()) {
			updateClusterNames();
		}
		return true;
	}

	/** Re-routes edges into the cache; cheap to call but only does work after tables move. */
	private void ensureGeometry() {
		if (!geometryDirty) {
			return;
		}
		geometry = EdgeRouter.route(scene);
		// Lightweight: straight segments — far cheaper to stroke than splines — instead of the chosen style.
		EdgeStyle es = LightweightMode.isOn() ? EdgeStyle.STRAIGHT : edgeStyle;
		List<Path2D> base = new ArrayList<>(geometry.size());
		for (EdgeRouter.EdgeGeometry edge : geometry) {
			base.add(es.path(edge.waypoints(), edge.startHorizontal(), edge.endHorizontal()));
		}
		// Labels off (the default) → routing untouched. A user-dragged label pins its edge: bend the route
		// through the pinned point so the edge follows the hint. The force pass then spreads the auto labels.
		if (showJoinColumns) {
			for (Map.Entry<Integer, Point2D> override : labelOverrides.entrySet()) {
				int i = override.getKey();
				if (i >= 0 && i < base.size()) {
					base.set(i, bentPath(geometry.get(i), override.getValue()));
				}
			}
		}
		paths = base;
		List<double[][]> flats = new ArrayList<>(base.size());
		List<Rectangle2D> bounds = new ArrayList<>(base.size());
		double flatness = flattenFlatness();
		for (Path2D p : base) {
			flats.add(SceneRenderer.flatten(p, flatness));
			bounds.add(p.getBounds2D());   // cached once; the banded re-bake reuses it instead of recomputing per band
		}
		flattenedFor = effectiveScale();
		flatPaths = flats;
		edgeBounds = bounds;
		joinLabelRects = showJoinColumns && draggingIndex < 0
				? LabelLayout.layout(scene, base, getFontMetrics(rowFont), labelOverrides)
				: Map.of();
		geometryDirty = false;
	}

	// Bends an edge's route through a user-pinned point (inserted at the route's middle), then splines it,
	// keeping the edge's own attach-side tangents so it still leaves/enters its tables cleanly.
	private Path2D bentPath(EdgeRouter.EdgeGeometry edge, Point2D through) {
		List<Point2D> bent = new ArrayList<>(edge.waypoints());
		bent.add(bent.size() / 2, through);
		return edgeStyle.path(bent, edge.startHorizontal(), edge.endHorizontal());
	}

	// Edge indices that touch the given table — the ones whose comets flow and whose labels stay lit.
	private List<Integer> edgesTouching(EntityBox table) {
		int index = scene.indexOf(table);
		List<Integer> out = new ArrayList<>();
		List<Link> edges = scene.edges();
		for (int i = 0; i < edges.size(); i++) {
			if (edges.get(i).from() == index || edges.get(i).to() == index) {
				out.add(i);
			}
		}
		return out;
	}

	private int labelAt(Point2D world) {
		for (Map.Entry<Integer, LabelLayout.Placed> entry : joinLabelRects.entrySet()) {
			if (entry.getValue().rect().contains(world)) {
				return entry.getKey();
			}
		}
		return -1;
	}

	// Live update while dragging a label: pin it, bend its edge through it, and resize its pill — no full relayout.
	private void moveLabel(int edge, double centreX, double centreY) {
		Point2D pt = new Point2D.Double(centreX, centreY);
		labelOverrides.put(edge, pt);
		if (edge >= 0 && edge < paths.size()) {
			Path2D bent = bentPath(geometry.get(edge), pt);
			paths.set(edge, bent);
			if (edge < flatPaths.size()) {
				flatPaths.set(edge, SceneRenderer.flatten(bent, flattenFlatness()));
			}
		}
		String text = scene.edges().get(edge).joinLabel();
		if (text != null) {
			joinLabelRects.put(edge,
					new LabelLayout.Placed(LabelLayout.labelRect(getFontMetrics(rowFont), text, pt), pt));
		}
		bufferDirty = true;
	}

	// Rebuilt whenever the host actions change: items in registration order, a null-handler entry drawn as a
	// divider (never leading, so a menu doesn't open on a rule).
	private void rebuildMenu() {
		tableMenu.removeAll();
		for (MappaView.EntityAction action : entityActions) {
			if (action.handler() == null) {
				if (tableMenu.getComponentCount() > 0) {
					tableMenu.addSeparator();
				}
				continue;
			}
			JMenuItem item = new JMenuItem(action.label());
			item.addActionListener(a -> {
				MappaMap.Entity entity = menuTable == null ? null : entityNamed(menuTable.name());
				if (entity != null) {
					action.handler().accept(entity);
				}
			});
			tableMenu.add(item);
		}
	}

	// Menu actions hand the host the live map's entity, not just a name — resolved at click time so a
	// setMap() swap can't leave an item pointing at a stale map.
	MappaMap.Entity entityNamed(String name) {
		if (graph == null || name == null) {
			return null;
		}
		String key = name.toLowerCase(Locale.ROOT);
		return graph.entities().stream()
				.filter(e -> e.name().toLowerCase(Locale.ROOT).equals(key))
				.findFirst().orElse(null);
	}

	// ---- Painting ----------------------------------------------------------------------------------

	@Override
	protected void paintComponent(Graphics g) {
		if (scene.isEmpty()) {
			g.setColor(theme.background());
			g.fillRect(0, 0, getWidth(), getHeight());
			if (building) {
				((Graphics2D) g).setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
						RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
				g.setColor(theme.muted());
				g.setFont(titleFont);
				String msg = "Laying out diagram…";
				FontMetrics fm = g.getFontMetrics();
				g.drawString(msg, (getWidth() - fm.stringWidth(msg)) / 2, getHeight() / 2);
			}
			return;
		}
		// While a resize/sidebar-drag is in flight, never re-raster the diagram (that's what flickered/jumped) and
		// never keep the live diagram on screen (that re-exposed it too). Show the calm placeholder at once; once
		// the drag settles, resizeRedraw bakes the new diagram and fades the placeholder back out over it.
		// (Skipped on the very first render — no buffer yet — so opening the tab goes straight to the diagram.)
		if (resizing && buffer != null) {
			paintResizingPlaceholder(g);
			return;
		}
		// Frame the diagram on first show. Clear the flag only once the fit actually lands (computeFit needs a
		// real size), so the initial framing isn't lost if the first paint happens before the component is sized.
		if (fitPending && computeFit()) {
			fitPending = false;
			bufferDirty = true;
			onViewChanged.run();
		}
		ensureGeometry();
		refreshFlatsForScale();

		// The static buffer is rendered at device resolution with a margin around the viewport, so a pan can
		// just blit the cached pixels shifted (no re-render) until it drifts past the margin or settles —
		// the heavy redraw happens on a real change (zoom, select, drag, or pan-settle), not every pan frame.
		double dpr = Math.max(((Graphics2D) g).getTransform().getScaleX(), 1.0);
		// A halo of extra scene baked around the viewport, so a pan or zoom-out reaches detail well past the
		// edge before it falls back to the low-geometry pass — sized to the viewport (bigger window, bigger
		// reach) and capped. The rasterisation is culled and banded across cores, so a wide halo stays cheap.
		int m = Math.max(BUFFER_MARGIN, Math.min(BUFFER_MARGIN_MAX, (int) (Math.min(getWidth(), getHeight()) * 0.6)));
		int bw = Math.max(1, (int) Math.round((getWidth() + 2 * m) * dpr));
		int bh = Math.max(1, (int) Math.round((getHeight() + 2 * m) * dpr));
		// Blit the cached buffer when it's clean and still matches the viewport (pan/zoom shift+scale it for
		// instant feedback); otherwise re-bake. Resize never reaches here — it shows the placeholder and re-bakes
		// once on settle — so there's no stale-buffer-scaling to look weird or sluggish.
		boolean canBlit = !bufferDirty && buffer != null
				&& buffer.getWidth() == bw && buffer.getHeight() == bh && dpr == bufferDpr;
		if (!canBlit) {
			rebuildBuffer(dpr, bw, bh, m);
		}
		if (!resizeArmed && !armScheduled) {
			armScheduled = true;
			armResize.restart();   // the diagram is now actually on screen — arm the resize placeholder shortly after
		}
		// Blit the device-pixel buffer (bypassing the HiDPI transform) under an affine that maps where it was
		// baked (bufferScale + bufferPan) onto the current viewport: a pan shifts it, a zoom scales it — so
		// dragging and wheeling get instant feedback. The settle timer re-bakes it crisp once motion stops.
		// A scaled or far-panned blit can leave the edges uncovered, so clear to the backdrop first.
		double f = scale / bufferScale;
		double tx = dpr * offsetX - f * dpr * (m + bufferPanX);
		double ty = dpr * offsetY - f * dpr * (m + bufferPanY);
		g.setColor(theme.background());
		g.fillRect(0, 0, getWidth(), getHeight());

		// Low-geometry placeholders beneath the detailed buffer: where the buffer doesn't reach (a fast pan or
		// zoom-out exposes its margin), boxes still show their structure instead of appearing from nothing. The
		// opaque buffer covers them where it's baked; the settle re-bake resolves them to full detail. When the
		// buffer covers the whole viewport (any still view, and every flow-animation frame), they'd be painted
		// over entirely — skip the pass rather than draw a scene's worth of invisible boxes 30× a second.
		boolean bufferCovers = tx <= 0 && ty <= 0
				&& tx + f * buffer.getWidth() >= getWidth() * dpr
				&& ty + f * buffer.getHeight() >= getHeight() * dpr;
		if (scene != null && !LightweightMode.isOn() && !bufferCovers) {
			Graphics2D sil = (Graphics2D) g.create();
			sil.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			sil.translate(offsetX, offsetY);
			sil.scale(scale, scale);
			renderer.drawSilhouettes(sil, scene, visibleWorld(48));
			sil.dispose();
		}

		Graphics2D blit = (Graphics2D) g.create();
		blit.setTransform(new AffineTransform());
		if (f != 1.0) {
			blit.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		}
		AffineTransform at = new AffineTransform();
		at.translate(tx, ty);
		at.scale(f, f);
		blit.drawImage(buffer, at, null);
		blit.dispose();

		Graphics2D overlay = (Graphics2D) g.create();
		overlay.translate(offsetX, offsetY);
		overlay.scale(scale, scale);
		if (draggingIndex >= 0) {
			renderer.drawDragged(overlay, scene, geometry, paths, draggingIndex, focusTable);
		}
		else if (!pathEdges.isEmpty()) {
			Graphics2D trail = trailGraphics(overlay);
			renderer.drawPath(trail, scene, paths, flatPaths, pathEdges, pathFrom, pathTo,
					animating ? flowPhase : 0, scale, visibleWorld(0), edgeBounds);
			trail.dispose();
			renderer.drawJoinLabelsOver(overlay, scene, joinLabelRects, pathEdges);   // comets below the hints
		}
		else if (active != null) {
			Graphics2D trail = trailGraphics(overlay);
			renderer.drawFlow(trail, scene, flatPaths, active, animating ? flowPhase : 0, scale,
					visibleWorld(0), edgeBounds);
			trail.dispose();
			renderer.drawJoinLabelsOver(overlay, scene, joinLabelRects, edgesTouching(active));   // flow below the hints
		}
		overlay.dispose();

		paintMinimap(g);

		// A resize just finished: the placeholder fades out over the freshly drawn diagram beneath.
		if (placeholderAlpha > 0.004f) {
			overlayPlaceholder(g);
		}
	}


	// ---- overview minimap ----------------------------------------------------------------------------

	private record MiniView(Rectangle panel, double scale, double originX, double originY, Rectangle2D world) {
		double toX(double worldX) {
			return originX + (worldX - world.getX()) * scale;
		}

		double toY(double worldY) {
			return originY + (worldY - world.getY()) * scale;
		}

		double toWorldX(double miniX) {
			return world.getX() + (miniX - originX) / scale;
		}

		double toWorldY(double miniY) {
			return world.getY() + (miniY - originY) / scale;
		}
	}

	/** Whether the minimap shows, and in which corner (OFF hides it; AUTO gates it on the diagram's size). */
	void setMinimap(MappaMinimap minimap) {
		this.minimap = minimap == null ? MappaMinimap.AUTO : minimap;
		repaint();
	}

	// Whether the overview minimap is currently on screen — the visibility decision, exposed for tests.
	boolean minimapVisible() {
		return miniView() != null;
	}

	// The minimap layout for the current scene, placed in its configured corner, or null when it is off or
	// (in AUTO) the diagram is small enough not to need one.
	private MiniView miniView() {
		if (scene == null || minimap == MappaMinimap.OFF) {
			return null;
		}
		if (minimap == MappaMinimap.AUTO && scene.tables().size() < MINIMAP_MIN_TABLES) {
			return null;
		}
		Rectangle2D world = scene.worldBounds();
		if (world == null || world.getWidth() <= 0 || world.getHeight() <= 0) {
			return null;
		}
		MappaMinimap corner = minimap == MappaMinimap.AUTO ? MappaMinimap.BOTTOM_RIGHT : minimap;
		boolean left = corner == MappaMinimap.TOP_LEFT || corner == MappaMinimap.BOTTOM_LEFT;
		boolean top = corner == MappaMinimap.TOP_LEFT || corner == MappaMinimap.TOP_RIGHT;
		int px = left ? MINIMAP_MARGIN : getWidth() - MINIMAP_W - MINIMAP_MARGIN;
		int py = top ? MINIMAP_MARGIN : getHeight() - MINIMAP_H - MINIMAP_MARGIN;
		double ms = Math.min((MINIMAP_W - 2.0 * MINIMAP_PAD) / world.getWidth(),
				(MINIMAP_H - 2.0 * MINIMAP_PAD) / world.getHeight());
		double originX = px + (MINIMAP_W - world.getWidth() * ms) / 2;
		double originY = py + (MINIMAP_H - world.getHeight() * ms) / 2;
		return new MiniView(new Rectangle(px, py, MINIMAP_W, MINIMAP_H), ms, originX, originY, world);
	}

	// The overview inset: every box as a dot, and the current viewport as a frame. Drag it to jump the view.
	private void paintMinimap(Graphics g) {
		MiniView mv = miniView();
		if (mv == null || LightweightMode.isOn()) {
			return;
		}
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		Rectangle p = mv.panel();
		g2.setColor(new Color(theme.surface().getRed(), theme.surface().getGreen(), theme.surface().getBlue(), 235));
		g2.fillRoundRect(p.x, p.y, p.width, p.height, 12, 12);
		g2.setColor(theme.line());
		g2.setStroke(new BasicStroke(1));
		g2.drawRoundRect(p.x, p.y, p.width, p.height, 12, 12);
		g2.setClip(new RoundRectangle2D.Double(p.x, p.y, p.width, p.height, 12, 12));

		for (EntityBox box : scene.tables()) {
			Rectangle2D b = box.bounds();
			Color dot = box == active ? theme.accent()
					: new Color(theme.muted().getRed(), theme.muted().getGreen(), theme.muted().getBlue(), 150);
			g2.setColor(dot);
			g2.fill(new Rectangle2D.Double(mv.toX(b.getX()), mv.toY(b.getY()),
					Math.max(1.5, b.getWidth() * mv.scale()), Math.max(1.5, b.getHeight() * mv.scale())));
		}

		Point2D topLeft = toWorld(new Point(0, 0));
		Point2D bottomRight = toWorld(new Point(getWidth(), getHeight()));
		g2.setColor(theme.entityHeader());
		g2.setStroke(new BasicStroke(1.5f));
		g2.draw(new Rectangle2D.Double(mv.toX(topLeft.getX()), mv.toY(topLeft.getY()),
				(bottomRight.getX() - topLeft.getX()) * mv.scale(), (bottomRight.getY() - topLeft.getY()) * mv.scale()));
		g2.dispose();
	}

	// Aims the camera at the world point under a minimap click/drag. Motion is optional: when disabled the
	// viewport lands at the requested point immediately instead of tweening behind the pointer.
	private void navigateFromMinimap(Point at, MiniView mv) {
		double worldX = mv.toWorldX(at.x);
		double worldY = mv.toWorldY(at.y);
		followCameraTo(getWidth() / 2.0 - scale * worldX, getHeight() / 2.0 - scale * worldY);
	}

	// Cinematic pan when enabled; otherwise land immediately so the minimap stays direct and predictable.
	private void followCameraTo(double targetX, double targetY) {
		followTargetX = targetX;
		followTargetY = targetY;
		if (!animating || LightweightMode.isOn()) {
			cameraFollow.stop();
			offsetX = targetX;
			offsetY = targetY;
			bufferDirty = true;
			repaint();
			onViewChanged.run();
			return;
		}
		if (!cameraFollow.isRunning()) {
			cameraFollow.start();
		}
	}

	private void stepCameraFollow() {
		double dx = followTargetX - offsetX;
		double dy = followTargetY - offsetY;
		// Whenever the camera catches up to the target — even while the pointer still holds the minimap — land
		// exactly and re-bake the buffer crisp there, so detail keeps arriving as you drag and pause, not only
		// once you let go. A fast continuous drag keeps the target ahead, so it stays a cheap shifted blit.
		if (Math.hypot(dx, dy) < 0.5) {
			offsetX = followTargetX;
			offsetY = followTargetY;
			cameraFollow.stop();
			bufferDirty = true;   // landed — re-bake crisp here
			repaint();
			onViewChanged.run();
			return;
		}
		offsetX += dx * FOLLOW_EASE;
		offsetY += dy * FOLLOW_EASE;
		repaint();
		onViewChanged.run();
	}

	// The minimal placeholder shown only while a resize is in flight: the diagram backdrop, a single small
	// node-link glyph, and one quiet line of text — lots of whitespace, nothing busy. Cheap and rock-steady (no
	// scene raster) so a resize never flickers or jumps; the real diagram re-renders once the drag settles.
	private void paintResizingPlaceholder(Graphics g) {
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		int w = getWidth();
		int h = getHeight();
		// Just a solid backdrop here — no tiled pattern fill — so painting the placeholder every resize frame is
		// as cheap as possible (a tiled TexturePaint over the whole canvas per frame is what dragged).
		g2.setColor(theme.background());
		g2.fillRect(0, 0, w, h);

		int cx = w / 2;
		int cy = h / 2;
		drawNodeGlyph(g2, cx, cy - 26);

		g2.setFont(Fonts.sans(13f));
		g2.setColor(theme.muted());
		String msg = "Redrawing when you stop resizing";
		FontMetrics fm = g2.getFontMetrics();
		g2.drawString(msg, cx - fm.stringWidth(msg) / 2, cy + 30);
		g2.dispose();
	}

	// A small, understated node-link glyph (three outline nodes joined by thin lines) centred at (cx, cy) — a
	// minimal nod to "a diagram", drawn entirely in muted tones.
	private void drawNodeGlyph(Graphics2D g2, int cx, int cy) {
		int[][] n = { { cx - 19, cy - 7 }, { cx + 19, cy - 7 }, { cx, cy + 13 } };
		int r = 5;
		g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g2.setColor(withAlpha(theme.muted(), 95));
		g2.drawLine(n[0][0], n[0][1], n[1][0], n[1][1]);
		g2.drawLine(n[0][0], n[0][1], n[2][0], n[2][1]);
		g2.drawLine(n[1][0], n[1][1], n[2][0], n[2][1]);
		for (int[] p : n) {
			g2.setColor(theme.background());   // mask the lines under each node so the outline reads cleanly
			g2.fillOval(p[0] - r, p[1] - r, 2 * r, 2 * r);
			g2.setColor(withAlpha(theme.muted(), 150));
			g2.drawOval(p[0] - r, p[1] - r, 2 * r, 2 * r);
		}
	}

	private static Color withAlpha(Color c, int a) {
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
	}

	// Banded rasterisation. The CPU rasterisation of the scene (Marlin) is the heaviest per-re-bake cost, so on
	// a large schema we split the buffer into horizontal bands and rasterise them across cores, each into its
	// own tile (Java2D supports concurrent rendering to distinct images). Each tile is the exact size of its
	// band and the full scene is drawn into it through a shifted transform, so the tile's own bounds clip the
	// pixels exactly — no shared pixels between threads, no seams, and (unlike a per-band cull) glows/shadows
	// that bleed across a boundary are preserved. The redundant work is only per-shape setup, not rasterisation
	// (Marlin skips pixels outside the tile). A small schema stays single-threaded — banding isn't worth it.
	private static final int BAND_MIN_TABLES = 60;   // below this the single-threaded path is plenty
	private static final int BAND_MIN_PX = 500;      // don't carve bands thinner than this (device px)
	private static final int BAND_MAX = 8;

	private void rebuildBuffer(double dpr, int pixelWidth, int pixelHeight, int margin) {
		// Allocate the scene buffer in the display's native format (createCompatibleImage) so the GPU
		// pipeline (Metal on macOS, Direct3D on Windows -- both default) blits it with no per-frame format
		// conversion, and pin it in VRAM (acceleration priority) so the pan/zoom blits stay hardware-
		// accelerated. The heavy work -- antialiased vector rasterisation -- still runs on the CPU (Marlin)
		// once per re-bake; only the repeated blits are GPU-accelerated, which is what keeps interaction smooth.
		//
		// Reuse the buffer whenever its dimensions still match: most re-bakes (selecting a table, a theme or
		// keys-only/join-labels toggle, a same-size zoom-settle) keep the size, and the scene fully repaints the
		// buffer — so reusing it avoids allocating + discarding a full-resolution image each time. That cuts GC
		// churn markedly on memory-constrained / older machines (only a resize, which changes the size, allocates).
		GraphicsConfiguration gc = getGraphicsConfiguration();
		if (buffer == null || buffer.getWidth() != pixelWidth || buffer.getHeight() != pixelHeight) {
			buffer = gc != null
					? gc.createCompatibleImage(pixelWidth, pixelHeight)
					: new BufferedImage(pixelWidth, pixelHeight, BufferedImage.TYPE_INT_RGB);
			buffer.setAccelerationPriority(1.0f);
		}

		int bands = bandCount(pixelHeight);
		if (bands <= 1) {
			Graphics2D g2 = buffer.createGraphics();
			paintSceneInto(g2, dpr, margin, 0, visibleWorld(margin));
			g2.dispose();
		}
		else {
			rasteriseInBands(dpr, pixelWidth, pixelHeight, margin, bands, gc);
		}
		bufferPanX = offsetX;
		bufferPanY = offsetY;
		bufferScale = scale;
		bufferDpr = dpr;
		bufferDirty = false;
	}

	// How many bands to split the re-bake into: one for small schemas, otherwise scaled by cores and by the
	// buffer height (never thinner than BAND_MIN_PX), capped at BAND_MAX.
	private int bandCount(int pixelHeight) {
		if (scene == null || scene.tables().size() < BAND_MIN_TABLES) {
			return 1;
		}
		int cores = Runtime.getRuntime().availableProcessors();
		int byHeight = Math.max(1, pixelHeight / BAND_MIN_PX);
		return Math.max(1, Math.min(Math.min(cores, byHeight), BAND_MAX));
	}

	// Rasterises the scene into {@code bands} horizontal tiles in parallel, then composites them into buffer.
	private void rasteriseInBands(double dpr, int pixelWidth, int pixelHeight, int margin, int bands,
			GraphicsConfiguration gc) {
		int bandHeight = (pixelHeight + bands - 1) / bands;
		Rectangle2D clip = visibleWorld(margin);   // same clip for every band; the tile bounds do the slicing
		BufferedImage[] tiles = new BufferedImage[bands];
		IntStream.range(0, bands).parallel().forEach(b -> {
			int y0 = b * bandHeight;
			int h = Math.min(bandHeight, pixelHeight - y0);
			if (h <= 0) {
				return;
			}
			BufferedImage tile = gc != null
					? gc.createCompatibleImage(pixelWidth, h)
					: new BufferedImage(pixelWidth, h, BufferedImage.TYPE_INT_RGB);
			Graphics2D bg = tile.createGraphics();
			paintSceneInto(bg, dpr, margin, y0, clip);
			bg.dispose();
			tiles[b] = tile;
		});
		Graphics2D g2 = buffer.createGraphics();
		for (int b = 0; b < bands; b++) {
			if (tiles[b] != null) {
				g2.drawImage(tiles[b], 0, b * bandHeight, null);
			}
		}
		g2.dispose();
	}

	// Paints the whole scene through the standard transform stack. {@code bandTop} shifts the device origin so
	// a band tile (whose own bounds clip it) lands correctly; pass 0 for a full-buffer paint. {@code worldClip}
	// culls shapes the band can't show.
	private void paintSceneInto(Graphics2D g2, double dpr, int margin, int bandTop, Rectangle2D worldClip) {
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		if (bandTop != 0) {
			g2.translate(0, -bandTop);   // device-space shift so this band's slice maps to the tile's origin
		}
		g2.scale(dpr, dpr);          // map logical coordinates onto the device-resolution buffer
		g2.translate(margin, margin);   // buffer covers the viewport plus a margin on every side
		g2.setColor(theme.background());
		g2.fillRect(-margin, -margin, getWidth() + 2 * margin, getHeight() + 2 * margin);
		paintGrid(g2, -margin, -margin, getWidth() + 2 * margin, getHeight() + 2 * margin, offsetX, offsetY);
		g2.translate(offsetX, offsetY);
		g2.scale(scale, scale);
		renderer.draw(g2, scene, geometry, paths, joinLabelRects, active, focusTable, draggingIndex,
				showJoinColumns, worldClip, edgeBounds);
	}

	// The chosen backdrop pattern in screen space (so it stays a constant size); offset by the pan so it drifts
	// with the diagram. Painted as a single tiled TexturePaint fill (one op) rather than thousands of per-shape
	// draws — the pattern alone was a few thousand draws on every buffer rebuild. PLAIN paints nothing.
	private void paintGrid(Graphics2D g2, int x, int y, int areaWidth, int areaHeight, double offX, double offY) {
		if (background == Backdrop.PLAIN || LightweightMode.isOn()) {
			return;   // lightweight: plain backdrop, skip the tiled pattern fill entirely
		}
		if (gridTile == null || gridTileStyle != background || gridTileDark != theme.isDark()) {
			gridTile = buildBackgroundTile(background);
			gridTileStyle = background;
			gridTileDark = theme.isDark();
		}
		int tw = gridTile.getWidth();
		int th = gridTile.getHeight();
		double startX = ((offX % tw) + tw) % tw;
		double startY = ((offY % th) + th) % th;
		Graphics2D gg = (Graphics2D) g2.create();
		gg.setPaint(new TexturePaint(gridTile, new Rectangle2D.Double(startX, startY, tw, th)));
		gg.fillRect(x, y, areaWidth, areaHeight);
		gg.dispose();
	}

	// The repeating tile for the current backdrop pattern, tiled by the TexturePaint above. Drawn in a faint
	// muted tone so the diagram always reads first.
	private BufferedImage buildBackgroundTile(Backdrop style) {
		Color m = theme.muted();
		// Very faint — a barely-there texture, well below the edge tone (which also uses muted) so it never
		// competes with the relationships.
		Color faint = new Color(m.getRed(), m.getGreen(), m.getBlue(), theme.isDark() ? 22 : 24);
		return switch (style) {
			case DOTS, PLAIN -> dotTile(faint);
			case GRID -> gridRuleTile(faint);
			case HEXAGONS -> hexagonTile(faint);
		};
	}

	private BufferedImage dotTile(Color colour) {
		BufferedImage tile = new BufferedImage(GRID_SPACING, GRID_SPACING, BufferedImage.TYPE_INT_ARGB);
		Graphics2D tg = tile.createGraphics();
		tg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		tg.setColor(colour);
		tg.fillOval(0, 0, 2, 2);
		tg.dispose();
		return tile;
	}

	// Thin rules along the top and left edges — tiled into a continuous graph-paper grid.
	private BufferedImage gridRuleTile(Color colour) {
		BufferedImage tile = new BufferedImage(GRID_SPACING, GRID_SPACING, BufferedImage.TYPE_INT_ARGB);
		Graphics2D tg = tile.createGraphics();
		tg.setColor(new Color(colour.getRed(), colour.getGreen(), colour.getBlue(),
				Math.round(colour.getAlpha() * 0.7f)));   // lines cover more area, so ease them back a touch
		tg.fillRect(0, 0, GRID_SPACING, 1);   // top rule
		tg.fillRect(0, 0, 1, GRID_SPACING);   // left rule
		tg.dispose();
		return tile;
	}

	// A pointy-top honeycomb. The repeat unit is (w, 3s) with rows offset by w/2; hexagons are drawn for the
	// surrounding lattice and clipped to the tile, so the TexturePaint tessellates seamlessly.
	private BufferedImage hexagonTile(Color colour) {
		double s = 15;                          // hexagon "radius" (centre → vertex)
		double w = s * Math.sqrt(3);            // horizontal period
		int tw = (int) Math.round(w);
		int th = (int) Math.round(3 * s);
		BufferedImage tile = new BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB);
		Graphics2D tg = tile.createGraphics();
		tg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		tg.setColor(colour);
		tg.setStroke(new BasicStroke(1f));
		for (int row = -1; row <= 3; row++) {
			for (int i = -1; i <= 2; i++) {
				double cx = i * w + ((row & 1) == 0 ? 0 : w / 2);
				double cy = row * 1.5 * s;
				tg.draw(hexagon(cx, cy, s));
			}
		}
		tg.dispose();
		return tile;
	}

	private static Path2D hexagon(double cx, double cy, double s) {
		Path2D p = new Path2D.Double();
		for (int k = 0; k < 6; k++) {
			double a = Math.PI / 3 * k;   // pointy-top: vertex straight up, then every 60°
			double px = cx + s * Math.sin(a);
			double py = cy - s * Math.cos(a);
			if (k == 0) {
				p.moveTo(px, py);
			}
			else {
				p.lineTo(px, py);
			}
		}
		p.closePath();
		return p;
	}

	/**
	 * Renders the whole diagram (every table and column, at full detail) to an image with a margin — for PNG
	 * export. With {@code transparent} the canvas backdrop (solid fill + dot grid) is omitted so the PNG carries
	 * an alpha channel showing only the tables and edges; otherwise it gets the themed solid background.
	 * {@code resolutionMultiplier} scales the standard supersample up (2×/3×/4×) for a higher-resolution PNG; the
	 * pixel-budget cap still applies, so an over-large request scales back toward 1:1 rather than OOM-ing.
	 */
	BufferedImage renderImage(boolean transparent, double resolutionMultiplier) {
		if (graph == null) {
			return null;
		}
		// Export builds its own scene with uncapped table heights (no scrollbars — every shown column is drawn
		// in full), so the PNG is a complete reference; it still honours the keys-only toggle so the export
		// matches what's on screen. The fresh build re-runs the layout, so we then copy the on-screen positions
		// onto it — otherwise dragging tables around would be thrown away and the export would show the default
		// auto-layout instead of the arrangement the user made.
		ExportContent content = buildExportContent();
		if (content == null) {
			return null;
		}
		Scene exportScene = content.scene();
		List<EdgeRouter.EdgeGeometry> exGeometry = content.geometry();
		List<Path2D> exPaths = content.paths();
		Map<Integer, LabelLayout.Placed> exLabels = content.labels();
		Rectangle2D world = content.world();

		int width = (int) Math.ceil(world.getWidth()) + EXPORT_MARGIN * 2;
		int height = (int) Math.ceil(world.getHeight()) + EXPORT_MARGIN * 2;
		// Render above 1:1 for a crisp PNG, but never past the pixel budget — a huge schema can't allocate a
		// hundreds-of-MB image and OOM a constrained machine, so it scales back toward 1:1 (or below) to fit.
		double target = EXPORT_SUPERSAMPLE * Math.max(1.0, resolutionMultiplier);
		double exportScale = Math.min(target, Math.sqrt(EXPORT_MAX_PIXELS / ((double) width * height)));
		int pw = Math.max(1, (int) Math.round(width * exportScale));
		int ph = Math.max(1, (int) Math.round(height * exportScale));
		BufferedImage image = new BufferedImage(pw, ph,
				transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
		Graphics2D g2 = image.createGraphics();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		g2.scale(exportScale, exportScale);
		if (!transparent) {
			g2.setColor(theme.background());
			g2.fillRect(0, 0, width, height);
			paintGrid(g2, 0, 0, width, height, 0, 0);
		}
		g2.translate(EXPORT_MARGIN - world.getX(), EXPORT_MARGIN - world.getY());
		renderer.draw(g2, exportScene, exGeometry, exPaths, exLabels, null, focusTable, -1, showJoinColumns, null);
		g2.dispose();
		return image;
	}

	/** The laid-out diagram ready to paint for export: the scene (with the user's on-screen positions), the routed
	 *  edge geometry + paths, the join-column label placements, and the world bounds (edges included). */
	record ExportContent(Scene scene, List<EdgeRouter.EdgeGeometry> geometry, List<Path2D> paths,
			Map<Integer, LabelLayout.Placed> labels, Rectangle2D world) { }

	/**
	 * Lays the whole diagram out for export — full detail, uncapped table heights (every shown column drawn in
	 * full), the keys-only toggle honoured. When nothing was height-capped on screen the on-screen positions are
	 * copied over so the export matches the arrangement the user dragged into place; when a table expands past the
	 * on-screen cap, the export keeps its own full-height auto-layout instead, so expanded boxes don't overlap.
	 * Reads the live scene and font metrics, so it must run on the EDT. Returns null when there's no graph yet.
	 */
	ExportContent buildExportContent() {
		if (graph == null) {
			return null;
		}
		Scene exportScene = SceneBuilder.build(graph, titleFont, rowFont,
				(text, font) -> getFontMetrics(font).stringWidth(text), showJoinColumns, false, keysOnly, layoutStyle);
		applyLivePositions(exportScene);
		Rectangle2D world = exportScene.worldBounds();
		if (world == null) {
			return null;
		}
		List<EdgeRouter.EdgeGeometry> geometry = EdgeRouter.route(exportScene);
		List<Path2D> paths = new ArrayList<>(geometry.size());
		for (EdgeRouter.EdgeGeometry e : geometry) {
			Path2D p = edgeStyle.path(e.waypoints(), e.startHorizontal(), e.endHorizontal());
			paths.add(p);
			world = world.createUnion(p.getBounds2D());   // include edge curves (e.g. self-loops dipping below)
		}
		Map<Integer, LabelLayout.Placed> labels = showJoinColumns
				? LabelLayout.layout(exportScene, paths, getFontMetrics(rowFont), Map.of())
				: Map.of();
		return new ExportContent(exportScene, geometry, paths, labels, world);
	}

	/**
	 * Writes the diagram to {@code file} as SVG — the same paint pipeline as the PNG export, but recorded into a
	 * vector document, so the result is small and scales without limit (ideal for sharing a hundreds-of-tables
	 * schema). {@code content} is built on the EDT via {@link #buildExportContent()}; the render + serialise here
	 * touches only that snapshot and a Graphics it owns, so this runs off the EDT. Text is emitted as outlined
	 * glyphs so it renders identically everywhere and the layout can't shift on a viewer that lacks the font.
	 */
	void writeSvg(ExportContent content, boolean transparent, File file) throws IOException {
		writeFile(file, toSvg(content, transparent));
	}

	/**
	 * Writes the diagram as a single self-contained interactive HTML page: the SVG inlined under a small
	 * pan/zoom/fit viewer (see {@link HtmlExport}). Opens in any browser with no Connector Bridge,
	 * server, or extension — so a diagram can be exported once and viewed/shared anywhere.
	 */
	void writeInteractiveHtml(ExportContent content, boolean transparent, File file, String title) throws IOException {
		Color bg = theme.background();
		String background = transparent ? null : String.format("#%06X", bg.getRGB() & 0xFFFFFF);
		writeFile(file, HtmlExport.wrap(toSvg(content, transparent), title, background,
				sceneData(content.scene(), content.world())));
	}

	/**
	 * The scene sidecar the interactive HTML viewer overlays: each table's box and the foreign-key edges, in the
	 * SVG's coordinate space (the export translate is baked in), so the in-page overlay aligns and pans/zooms with
	 * the diagram. Hand-built JSON — no dependency, and the only free text (table names) is escaped.
	 */
	static String sceneData(Scene scene, Rectangle2D world) {
		double dx = EXPORT_MARGIN - world.getX();
		double dy = EXPORT_MARGIN - world.getY();
		StringBuilder sb = new StringBuilder("{\"tables\":[");
		List<EntityBox> tables = scene.tables();
		for (int i = 0; i < tables.size(); i++) {
			Rectangle2D b = tables.get(i).bounds();
			if (i > 0) {
				sb.append(',');
			}
			sb.append("{\"n\":").append(jsonString(tables.get(i).name()))
					.append(",\"x\":").append(Math.round(b.getX() + dx))
					.append(",\"y\":").append(Math.round(b.getY() + dy))
					.append(",\"w\":").append(Math.round(b.getWidth()))
					.append(",\"h\":").append(Math.round(b.getHeight()))
					.append('}');
		}
		sb.append("],\"edges\":[");
		List<Link> edges = scene.edges();
		for (int i = 0; i < edges.size(); i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append('[').append(edges.get(i).from()).append(',').append(edges.get(i).to()).append(']');
		}
		return sb.append("]}").toString();
	}

	private static String jsonString(String value) {
		if (value == null) {
			value = "";
		}
		StringBuilder sb = new StringBuilder("\"");
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					}
					else {
						sb.append(c);
					}
				}
			}
		}
		return sb.append('"').toString();
	}

	/** Renders the diagram scene to an SVG document string (glyphs outlined, so it carries no font dependency). */
	String toSvg(ExportContent content, boolean transparent) throws IOException {
		Rectangle2D world = content.world();
		int width = (int) Math.ceil(world.getWidth()) + EXPORT_MARGIN * 2;
		int height = (int) Math.ceil(world.getHeight()) + EXPORT_MARGIN * 2;
		SvgGraphics2D svg = new SvgGraphics2D(width, height);
		if (!transparent) {
			svg.setColor(theme.background());
			svg.fillRect(0, 0, width, height);
		}
		svg.translate(EXPORT_MARGIN - world.getX(), EXPORT_MARGIN - world.getY());
		renderer.draw(svg, content.scene(), content.geometry(), content.paths(), content.labels(), null, focusTable,
				-1, showJoinColumns, null);
		return svg.document();
	}

	private static void writeFile(File file, String text) throws IOException {
		try (Writer out = new OutputStreamWriter(
				new BufferedOutputStream(new FileOutputStream(file)), StandardCharsets.UTF_8)) {
			out.write(text);
		}
	}

	/**
	 * Copy each on-screen table's position onto the freshly-laid-out export scene, so the PNG reflects the
	 * arrangement the user dragged into place rather than the default auto-layout. Matched by table name (names
	 * are unique in a schema); aligned by the box's top-left corner, so an uncapped (taller) export box grows
	 * downward from where the on-screen box sits instead of shifting off its anchor. Tables with no on-screen
	 * counterpart keep their laid-out position.
	 */
	private void applyLivePositions(Scene exportScene) {
		if (scene == null || scene.isEmpty()) {
			return;
		}
		// Only carry over the on-screen arrangement when no table was height-capped on screen. If a table has
		// more columns than the on-screen cap allows, its uncapped export box is taller than the gap the on-screen
		// layout left below it — copying that position would grow it down into the next table. In that case keep
		// the export's own full-height auto-layout, which is spaced for the expanded boxes (no overlap).
		if (sameHeights(scene, exportScene)) {
			copyPositions(scene, exportScene);
		}
	}

	/** Whether every same-named table has the same box height in both scenes — i.e. nothing was height-capped, so
	 *  the on-screen arrangement still fits the export. Visible for testing. */
	static boolean sameHeights(Scene onScreen, Scene export) {
		Map<String, Double> heights = new HashMap<>();
		for (EntityBox t : onScreen.tables()) {
			heights.putIfAbsent(t.name(), t.bounds().getHeight());
		}
		for (EntityBox t : export.tables()) {
			Double onScreenHeight = heights.get(t.name());
			if (onScreenHeight != null && Math.abs(onScreenHeight - t.bounds().getHeight()) > 0.5) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Move each table in {@code to} to its same-named counterpart's position in {@code from}, aligned by the
	 * box's top-left so a taller (uncapped) export box grows downward from the on-screen anchor. Visible for
	 * testing. Tables with no counterpart in {@code from} keep their position.
	 */
	static void copyPositions(Scene from, Scene to) {
		Map<String, EntityBox> byName = new HashMap<>();
		for (EntityBox t : from.tables()) {
			byName.putIfAbsent(t.name(), t);
		}
		for (EntityBox target : to.tables()) {
			EntityBox live = byName.get(target.name());
			if (live != null) {
				double w = target.bounds().getWidth();
				double h = target.bounds().getHeight();
				target.moveCentreTo(live.bounds().getX() + w / 2, live.bounds().getY() + h / 2);
			}
		}
	}

	// The overlay graphics the chevron/dash trails draw through. While motion is OFF the trails are static —
	// effectively part of the scene — so they must hide behind table boxes exactly as their edges do: clip
	// them against the viewport minus every visible box. While animating, the moving pulse keeps its historic
	// draw-over-everything pass (it's transient, and re-clipping every 33ms frame would cost more than it says).
	private Graphics2D trailGraphics(Graphics2D overlay) {
		Graphics2D trail = (Graphics2D) overlay.create();
		if (animating) {
			return trail;
		}
		Rectangle2D view = visibleWorld(32);
		Area area = new Area(view);
		for (EntityBox table : scene.tables()) {
			Rectangle2D b = table.bounds();
			if (b.intersects(view)) {
				area.subtract(new Area(new RoundRectangle2D.Double(b.getX(), b.getY(), b.getWidth(),
						b.getHeight(), BoxMetrics.CORNER, BoxMetrics.CORNER)));
			}
		}
		trail.clip(area);
		return trail;
	}

	/** The viewport (plus a margin) in world coordinates — what the buffer covers, for render culling. */
	private Rectangle2D visibleWorld(int margin) {
		return new Rectangle2D.Double((-offsetX - margin) / scale, (-offsetY - margin) / scale,
				(getWidth() + 2 * margin) / scale, (getHeight() + 2 * margin) / scale);
	}

	// ---- Drag, pan, zoom, hover, menu --------------------------------------------------------------

	private void installInteraction() {
		MouseAdapter mouse = new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				onMousePressed(e);
			}

			@Override
			public void mouseDragged(MouseEvent e) {
				onMouseDragged(e);
			}

			@Override
			public void mouseReleased(MouseEvent e) {
				onMouseReleased(e);
			}

			@Override
			public void mouseMoved(MouseEvent e) {
				onMouseMoved(e);
			}

			@Override
			public void mouseWheelMoved(MouseWheelEvent e) {
				onMouseWheel(e);
			}
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);
		addMouseWheelListener(mouse);
		addComponentListener(new ComponentAdapter() {
			@Override
			public void componentResized(ComponentEvent e) {
				if (scene.isEmpty()) {
					return;
				}
				if (resizeArmed) {
					holdForResize();   // a genuine resize: placeholder during the drag, crisp re-bake once it settles
				}
				// else: still within the open's layout churn — render normally; arming fires from the first paint.
			}
		});
	}

	// Press: grab a hint label, a table (to drag), or empty space (to pan).
	private void onMousePressed(MouseEvent e) {
		if (maybeShowMenu(e)) {
			return;
		}
		MiniView mini = miniView();
		if (mini != null && mini.panel().contains(e.getPoint())) {
			navigatingMinimap = true;
			navigateFromMinimap(e.getPoint(), mini);
			return;
		}
		cameraFollow.stop();   // a press on the canvas cancels an in-flight minimap glide
		pressPoint = e.getPoint();
		Point2D world = toWorld(e.getPoint());
		if (showJoinColumns) {
			int label = labelAt(world);   // a hint label is grabbable to re-route its edge by hand
			if (label >= 0) {
				draggingLabel = label;
				Rectangle2D r = joinLabelRects.get(label).rect();
				labelGrabX = r.getCenterX() - world.getX();
				labelGrabY = r.getCenterY() - world.getY();
				return;
			}
		}
		pressedTable = scene.tableAt(world);
		if (pressedTable != null) {
			grabOffsetX = pressedTable.bounds().getCenterX() - world.getX();
			grabOffsetY = pressedTable.bounds().getCenterY() - world.getY();
		}
		else {
			lastDragPoint = e.getPoint();   // empty space → pan the canvas
		}
	}

	// Drag: move a grabbed label, drag a table (re-routing its edges live), or pan the canvas.
	private void onMouseDragged(MouseEvent e) {
		if (navigatingMinimap) {
			MiniView mini = miniView();
			if (mini != null) {
				navigateFromMinimap(e.getPoint(), mini);
			}
			return;
		}
		if (pressPoint == null || pressPoint.distance(e.getPoint()) < CLICK_SLOP) {
			return;   // still within click slop — not a drag yet
		}
		if (draggingLabel >= 0) {
			Point2D world = toWorld(e.getPoint());
			moveLabel(draggingLabel, world.getX() + labelGrabX, world.getY() + labelGrabY);
			repaint();
			onViewChanged.run();
			return;
		}
		if (pressedTable != null) {
			if (draggingIndex < 0) {
				draggingIndex = scene.indexOf(pressedTable);   // a real drag began
				bufferDirty = true;                            // bake a buffer that excludes it
			}
			Point2D world = toWorld(e.getPoint());
			pressedTable.moveCentreTo(world.getX() + grabOffsetX, world.getY() + grabOffsetY);
			geometryDirty = true;   // re-route its edges (drawn live; buffer is reused)
			repaint();
			onViewChanged.run();    // a dragged table carries its note along
		}
		else if (lastDragPoint != null) {
			offsetX += e.getX() - lastDragPoint.x;
			offsetY += e.getY() - lastDragPoint.y;
			lastDragPoint = e.getPoint();
			// No mid-pan rebuild: the cached buffer blits shifted (the low-geometry pass fills any fringe) and the
			// settle timer re-bakes it crisp once the pan stops.
			viewSettle.restart();
			repaint();
			onViewChanged.run();
		}
	}

	// Release: finish a label/table drag, or — if it was a click — select / trace-path / clear.
	private void onMouseReleased(MouseEvent e) {
		if (navigatingMinimap) {
			navigatingMinimap = false;   // the follow tween glides to the target, then settles and re-bakes
			return;
		}
		if (draggingLabel >= 0) {
			boolean moved = pressPoint != null && pressPoint.distance(e.getPoint()) >= CLICK_SLOP;
			if (!moved) {
				labelOverrides.remove(draggingLabel);   // a click (no drag) resets the label to auto-placement
			}
			draggingLabel = -1;
			geometryDirty = true;   // re-relax the auto labels around the change
			bufferDirty = true;
			pressPoint = null;
			repaint();
			onViewChanged.run();
			return;
		}
		boolean wasClick = draggingIndex < 0 && pressPoint != null
				&& pressPoint.distance(e.getPoint()) < CLICK_SLOP;
		if (draggingIndex >= 0) {
			draggingIndex = -1;
			bufferDirty = true;     // fold the dropped table back into the static buffer
			geometryDirty = true;   // re-run the label force pass now the drag has settled
			repaint();
			// Freeze the whole arrangement onto the map: later rebuilds (a detail or label toggle) then
			// restore it instead of re-laying-out, and the host is handed the positioned map to save.
			graph = graph.withPositions(SceneBuilder.positionsOf(scene));
			onArranged.accept(graph);
		}
		else if (wasClick) {
			if (e.isShiftDown() && active != null && pressedTable != null && pressedTable != active) {
				// Shift-click a second table to trace the shortest join path from the active one.
				tracePath(active, pressedTable);
			}
			else {
				// Click a table to light it up + flow its edges; click empty space to turn it off.
				clearPath();
				setActive(pressedTable == active ? null : pressedTable);
			}
		}
		pressedTable = null;
		lastDragPoint = null;
		pressPoint = null;
		maybeShowMenu(e);
	}

	// Move: a move cursor over a grabbable table or hint label, the default cursor otherwise.
	private void onMouseMoved(MouseEvent e) {
		Point2D world = toWorld(e.getPoint());
		boolean grabbable = scene.tableAt(world) != null || (showJoinColumns && labelAt(world) >= 0);
		setCursor(Cursor.getPredefinedCursor(grabbable ? Cursor.MOVE_CURSOR : Cursor.DEFAULT_CURSOR));
	}

	// Vertical scroll zooms about the pointer on every device — the factor scales with the precise
	// rotation, so a classic mouse notch (±1) lands exactly one ZOOM_STEP while trackpads and
	// high-resolution wheels (fractional rotations) glide continuously. Device sniffing is off the
	// table: a smooth mouse wheel is indistinguishable from a trackpad in AWT. Sideways scroll
	// (two-finger drift, tilt wheel) pans horizontally, with drift during a zoom gesture filtered
	// to the dominant axis. Panning otherwise stays on drag.
	private void onMouseWheel(MouseWheelEvent e) {
		double rotation = e.getPreciseWheelRotation();
		if (rotation == 0 || !axisLock.allow(e.getWhen(), e.isShiftDown(), rotation)) {
			return;
		}
		if (e.isShiftDown()) {
			// Over a tall table the sideways/Shift scroll peeks its hidden columns; anywhere else it pans.
			EntityBox over = scene.tableAt(toWorld(e.getPoint()));
			if (over != null && over.scrollable()) {
				over.scrollBy(rotation * BoxMetrics.ROW_HEIGHT);   // scroll the columns
				bufferDirty = true;   // the table card's content changed
				repaint();
				onViewChanged.run();
			}
			else {
				offsetX -= rotation * e.getScrollAmount() * SIDEWAYS_PAN_UNIT;
				// Same mid-pan path as a drag: blit the buffer shifted, re-bake crisp once the pan stops.
				viewSettle.restart();
				repaint();
				onViewChanged.run();
			}
		}
		else {
			wheelDevice.advance(e.getWhen(), rotation);
			double factor = Math.pow(ZOOM_STEP, -rotation);
			if (wheelDevice.notch()) {
				glideZoomAt(e.getPoint(), factor);   // a discrete step glides to its target
			}
			else {
				zoomGlide.stop();                    // a live fractional stream steers directly
				zoomAt(e.getPoint(), factor);
			}
		}
	}

	// Clicking a table lights it (+ its relationships) and flows its edges; the highlight/dim is baked
	// into the static buffer (rebuilt once), then the particles animate cheaply over it. The selection
	// also drives the relationship caption (via the callback) — so the caption only describes a table the
	// user deliberately chose, never whatever the pointer grazed.
	/**
	 * Find a table by name (exact, then prefix, then substring — case-insensitive) and bring it on screen,
	 * selected and highlighted, so it's easy to pinpoint in a large diagram. Returns whether one matched.
	 */
	boolean revealTable(String query) {
		if (query == null || query.isBlank() || scene == null) {
			return false;
		}
		String q = query.trim().toLowerCase(Locale.ROOT);
		EntityBox match = firstMatch(t -> t.name().equalsIgnoreCase(query));
		if (match == null) {
			match = firstMatch(t -> t.name().toLowerCase(Locale.ROOT).startsWith(q));
		}
		if (match == null) {
			match = firstMatch(t -> t.name().toLowerCase(Locale.ROOT).contains(q));
		}
		if (match == null) {
			return false;
		}
		setActive(match);   // selects, highlights its relationships, and frames it in the viewport
		return true;
	}

	private EntityBox firstMatch(Predicate<EntityBox> test) {
		for (EntityBox table : scene.tables()) {
			if (test.test(table)) {
				return table;
			}
		}
		return null;
	}

	private void setActive(EntityBox table) {
		active = table;
		bufferDirty = true;
		onActiveTable.accept(table == null ? null : table.name());
		updateFlowTimer();
		if (table != null) {
			focusOnActive();   // frame the selection (table + its neighbours) in the viewport
		}
		repaint();
	}

	// Smoothly frames the active table together with the tables it links to directly, so a click brings the
	// readable highlighted neighbourhood on screen without letting a distant outlier shrink everything.
	private void focusOnActive() {
		if (active == null) {
			return;
		}
		animateFitTo(smartFocusBounds(scene, active));
	}

	// Include nearby directly linked tables, but reject relationship outliers that would make the selected table
	// tiny and force a long, sluggish camera sweep. The highlight still shows every edge; this only chooses the
	// viewport target.
	static Rectangle2D smartFocusBounds(Scene scene, EntityBox active) {
		int index = scene.indexOf(active);
		if (index < 0) {
			return (Rectangle2D) active.bounds().clone();
		}
		Map<Integer, Neighbour> uniqueNeighbours = new LinkedHashMap<>();
		for (Link edge : scene.edges()) {
			int neighbour = -1;
			if (edge.from() == index) {
				neighbour = edge.to();
			}
			else if (edge.to() == index) {
				neighbour = edge.from();
			}
			if (neighbour >= 0 && neighbour < scene.tables().size() && neighbour != index) {
				EntityBox table = scene.tables().get(neighbour);
				uniqueNeighbours.putIfAbsent(
						neighbour,
						new Neighbour(neighbour, table, distance(active.bounds(), table.bounds())));
			}
		}
		List<Neighbour> neighbours = new ArrayList<>(uniqueNeighbours.values());
		neighbours.sort((a, b) -> {
			int byDistance = Double.compare(a.distance(), b.distance());
			return byDistance != 0 ? byDistance : Integer.compare(a.index(), b.index());
		});

		Rectangle2D bounds = (Rectangle2D) active.bounds().clone();
		double maxDiagonal = Math.max(
				diagonal(active.bounds()) * FOCUS_MAX_NEIGHBOUR_DIAGONAL,
				FOCUS_MIN_NEIGHBOUR_DIAGONAL);
		int included = 0;
		for (Neighbour neighbour : neighbours) {
			if (included >= FOCUS_MAX_NEIGHBOURS) {
				break;
			}
			Rectangle2D candidate = bounds.createUnion(neighbour.table().bounds());
			if (diagonal(candidate) <= maxDiagonal) {
				bounds = candidate;
				included++;
			}
		}
		return bounds;
	}

	private record Neighbour(int index, EntityBox table, double distance) {
	}

	private static double distance(Rectangle2D a, Rectangle2D b) {
		return Point2D.distance(a.getCenterX(), a.getCenterY(), b.getCenterX(), b.getCenterY());
	}

	private static double diagonal(Rectangle2D bounds) {
		return Math.hypot(bounds.getWidth(), bounds.getHeight());
	}

	// Eases the camera (scale + offset) to frame a world rectangle, reusing the scaled-buffer blit during the
	// move and re-baking crisply once it lands — so it's smooth even on a large diagram.
	private void animateFitTo(Rectangle2D world) {
		if (getWidth() == 0 || getHeight() == 0 || world.getWidth() <= 0 || world.getHeight() <= 0) {
			return;
		}
		double margin = FIT_MARGIN + 40;
		double target = clampScale(Math.min(
				(getWidth() - margin) / world.getWidth(),
				(getHeight() - margin) / world.getHeight()));
		target = Math.min(target, FOCUS_MAX_SCALE);   // don't slam in too close for a small neighbourhood
		double targetX = (getWidth() - world.getWidth() * target) / 2 - world.getX() * target;
		double targetY = (getHeight() - world.getHeight() * target) / 2 - world.getY() * target;
		double startScale = scale;
		double startX = offsetX;
		double startY = offsetY;
		double dScale = target - startScale;
		double dX = targetX - startX;
		double dY = targetY - startY;
		if (cameraTimer != null && cameraTimer.isRunning()) {
			cameraTimer.stop();
		}
		cameraTargetScale = target;
		cameraTargetX = targetX;
		cameraTargetY = targetY;
		if (Math.abs(dScale) < 1e-4 && Math.hypot(dX, dY) < 1) {
			return;   // already framed
		}
		if (!animating || LightweightMode.isOn()
				|| shouldJumpCamera(getWidth(), getHeight(), startScale, target, dX, dY)) {
			moveCameraTo(target, targetX, targetY);   // long jumps feel faster and avoid many stale-buffer blits
			updateFlowTimer();
			return;
		}
		flowTimer.stop();   // redraw the highlight while moving; resume the decorative flow once the camera lands
		final int frames = 16;
		final int[] step = { 0 };
		cameraTimer = new Timer(16, null);
		cameraTimer.addActionListener(ev -> {
			step[0]++;
			double t = Math.min(1.0, step[0] / (double) frames);
			double ease = t * t * (3 - 2 * t);   // smoothstep
			scale = startScale + dScale * ease;
			offsetX = startX + dX * ease;
			offsetY = startY + dY * ease;
			repaint();
			onViewChanged.run();
			if (t >= 1.0) {
				cameraTimer.stop();
				bufferDirty = true;   // re-bake crisp at the framed position
				repaint();
				updateFlowTimer();
			}
		});
		cameraTimer.start();
	}

	private void moveCameraTo(double targetScale, double targetX, double targetY) {
		scale = targetScale;
		offsetX = targetX;
		offsetY = targetY;
		bufferDirty = true;
		repaint();
		onViewChanged.run();
	}

	static boolean shouldJumpCamera(int viewportWidth, int viewportHeight, double startScale, double targetScale,
			double deltaX, double deltaY) {
		if (viewportWidth <= 0 || viewportHeight <= 0) {
			return false;
		}
		double viewport = Math.hypot(viewportWidth, viewportHeight);
		double travel = Math.hypot(deltaX, deltaY);
		double start = Math.max(startScale, MIN_SCALE);
		double target = Math.max(targetScale, MIN_SCALE);
		double ratio = Math.max(start / target, target / start);
		return travel > viewport * CAMERA_JUMP_VIEWPORTS || ratio > CAMERA_JUMP_SCALE_RATIO;
	}

	// Traces and lights the shortest join path between two tables. Clears the single-table selection so the
	// whole scene shows undimmed behind the route; falls back to plain selection when nothing connects them.
	private void tracePath(EntityBox from, EntityBox to) {
		List<Integer> route = JoinPath.shortest(scene.edges(), scene.indexOf(from), scene.indexOf(to));
		if (route.isEmpty()) {
			clearPath();
			setActive(to);
			return;
		}
		pathEdges = route;
		pathFrom = scene.indexOf(from);
		pathTo = scene.indexOf(to);
		active = null;                       // drop the dim so the full scene reads behind the route
		onActiveTable.accept(null);
		bufferDirty = true;
		updateFlowTimer();
		repaint();
	}

	// Drops the click-spotlight and any traced join path — the diagram returns to its calm whole-scene state.
	// Hosts call this when the diagram's tab is hidden, so a stale selection never greets the reader on return.
	void clearHighlight() {
		clearPath();
		setActive(null);
	}

	private void clearPath() {
		if (pathEdges.isEmpty()) {
			return;
		}
		pathEdges = List.of();
		pathFrom = -1;
		pathTo = -1;
		updateFlowTimer();
		repaint();
	}

	// The flow animation ticks while a table is selected or a path is traced, and is otherwise idle. It runs
	// at any zoom (the flow strokes only the viewport-visible run of each edge), so it never blinks out when
	// you zoom right in. Too many lit edges *on screen* is the one thing that can't animate smoothly, so past
	// the renderer's cap we leave the timer stopped — the edges still light up (drawn statically), just without
	// the flow. Counting visible edges (not all of a hub's) means a massively-referenced table still flows once
	// you're zoomed in on a readable slice of it; the count is re-checked whenever the viewport settles.
	private void updateFlowTimer() {
		int litEdges = (active != null ? visibleCount(edgesTouching(active)) : 0) + visibleCount(pathEdges);
		boolean wantFlow = animating   // a host can pause the decorative flow (e.g. while its tab is hidden)
				&& !LightweightMode.isOn()   // lightweight: no flowing-edge animation at all
				&& (active != null || !pathEdges.isEmpty())
				&& SceneRenderer.flowAnimates(scene, scale * deviceScale())   // zoomed far out: static highlight
				&& litEdges <= SceneRenderer.MAX_FLOW_EDGES;
		if (wantFlow) {
			if (!flowTimer.isRunning()) {
				flowTickNanos = 0;   // fresh start: don't count the paused span as elapsed animation time
				flowTimer.start();
			}
		}
		else {
			flowTimer.stop();
		}
	}

	// The display's HiDPI factor — the same device zoom the renderer's LOD sees, for gating outside a paint.
	// The chevron/comet trails ride the flattened polylines. Flatness is a world-unit tolerance, so what is
	// sub-pixel at 1:1 becomes a visible gap between trail and stroked spline once zoomed in — re-flatten
	// finer as the view closes in (and coarser again zoomed out, keeping the polylines cheap).
	private double flattenedFor = 1;

	private double effectiveScale() {
		return Math.max(1, scale * deviceScale());
	}

	private double flattenFlatness() {
		return 1.5 / effectiveScale();
	}

	private void refreshFlatsForScale() {
		double effective = effectiveScale();
		double ratio = effective / flattenedFor;
		if (ratio < 1.4 && ratio > 0.7) {
			return;   // still within tolerance of the zoom the polylines were flattened for
		}
		List<double[][]> flats = new ArrayList<>(paths.size());
		double flatness = flattenFlatness();
		for (Path2D p : paths) {
			flats.add(SceneRenderer.flatten(p, flatness));
		}
		flatPaths = flats;
		flattenedFor = effective;
	}

	private double deviceScale() {
		GraphicsConfiguration gc = getGraphicsConfiguration();
		return gc == null ? 1.0 : gc.getDefaultTransform().getScaleX();
	}

	// How many of the given edges touch the viewport — the flow animation's real per-frame workload. Bounds
	// are padded by 1px so a perfectly straight (zero-area) edge still counts. Before the first geometry pass
	// there are no bounds yet; every edge counts as visible, and the settle re-check corrects it.
	private int visibleCount(List<Integer> edgeIndices) {
		if (edgeIndices.isEmpty()) {
			return 0;
		}
		Rectangle2D visible = visibleWorld(24);
		int count = 0;
		for (int i : edgeIndices) {
			if (i < 0 || i >= edgeBounds.size()) {
				count++;
				continue;
			}
			Rectangle2D b = edgeBounds.get(i);
			if (visible.intersects(b.getX() - 1, b.getY() - 1, b.getWidth() + 2, b.getHeight() + 2)) {
				count++;
			}
		}
		return count;
	}

	/** On a popup trigger over a table, shows its context menu and returns true (so press/drag is skipped). */
	private boolean maybeShowMenu(MouseEvent e) {
		if (!e.isPopupTrigger()) {
			return false;
		}
		menuTable = scene.tableAt(toWorld(e.getPoint()));
		if (menuTable != null && tableMenu.getComponentCount() > 0) {
			tableMenu.show(this, e.getX(), e.getY());
			return true;
		}
		return false;
	}

	private Point2D.Double toWorld(Point screen) {
		return new Point2D.Double((screen.x - offsetX) / scale, (screen.y - offsetY) / scale);
	}

	// Motion is opt-in. The selected state and relationship highlight always remain; this switch only governs
	// decorative flow and viewport transitions, so a still diagram stays fully interactive.
	private boolean animating;

	void setAnimating(boolean value) {
		if (animating != value) {
			animating = value;
			if (!value) {
				settleMotion();
			}
			updateFlowTimer();
		}
	}

	boolean isAnimating() {
		return animating;
	}

	// A hidden tab can resume after its cached buffer was baked under a different peer layout or after a view
	// transition was interrupted. Keep the reader's viewport and selection, but always re-bake those pixels.
	void refreshSurface() {
		if (disposed) {
			return;
		}
		resizing = false;
		resizeRedraw.stop();
		placeholderFade.stop();
		placeholderAlpha = 0f;
		placeholderTarget = 0f;
		resizeArmed = false;
		armScheduled = false;
		armResize.stop();
		viewSettle.stop();
		bufferDirty = true;
		revalidate();
		repaint();
	}

	// Finish an in-flight transition at its requested destination before disabling motion, rather than leaving
	// the viewport stranded part-way through a glide.
	private void settleMotion() {
		if (zoomGlide.isRunning()) {
			zoomGlide.stop();
			zoomTo(zoomPivot, zoomTargetScale);
		}
		if (cameraFollow.isRunning()) {
			cameraFollow.stop();
			offsetX = followTargetX;
			offsetY = followTargetY;
			bufferDirty = true;
			repaint();
			onViewChanged.run();
		}
		if (cameraTimer != null && cameraTimer.isRunning()) {
			cameraTimer.stop();
			moveCameraTo(cameraTargetScale, cameraTargetX, cameraTargetY);
		}
	}

	void zoomIn() {
		glideZoomAt(new Point(getWidth() / 2, getHeight() / 2), ZOOM_STEP);
	}

	void zoomOut() {
		glideZoomAt(new Point(getWidth() / 2, getHeight() / 2), 1 / ZOOM_STEP);
	}

	/** Zooms by {@code factor} while keeping the world point under {@code pivot} fixed on screen. */
	private void zoomAt(Point pivot, double factor) {
		zoomTo(pivot, scale * factor);
	}

	/** Sets the scale to {@code nextScale} while keeping the world point under {@code pivot} fixed on screen. */
	private void zoomTo(Point pivot, double nextScale) {
		cameraFollow.stop();   // a manual zoom takes over from an in-flight minimap glide
		double next = clampScale(nextScale);
		double applied = next / scale;
		offsetX = pivot.x - (pivot.x - offsetX) * applied;
		offsetY = pivot.y - (pivot.y - offsetY) * applied;
		scale = next;
		viewSettle.restart();   // blit the cached buffer scaled now; re-bake crisp once zooming stops
		updateFlowTimer();      // particles are zoom-gated — start/stop the ticker as we cross the threshold
		repaint();
		onViewChanged.run();
	}

	/** Glides toward {@code factor} times the pending target scale; rapid steps accumulate into one motion. */
	private void glideZoomAt(Point pivot, double factor) {
		if (cameraTimer != null && cameraTimer.isRunning()) {
			cameraTimer.stop();   // the user's zoom wins over an in-flight table framing
		}
		double base = zoomGlide.isRunning() ? zoomTargetScale : scale;
		zoomTargetScale = clampScale(base * factor);
		zoomPivot = pivot;
		if (!animating || LightweightMode.isOn()) {
			zoomGlide.stop();
			zoomTo(zoomPivot, zoomTargetScale);   // no animation in lightweight mode — land in one step
			return;
		}
		if (!zoomGlide.isRunning()) {
			zoomGlide.start();
		}
	}

	// One glide frame: approach the target scale about the pivot; land and stop when within a hair.
	private void tickZoomGlide() {
		double next = scale + (zoomTargetScale - scale) * ZOOM_EASE;
		if (Math.abs(zoomTargetScale - next) < zoomTargetScale * 0.002) {
			next = zoomTargetScale;
			zoomGlide.stop();
		}
		zoomTo(zoomPivot, next);
	}

	private static double clampScale(double value) {
		return Math.max(MIN_SCALE, Math.min(MAX_SCALE, value));
	}

	/** Centres the whole diagram in the viewport at a scale that fits, with a margin. */
	void fitToView() {
		if (computeFit()) {
			bufferDirty = true;   // an explicit fit (first show, Fit button) re-bakes crisp right away
			repaint();
			onViewChanged.run();
		}
	}

	// Handles a window/sidebar resize. Rather than fight to redraw the diagram every frame (which flickers/jumps),
	// we show a calm placeholder while the size is changing and debounce the real render: every resize event marks
	// us "resizing" and restarts the timer, so the one clean reframe+re-bake fires only once the drag has been
	// still for ~180ms. Cheap during the drag (just the placeholder), crisp the instant you stop.
	// Enters resize-hold: show the placeholder now and (re)arm the debounce. Called on every actual resize event,
	// and also proactively by the shell the instant the sidebar divider is pressed — so grabbing the gutter shows
	// the placeholder before the first resize even arrives, which feels snappier.
	void holdForResize() {
		resizing = true;
		resizeRedraw.restart();
		if (buffer != null) {        // snap straight to the placeholder — no fade-in (that kept the live diagram
			placeholderAlpha = 1f;   // visible underneath and re-exposed the flicker/jump). Only the fade-out animates.
			placeholderTarget = 1f;
			placeholderFade.stop();
		}
		repaint();
	}

	// Drives the placeholder cross-fade toward its target (0 or 1), then stops.
	private void startFade() {
		if (!placeholderFade.isRunning()) {
			placeholderFade.start();
		}
	}

	private void stepFade() {
		float step = 0.12f;   // ~140ms for a full fade
		placeholderAlpha = placeholderAlpha < placeholderTarget
				? Math.min(placeholderTarget, placeholderAlpha + step)
				: Math.max(placeholderTarget, placeholderAlpha - step);
		if (Math.abs(placeholderAlpha - placeholderTarget) < 1e-3) {
			placeholderAlpha = placeholderTarget;
			placeholderFade.stop();
		}
		repaint();
	}

	// Paints the placeholder over whatever's already drawn, at the current cross-fade alpha.
	private void overlayPlaceholder(Graphics g) {
		Graphics2D pg = (Graphics2D) g.create();
		pg.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
				Math.max(0f, Math.min(1f, placeholderAlpha))));
		paintResizingPlaceholder(pg);
		pg.dispose();
	}

	// Sets scale/offset to frame the whole diagram in the viewport; false when there's nothing to fit yet.
	private boolean computeFit() {
		Rectangle2D world = scene.worldBounds();
		if (world == null || getWidth() == 0 || getHeight() == 0) {
			return false;
		}
		double fitScale = Math.min(
				(getWidth() - FIT_MARGIN) / world.getWidth(),
				(getHeight() - FIT_MARGIN) / world.getHeight());
		scale = clampScale(Math.min(fitScale, 1.0));
		offsetX = (getWidth() - world.getWidth() * scale) / 2 - world.getX() * scale;
		offsetY = (getHeight() - world.getHeight() * scale) / 2 - world.getY() * scale;
		return true;
	}

	/** Pauses the edge-flow animation — the window owner calls this when minimised/unfocused so a selected
	 *  table's decorative 30fps overlay doesn't burn CPU while no one is looking (helps battery / older CPUs). */
	void suspendAnimation() {
		flowTimer.stop();
	}

	/** Resumes the flow animation if the current selection still warrants it. */
	void resumeAnimation() {
		updateFlowTimer();
	}

	@Override
	public void addNotify() {
		super.addNotify();
		if (!disposed && !lightweightListening) {
			LightweightMode.addListener(lightweightListener);
			lightweightListening = true;
		}
		renderer.setLightweight(LightweightMode.isOn());
		updateFlowTimer();
	}

	@Override
	public void removeNotify() {
		stopTransientWork();
		removeLightweightListener();
		super.removeNotify();
	}

	void disposeSurface() {
		disposed = true;
		buildGeneration++;
		stopTransientWork();
		removeLightweightListener();
		layoutExecutor.shutdownNow();   // abandon any in-flight layout for a surface that's truly closing
	}

	private void stopTransientWork() {
		flowTimer.stop();   // don't keep ticking while the diagram is not in a visible hierarchy
		viewSettle.stop();
		resizeRedraw.stop();
		armResize.stop();
		placeholderFade.stop();
		zoomGlide.stop();
		cameraFollow.stop();
		if (cameraTimer != null) {
			cameraTimer.stop();
		}
	}

	private void removeLightweightListener() {
		if (lightweightListening) {
			LightweightMode.removeListener(lightweightListener);
			lightweightListening = false;
		}
	}

	@Override
	public Dimension getPreferredSize() {
		return new Dimension(900, 600);
	}
}
