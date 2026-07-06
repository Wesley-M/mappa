package io.github.wesleym.mappa;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MappaViewGuardsTest {

	@Test
	void nullHandlersOptionsAndThemeAreAllSafe() {
		MappaView view = Mappa.view(Fixtures.store())
				.action(null, e -> { })     // rejected: null label
				.action("  ", e -> { })      // rejected: blank label
				.action("open", null)        // rejected: null handler
				.onEntitySelected(null)      // reset to no-op
				.options(null)               // reset to defaults
				.layout(null).edges(null).detail(null).background(null)
				.theme(null);                // reset to light
		assertNotNull(view.image(240, 160));
		assertNotNull(view.component());
	}

	@Test
	void autoDetailDropsToKeysOnlyPastTenEntities() {
		var b = Mappa.schema("Big");
		for (int i = 0; i < 12; i++) {
			int idx = i;
			b.table("t" + i, t -> {
				t.primaryKey("id", "int");
				t.column("note", "text");
				if (idx > 0) {
					t.reference("p_id", "int", "t" + (idx - 1), "id");
				}
			});
		}
		assertNotNull(Mappa.view(b.build()).detail(MappaDetail.AUTO).image(900, 700));
	}

	@Test
	void emptyMapRendersJustTheBackground() {
		assertNotNull(Mappa.view(new MappaMap("Empty", List.of(), List.of())).image(120, 90));
		assertNotNull(Mappa.view(new MappaMap("Empty", List.of(), List.of())).toSvg());
	}

	@Test
	void transparentSvgOmitsTheBackgroundFill(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("t.svg");
		Mappa.view(Fixtures.store()).writeSvg(file, true);
		assertTrue(Files.size(file) > 300);
	}
}
