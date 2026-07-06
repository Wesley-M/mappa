package io.github.wesleym.mappa;

import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MappaThemeTest {

	@Test
	void lightAndDarkCarryDistinctDefaults() {
		assertFalse(MappaTheme.light().isDark());
		assertTrue(MappaTheme.dark().isDark());
		assertTrue(MappaTheme.of(true).isDark());
		assertFalse(MappaTheme.of(false).isDark());
		assertNotEquals(MappaTheme.light().background(), MappaTheme.dark().background());
	}

	@Test
	void everyDefaultColourIsPopulated() {
		MappaTheme theme = MappaTheme.dark();
		for (Color c : new Color[] { theme.background(), theme.surface(), theme.text(), theme.muted(),
				theme.line(), theme.accent(), theme.entityHeader(), theme.viewHeader(), theme.reference(),
				theme.suggestedReference(), theme.inbound(), theme.outbound(), theme.clusterRegion() }) {
			assertTrue(c != null, "no null colour in the default palette");
		}
	}

	@Test
	void fluentSetterChangesOnlyItsOwnField() {
		MappaTheme base = MappaTheme.light();
		Color accent = new Color(0x255E93);   // distinct from the default light accent
		MappaTheme themed = base.accent(accent);

		assertEquals(accent, themed.accent());
		assertEquals(base.background(), themed.background());
		assertEquals(base.surface(), themed.surface());
		assertEquals(base.text(), themed.text());
		assertEquals(base.isDark(), themed.isDark());
		assertNotEquals(base.accent(), themed.accent());
	}

	@Test
	void nullOverrideFallsBackToTheDefault() {
		assertEquals(MappaTheme.dark().accent(), MappaTheme.dark().accent(null).accent());
	}
}
