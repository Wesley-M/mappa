package io.github.wesleym.mappa;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MappaBuilderTest {

	@Test
	void buildsSchemaMapsWithReferences() {
		MappaMap map = sample();

		assertEquals("Store", map.title());
		assertEquals(List.of("orders", "customers", "order_status"),
				map.entities().stream().map(MappaMap.Entity::name).toList());
		assertEquals(2, map.relationships().size());
		assertTrue(map.entities().get(0).fields().stream()
				.filter(f -> f.name().equals("customer_id"))
				.findFirst()
				.orElseThrow()
				.reference());
	}

	@Test
	void focusKeepsOnlyTheSubjectAndDirectRelationships() {
		MappaMap focused = sample().focus("orders");

		assertEquals(List.of("orders", "customers", "order_status"),
				focused.entities().stream().map(MappaMap.Entity::name).toList());
		assertEquals(2, focused.relationships().size());
	}

	@Test
	void selectDropsRelationshipsOutsideTheSelection() {
		MappaMap selected = sample().select(List.of("orders", "customers"));

		assertEquals(List.of("orders", "customers"),
				selected.entities().stream().map(MappaMap.Entity::name).toList());
		assertEquals(1, selected.relationships().size());
		assertEquals("customers", selected.relationships().get(0).toEntity());
	}

	static MappaMap sample() {
		return Mappa.schema("Store")
				.table("orders", t -> t
						.primaryKey("id", "uuid")
						.reference("customer_id", "uuid", "customers", "id")
						.reference("status_id", "int", "order_status", "id")
						.column("total", "decimal"))
				.table("customers", t -> t
						.primaryKey("id", "uuid")
						.column("email", "text"))
				.table("order_status", t -> t
						.primaryKey("id", "int")
						.column("name", "text"))
				.build();
	}
}
