package io.github.wesleym.mappa.internal.render;

import io.github.wesleym.mappa.Mappa;
import io.github.wesleym.mappa.MappaMap;
import io.github.wesleym.mappa.MappaMinimap;
import io.github.wesleym.mappa.MappaOptions;
import io.github.wesleym.mappa.MappaTheme;
import io.github.wesleym.mappa.MappaView;
import org.junit.jupiter.api.Test;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JSeparator;

import java.awt.Component;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The entity right-click menu: actions registered on the view must reach the live canvas — the mappa
 * migration once dropped them between {@code MappaView.component()} and the canvas, silently killing
 * every context-menu item (including Remove from diagram).
 */
class CanvasMenuTest {

	private static MappaMap map() {
		return Mappa.schema("Store")
				.table("orders", t -> t.primaryKey("id", "uuid").column("total", "decimal"))
				.build();
	}

	private static MappaCanvas canvas(List<MappaView.EntityAction> actions) {
		return (MappaCanvas) StaticRender.live(map(), MappaOptions.defaults(), MappaTheme.light(), actions,
				null, null, MappaMinimap.AUTO);
	}

	private static List<String> labels(JPopupMenu menu) {
		List<String> out = new ArrayList<>();
		for (Component c : menu.getComponents()) {
			if (c instanceof JMenuItem item) {
				out.add(item.getText());
			}
		}
		return out;
	}

	private static boolean hasSeparator(JPopupMenu menu) {
		for (Component c : menu.getComponents()) {
			if (c instanceof JSeparator) {
				return true;
			}
		}
		return false;
	}

	@Test
	void registeredActionsReachTheCanvasMenuInOrder() {
		MappaCanvas canvas = canvas(List.of(
				new MappaView.EntityAction("Show data", e -> { }),
				new MappaView.EntityAction("Show insights", e -> { }),
				MappaView.EntityAction.SEPARATOR,
				new MappaView.EntityAction("Remove from diagram", e -> { })));
		assertEquals(List.of("Show data", "Show insights", "Remove from diagram"), labels(canvas.tableMenu()));
		assertTrue(hasSeparator(canvas.tableMenu()), "the divider between the groups is kept");
	}

	@Test
	void aLeadingSeparatorIsNotDrawn() {
		MappaCanvas canvas = canvas(List.of(
				MappaView.EntityAction.SEPARATOR,
				new MappaView.EntityAction("Remove from diagram", e -> { })));
		assertEquals(List.of("Remove from diagram"), labels(canvas.tableMenu()));
		assertFalse(hasSeparator(canvas.tableMenu()), "a menu must not open on a rule");
	}

	@Test
	void noActionsLeavesTheMenuEmpty() {
		assertEquals(0, canvas(List.of()).tableMenu().getComponentCount());
	}

	@Test
	void menuActionsResolveTheLiveEntityByNameCaseInsensitively() {
		MappaCanvas canvas = canvas(List.of());
		MappaMap.Entity entity = canvas.entityNamed("ORDERS");
		assertNotNull(entity, "the clicked box's name resolves against the live map");
		assertEquals("orders", entity.name());
	}
}
