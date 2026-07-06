package io.github.wesleym.mappa;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MappaCodecEdgeTest {

	@Test
	void roundTripsTheFullCommerceSchemaExactly() throws IOException {
		MappaMap map = Fixtures.commerce();
		MappaMap read = Mappa.read(map.toBytes());

		assertEquals(map.title(), read.title());
		assertEquals(map.entities(), read.entities());
		assertEquals(map.relationships(), read.relationships());
	}

	@Test
	void preservesSelfReferencesSuggestedFlagsAndEntityKinds() throws IOException {
		MappaMap read = Mappa.read(Fixtures.commerce().toBytes());

		MappaMap.Relationship selfRef = read.relationships().stream()
				.filter(r -> r.fromEntity().equals("categories") && r.toEntity().equals("categories"))
				.findFirst().orElseThrow();
		assertEquals("parent_id", selfRef.fromField());

		assertTrue(read.relationships().stream().anyMatch(MappaMap.Relationship::suggested), "suggested edge kept");
		assertEquals(MappaMap.EntityKind.VIEW, read.entities().stream()
				.filter(e -> e.name().equals("revenue_by_day")).findFirst().orElseThrow().kind());
	}

	@Test
	void roundTripsAnEmptyMap() throws IOException {
		MappaMap read = Mappa.read(new MappaMap("Nothing", List.of(), List.of()).toBytes());
		assertEquals("Nothing", read.title());
		assertTrue(read.isEmpty());
	}

	@Test
	void roundTripsUnicodeNamesAndTypes() throws IOException {
		MappaMap map = Mappa.map("Ubersicht")
				.entity("Bestellung", t -> t.primaryKey("Schlussel", "ganzzahl").column("betrag", "dezimal"))
				.build();
		MappaMap read = Mappa.read(map.toBytes());
		assertEquals(map.entities(), read.entities());
	}

	@Test
	void staysSmallerThanTheNaiveTextItEncodes() {
		// A compact document: de-duplicated string table + deflate. It must beat the raw concatenation
		// of every name and type it carries (which repeats "text", "decimal", "uuid", ... many times).
		MappaMap map = Fixtures.commerce();
		int naiveTextBytes = 0;
		for (MappaMap.Entity e : map.entities()) {
			naiveTextBytes += utf8(e.name());
			for (MappaMap.Field f : e.fields()) {
				naiveTextBytes += utf8(f.name()) + utf8(f.type());
			}
		}
		assertTrue(map.toBytes().length < naiveTextBytes,
				"native document (" + map.toBytes().length + "B) was not smaller than its raw text ("
						+ naiveTextBytes + "B)");
	}

	private static int utf8(String value) {
		return value.getBytes(StandardCharsets.UTF_8).length;
	}

	@Test
	void rejectsForeignBytes() {
		assertThrows(IOException.class, () -> Mappa.read("not a mappa file".getBytes(StandardCharsets.UTF_8)));
		assertThrows(IOException.class, () -> Mappa.read(new byte[0]));
	}

	@Test
	void rejectsAnUnsupportedVersion() {
		byte[] bytes = Fixtures.store().toBytes();
		bytes[5] = 99;   // the version byte, right after the 5-byte MAPPA magic
		assertThrows(IOException.class, () -> Mappa.read(bytes));
	}

	@Test
	void rejectsATruncatedBody() {
		byte[] bytes = Fixtures.store().toBytes();
		assertThrows(IOException.class, () -> Mappa.read(Arrays.copyOf(bytes, bytes.length - 6)));
	}
}
