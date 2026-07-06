package io.github.wesleym.mappa;

/**
 * Where — and whether — the live view shows its overview minimap. Mappa is made to be embedded, so a host
 * that draws its own toolbars or overlays can move the minimap out of the way, force it on, or turn it off.
 */
public enum MappaMinimap {

	/** Never show the minimap. */
	OFF,

	/** Show it only once the diagram is large enough to need one, in the bottom-right corner. The default. */
	AUTO,

	/** Always show it in the top-left corner, whatever the diagram's size. */
	TOP_LEFT,

	/** Always show it in the top-right corner, whatever the diagram's size. */
	TOP_RIGHT,

	/** Always show it in the bottom-left corner, whatever the diagram's size. */
	BOTTOM_LEFT,

	/** Always show it in the bottom-right corner, whatever the diagram's size. */
	BOTTOM_RIGHT
}
