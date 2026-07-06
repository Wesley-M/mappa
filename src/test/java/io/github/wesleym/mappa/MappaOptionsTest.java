package io.github.wesleym.mappa;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class MappaOptionsTest {

	@Test
	void defaultsAreTheAutoFamily() {
		MappaOptions o = MappaOptions.defaults();
		assertEquals(MappaLayout.AUTO, o.layout());
		assertEquals(MappaEdges.AUTO, o.edges());
		assertEquals(MappaDetail.AUTO, o.detail());
		assertEquals(MappaBackground.DOTS, o.background());
		assertFalse(o.relationshipLabels());
	}

	@Test
	void fluentSettersReturnANewValueLeavingTheOriginalUntouched() {
		MappaOptions base = MappaOptions.defaults();
		MappaOptions next = base.layout(MappaLayout.RADIAL).edges(MappaEdges.ORTHOGONAL)
				.detail(MappaDetail.KEYS).background(MappaBackground.GRID).relationshipLabels(true);

		assertEquals(MappaLayout.RADIAL, next.layout());
		assertEquals(MappaEdges.ORTHOGONAL, next.edges());
		assertEquals(MappaDetail.KEYS, next.detail());
		assertEquals(MappaBackground.GRID, next.background());
		assertEquals(MappaLayout.AUTO, base.layout());
		assertEquals(MappaBackground.DOTS, base.background());
	}

	@Test
	void nullOptionsFallBackToTheirDefault() {
		MappaOptions o = MappaOptions.defaults().layout(null).edges(null).detail(null).background(null);
		assertEquals(MappaLayout.AUTO, o.layout());
		assertEquals(MappaEdges.AUTO, o.edges());
		assertEquals(MappaDetail.AUTO, o.detail());
		assertEquals(MappaBackground.DOTS, o.background());
	}

	@Test
	void viewOptionsNullResetsToDefaults() {
		MappaView view = Mappa.view(Fixtures.store());
		assertSame(view, view.options(null));   // fluent, and does not throw
	}
}
