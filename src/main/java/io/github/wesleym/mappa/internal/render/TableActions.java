package io.github.wesleym.mappa.internal.render;

import java.util.function.Consumer;

/**
 * Host-supplied entity actions offered on a box's right-click menu, each keyed by the entity name. Any may be
 * {@code null} to omit that item. Mappa's view builder maps its {@code action(label, handler)} entries here.
 */
public record TableActions(Consumer<String> showData, Consumer<String> showInsights, Consumer<String> watch,
		Consumer<String> openFocusedDiagram) {

	public static TableActions none() {
		return new TableActions(null, null, null, null);
	}
}
