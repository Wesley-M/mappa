package io.github.wesleym.mappa;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Byte-level codec paths the round-trip can't reach: uncompressed bodies, out-of-range ids, dangling edges. */
class MappaCodecLowLevelTest {

	// A hand-built UNCOMPRESSED document (flag bit clear) — exercises the non-inflating read branch, and an
	// out-of-range entity-kind ordinal that must fall back to ENTITY.
	@Test
	void readsAnUncompressedBodyAndClampsUnknownKind() throws IOException {
		byte[] doc = document(4 /* field typeId "int" */);
		MappaMap map = Mappa.read(doc);
		assertEquals(1, map.entities().size());
		assertEquals(MappaMap.EntityKind.ENTITY, map.entities().get(0).kind(), "kind ordinal 99 clamps to ENTITY");
		assertEquals("a", map.entities().get(0).name());
	}

	@Test
	void rejectsAnOutOfRangeStringId() {
		byte[] doc = document(99 /* field typeId beyond the string table */);
		assertThrows(IOException.class, () -> Mappa.read(doc));
	}

	// A relationship whose endpoints aren't in the entity list round-trips to empty endpoint names (index -1).
	@Test
	void danglingRelationshipEndpointsBecomeEmpty() throws IOException {
		MappaMap map = new MappaMap("T",
				List.of(new MappaMap.Entity("a", MappaMap.EntityKind.TABLE,
						List.of(new MappaMap.Field("id", "int", true, false)))),
				List.of(new MappaMap.Relationship("ghost", "x", "a", "id", false),
						new MappaMap.Relationship("a", "id", "ghost", "y", false)));

		MappaMap read = Mappa.read(map.toBytes());
		assertEquals("", read.relationships().get(0).fromEntity(), "unknown 'from' entity → empty");
		assertEquals("", read.relationships().get(1).toEntity(), "unknown 'to' entity → empty");
	}

	// MAPPA magic, version 2, flags=0 (uncompressed); one entity "a" with kind ordinal 99 and one field.
	private static byte[] document(int fieldTypeId) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		for (char c : "MAPPA".toCharArray()) {
			b.write(c);
		}
		b.write(2);   // version
		b.write(0);   // flags: uncompressed
		var(b, 5);
		string(b, "");
		string(b, "T");
		string(b, "a");
		string(b, "id");
		string(b, "int");
		var(b, 1);    // title id -> "T"
		var(b, 1);    // entity count
		var(b, 2);    // entity name id -> "a"
		var(b, 99);   // kind ordinal (out of range → ENTITY)
		var(b, 1);    // field count
		var(b, 3);    // field name id -> "id"
		var(b, fieldTypeId);
		var(b, 1);    // flags: primary key
		var(b, 0);    // relationship count
		var(b, 0);    // position count (v2)
		return b.toByteArray();
	}

	private static void var(ByteArrayOutputStream out, int value) {
		int v = value;
		while ((v & ~0x7F) != 0) {
			out.write((v & 0x7F) | 0x80);
			v >>>= 7;
		}
		out.write(v);
	}

	private static void string(ByteArrayOutputStream out, String value) {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		var(out, bytes.length);
		out.writeBytes(bytes);
	}
}
