package io.github.wesleym.mappa.internal.common;

import io.github.wesleym.mappa.MappaMap;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FontsTest {

	@Test
	void bundledFontsLoadFromResources() {
		assertEquals("Inter", Fonts.sans(13f).getFamily());
		assertEquals("Inter Semi Bold", Fonts.sansSemiBold(13f).getFamily());
		assertEquals("JetBrains Mono", Fonts.mono(12f).getFamily());
	}

	@Test
	void sizesAndWeightsAreCarriedInTheFace() {
		Font title = Fonts.sansSemiBold(13f);
		assertEquals(13, title.getSize());
		// The weight lives in the outlines; the style flags stay PLAIN so nothing re-emboldens it.
		assertEquals(Font.PLAIN, title.getStyle());
	}

	@Test
	void coversLatinGreekCyrillicAndLabelGlyphs() {
		MappaMap map = new MappaMap("map", List.of(
				new MappaMap.Entity("orders", null, List.of(new MappaMap.Field("id", "int", true, false))),
				new MappaMap.Entity("données_μετρικές", null,
						List.of(new MappaMap.Field("итог", "text", false, false)))),
				List.of(new MappaMap.Relationship("orders", "id", "données_μετρικές", "итог", false)));
		assertTrue(Fonts.covers(map));
		assertTrue(Fonts.mono(12f).canDisplay('→'));   // join-column labels render as "fk → pk"
		assertTrue(Fonts.mono(12f).canDisplay('…'));   // and long rows elide with an ellipsis
	}

	@Test
	void nullMapIsCoveredSoTheEmptyCanvasUsesBundledFonts() {
		assertTrue(Fonts.covers(null));
	}

	@Test
	void cjkEntityNameFallsBackToLogicalFonts() {
		MappaMap map = new MappaMap("map", List.of(
				new MappaMap.Entity("注文", null, List.of(new MappaMap.Field("id", "int", true, false)))),
				List.of());
		assertFalse(Fonts.covers(map));
	}

	@Test
	void cjkFieldOrRelationshipTextFallsBackToLogicalFonts() {
		MappaMap fields = new MappaMap("map", List.of(
				new MappaMap.Entity("orders", null, List.of(new MappaMap.Field("数量", "int", false, false)))),
				List.of());
		assertFalse(Fonts.covers(fields));
		MappaMap relationships = new MappaMap("map", List.of(
				new MappaMap.Entity("orders", null, List.of())),
				List.of(new MappaMap.Relationship("orders", "顧客", "orders", "id", false)));
		assertFalse(Fonts.covers(relationships));
	}
}
