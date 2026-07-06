package io.github.wesleym.mappa.internal.render;

import io.github.wesleym.mappa.Mappa;
import io.github.wesleym.mappa.MappaEdges;
import io.github.wesleym.mappa.MappaMap;
import io.github.wesleym.mappa.MappaOptions;
import io.github.wesleym.mappa.MappaTheme;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticRenderExportTest {

	private static MappaMap map() {
		return Mappa.schema("S")
				.table("orders", t -> t.primaryKey("id", "uuid").reference("customer_id", "uuid", "customers", "id"))
				.table("customers", t -> t.primaryKey("id", "uuid").column("email", "text"))
				.build();
	}

	@Test
	void svgHonoursTransparencyAndDirectionalEdges() {
		MappaOptions directional = MappaOptions.defaults().edges(MappaEdges.DIRECTIONAL).relationshipLabels(true);
		assertTrue(StaticRender.svg(map(), directional, MappaTheme.dark(), true).startsWith("<svg"));
		assertTrue(StaticRender.svg(map(), directional, MappaTheme.dark(), false).contains("fill=\""));
	}

	@Test
	void interactiveHtmlHonoursBothTransparenciesAndDirectionalEdges() {
		MappaOptions opts = MappaOptions.defaults();
		MappaOptions directional = opts.edges(MappaEdges.DIRECTIONAL);
		assertTrue(StaticRender.interactiveHtml(map(), directional, MappaTheme.dark(), "T", true).contains("<svg"));
		assertTrue(StaticRender.interactiveHtml(map(), opts, MappaTheme.light(), "T", false).contains("<svg"));
	}

	@Test
	void liveComponentMapsEveryBackdrop() {
		for (io.github.wesleym.mappa.MappaBackground bg : io.github.wesleym.mappa.MappaBackground.values()) {
			assertTrue(io.github.wesleym.mappa.Mappa.view(map())
					.background(bg).component() != null, bg + " maps to a backdrop");
		}
	}
}
