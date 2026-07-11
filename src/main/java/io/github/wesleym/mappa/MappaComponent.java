package io.github.wesleym.mappa;

import io.github.wesleym.mappa.internal.render.StaticRender;

import javax.swing.JComponent;

import java.awt.BorderLayout;

/**
 * The live Swing view returned by {@link MappaView#component()}: the interactive diagram plus a small set of
 * host-drivable viewport controls. It carries the full built-in interaction layer (pan, wheel-zoom, drag,
 * click-spotlight, shift-click join-path trace, search, minimap, context actions); these methods let a host
 * that draws its own chrome drive the same viewport programmatically — a zoom button, a "fit" action, or
 * pausing the decorative edge-flow while the component is off-screen.
 *
 * <p>Add it to any container like a normal component. Build one through {@link MappaView#component()}.
 */
public final class MappaComponent extends JComponent {

	private final transient JComponent surface;   // the internal live canvas, driven through StaticRender

	MappaComponent(JComponent surface) {
		this.surface = surface;
		setLayout(new BorderLayout());
		add(surface, BorderLayout.CENTER);
	}

	/** Zooms the viewport in one step, about the centre — the same motion as a wheel notch. */
	public void zoomIn() {
		StaticRender.zoomIn(surface);
	}

	/** Zooms the viewport out one step, about the centre. */
	public void zoomOut() {
		StaticRender.zoomOut(surface);
	}

	/** Frames the whole diagram in the viewport at a fitting scale — the double-click-to-fit action. */
	public void fitView() {
		StaticRender.fitView(surface);
	}

	/**
	 * Turns live diagram motion on or off. When disabled, relationship flow, camera transitions, and stepped
	 * zoom changes settle immediately. It is off by default; a host can opt in for a surface where motion helps
	 * orient the reader, and pause it again while the component is hidden.
	 */
	public void setAnimating(boolean animating) {
		StaticRender.setAnimating(surface, animating);
	}

	/** Whether live diagram motion is currently enabled. New components start with it disabled. */
	public boolean isAnimating() {
		return StaticRender.isAnimating(surface);
	}

	/**
	 * Rebuilds the live pixels after a host restores this component from a hidden tab. The viewport, selection,
	 * and hand-arranged tables are retained; only transient cached rendering is discarded.
	 */
	public void refreshSurface() {
		StaticRender.refreshSurface(surface);
	}

	/**
	 * Drops the click-spotlight and any traced join path, returning the diagram to its calm whole-scene state.
	 * Hosts typically call this when the component's tab is hidden, so a stale selection never greets the
	 * reader on return.
	 */
	public void clearHighlight() {
		StaticRender.clearHighlight(surface);
	}

	/**
	 * Swaps the map shown, re-laying-out in place — for when the set of entities or relationships changes (a
	 * host narrowing the view, adding inferred edges, …). Cheaper and smoother than building a new component.
	 */
	public void setMap(MappaMap map) {
		StaticRender.setMap(surface, map);
	}

	/** Changes the placement mode live, re-laying-out (a new layout discards any hand-arrangement). */
	public void setLayout(MappaLayout layout) {
		StaticRender.setLayout(surface, layout);
	}

	/** Changes the edge wire style live (no re-layout — just re-strokes the routes). */
	public void setEdges(MappaEdges edges) {
		StaticRender.setEdges(surface, edges);
	}

	/** Changes how much field detail shows live, re-laying-out for the new box heights. */
	public void setDetail(MappaDetail detail) {
		StaticRender.setDetail(surface, detail);
	}

	/** Changes the backdrop pattern live. */
	public void setBackground(MappaBackground background) {
		StaticRender.setBackground(surface, background);
	}

	/** Shows or hides the joined-column labels on relationships live, re-laying-out to make room. */
	public void setRelationshipLabels(boolean show) {
		StaticRender.setRelationshipLabels(surface, show);
	}

	/** When on, draws only the suggested (inferred) relationships, hiding the declared ones; live. */
	public void setInferredOnly(boolean inferredOnly) {
		StaticRender.setInferredOnly(surface, inferredOnly);
	}

	/**
	 * Finds an entity by name — exact, then prefix, then substring, case-insensitive — and spotlights it:
	 * selects it, frames it in the viewport, and highlights its relationships, exactly as clicking it would.
	 * Returns whether one matched. Lets a host wire its own search box to mappa's find-and-highlight.
	 */
	public boolean reveal(String query) {
		return StaticRender.reveal(surface, query);
	}
}
