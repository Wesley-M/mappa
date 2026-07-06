package io.github.wesleym.mappa;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MappaMapTest {

	@Test
	void isEmptyReflectsEntityCount() {
		assertTrue(new MappaMap("Empty", List.of(), List.of()).isEmpty());
		assertFalse(Fixtures.store().isEmpty());
	}

	@Test
	void constructorDefensivelyCopiesEntities() {
		List<MappaMap.Entity> entities = new ArrayList<>();
		entities.add(new MappaMap.Entity("a", MappaMap.EntityKind.TABLE, List.of()));
		MappaMap map = new MappaMap("M", entities, List.of());

		entities.clear();

		assertEquals(1, map.entities().size());
		assertThrows(UnsupportedOperationException.class,
				() -> map.entities().add(new MappaMap.Entity("b", MappaMap.EntityKind.TABLE, List.of())));
	}

	@Test
	void constructorDropsNullElements() {
		List<MappaMap.Entity> entities = new ArrayList<>();
		entities.add(null);
		entities.add(new MappaMap.Entity("a", MappaMap.EntityKind.ENTITY, List.of()));

		assertEquals(1, new MappaMap("M", entities, List.of()).entities().size());
	}

	@Test
	void nullTitleAndFieldsNormaliseToEmpties() {
		MappaMap.Entity entity = new MappaMap.Entity(null, null, null);

		assertEquals("", entity.name());
		assertEquals(MappaMap.EntityKind.ENTITY, entity.kind());
		assertTrue(entity.fields().isEmpty());
		assertEquals("", new MappaMap(null, List.of(), List.of()).title());
	}

	@Test
	void focusKeepsSubjectPlusDirectNeighboursAndTheirEdges() {
		MappaMap focused = Fixtures.commerce().focus("orders");

		List<String> kept = focused.entities().stream().map(MappaMap.Entity::name).toList();
		assertTrue(kept.contains("orders"));
		assertTrue(kept.contains("customers"));       // orders.customer_id -> customers
		assertTrue(kept.contains("order_status"));    // orders.status_id  -> order_status
		assertTrue(kept.contains("order_items"));     // order_items.order_id -> orders
		assertFalse(kept.contains("products"));       // two hops away, dropped
		assertTrue(focused.relationships().stream()
				.allMatch(r -> r.fromEntity().equals("orders") || r.toEntity().equals("orders")));
	}

	@Test
	void focusIsCaseInsensitiveOnTheSubject() {
		assertEquals(Fixtures.commerce().focus("orders").entities().size(),
				Fixtures.commerce().focus("ORDERS").entities().size());
	}

	@Test
	void selectDropsEdgesWithAnEndOutsideTheSelection() {
		MappaMap selected = Fixtures.store().select(List.of("orders", "customers"));

		assertEquals(List.of("orders", "customers"),
				selected.entities().stream().map(MappaMap.Entity::name).toList());
		assertEquals(1, selected.relationships().size());
		assertEquals("customers", selected.relationships().get(0).toEntity());
	}

	@Test
	void selectWithNullYieldsAnEmptyMap() {
		assertTrue(Fixtures.store().select(null).isEmpty());
	}

	@Test
	void toBytesProducesAFreshArrayEachCall() {
		MappaMap map = Fixtures.store();
		assertNotSame(map.toBytes(), map.toBytes());
	}
}
