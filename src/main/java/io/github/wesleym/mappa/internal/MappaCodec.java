package io.github.wesleym.mappa.internal;

import io.github.wesleym.mappa.MappaMap;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/** Versioned native binary codec for {@code .mappa}. */
public final class MappaCodec {
	private static final byte[] MAGIC = { 'M', 'A', 'P', 'P', 'A' };
	private static final int VERSION = 1;
	private static final int FLAG_COMPRESSED = 1;

	private MappaCodec() { }

	public static byte[] write(MappaMap map) {
		try {
			ByteArrayOutputStream body = new ByteArrayOutputStream();
			Writer out = new Writer(body);
			Strings strings = Strings.of(map);
			out.var(strings.values.size());
			for (String value : strings.values) {
				out.string(value);
			}
			out.var(strings.id(map.title()));
			out.var(map.entities().size());
			for (MappaMap.Entity entity : map.entities()) {
				out.var(strings.id(entity.name()));
				out.var(entity.kind().ordinal());
				out.var(entity.fields().size());
				for (MappaMap.Field field : entity.fields()) {
					out.var(strings.id(field.name()));
					out.var(strings.id(field.type()));
					int flags = (field.key() ? 1 : 0) | (field.reference() ? 2 : 0);
					out.var(flags);
				}
			}
			Map<String, Integer> entityIndex = new LinkedHashMap<>();
			for (int i = 0; i < map.entities().size(); i++) {
				entityIndex.put(map.entities().get(i).name(), i);
			}
			out.var(map.relationships().size());
			for (MappaMap.Relationship r : map.relationships()) {
				out.var(entityIndex.getOrDefault(r.fromEntity(), -1) + 1);
				out.var(strings.id(r.fromField()));
				out.var(entityIndex.getOrDefault(r.toEntity(), -1) + 1);
				out.var(strings.id(r.toField()));
				out.var(r.suggested() ? 1 : 0);
			}

			ByteArrayOutputStream compressed = new ByteArrayOutputStream();
			try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
				body.writeTo(deflater);
			}

			ByteArrayOutputStream file = new ByteArrayOutputStream();
			file.writeBytes(MAGIC);
			file.write(VERSION);
			file.write(FLAG_COMPRESSED);
			compressed.writeTo(file);
			return file.toByteArray();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static MappaMap read(byte[] bytes) throws IOException {
		Reader file = new Reader(new ByteArrayInputStream(bytes == null ? new byte[0] : bytes));
		for (byte expected : MAGIC) {
			if (file.byteValue() != (expected & 0xFF)) {
				throw new IOException("Not a Mappa document.");
			}
		}
		int version = file.byteValue();
		if (version != VERSION) {
			throw new IOException("Unsupported Mappa format version: " + version);
		}
		int flags = file.byteValue();
		byte[] rest = file.rest();
		ByteArrayInputStream source = new ByteArrayInputStream(rest);
		Reader in = new Reader((flags & FLAG_COMPRESSED) != 0 ? new InflaterInputStream(source) : source);
		int stringCount = in.var();
		List<String> strings = new ArrayList<>(stringCount);
		for (int i = 0; i < stringCount; i++) {
			strings.add(in.string());
		}
		String title = string(strings, in.var());
		int entityCount = in.var();
		List<MappaMap.Entity> entities = new ArrayList<>(entityCount);
		for (int i = 0; i < entityCount; i++) {
			String name = string(strings, in.var());
			MappaMap.EntityKind kind = kind(in.var());
			int fieldCount = in.var();
			List<MappaMap.Field> fields = new ArrayList<>(fieldCount);
			for (int f = 0; f < fieldCount; f++) {
				String fieldName = string(strings, in.var());
				String type = string(strings, in.var());
				int fieldFlags = in.var();
				fields.add(new MappaMap.Field(fieldName, type, (fieldFlags & 1) != 0, (fieldFlags & 2) != 0));
			}
			entities.add(new MappaMap.Entity(name, kind, fields));
		}
		int relationshipCount = in.var();
		List<MappaMap.Relationship> relationships = new ArrayList<>(relationshipCount);
		for (int i = 0; i < relationshipCount; i++) {
			int fromIndex = in.var() - 1;
			String fromField = string(strings, in.var());
			int toIndex = in.var() - 1;
			String toField = string(strings, in.var());
			boolean suggested = in.var() != 0;
			String fromEntity = fromIndex >= 0 && fromIndex < entities.size() ? entities.get(fromIndex).name() : "";
			String toEntity = toIndex >= 0 && toIndex < entities.size() ? entities.get(toIndex).name() : "";
			relationships.add(new MappaMap.Relationship(fromEntity, fromField, toEntity, toField, suggested));
		}
		return new MappaMap(title, entities, relationships);
	}

	private static String string(List<String> strings, int id) throws IOException {
		if (id < 0 || id >= strings.size()) {
			throw new IOException("String table index out of range: " + id);
		}
		return strings.get(id);
	}

	private static MappaMap.EntityKind kind(int ordinal) {
		MappaMap.EntityKind[] values = MappaMap.EntityKind.values();
		return ordinal >= 0 && ordinal < values.length ? values[ordinal] : MappaMap.EntityKind.ENTITY;
	}

	private static final class Strings {
		private final List<String> values = new ArrayList<>();
		private final Map<String, Integer> ids = new LinkedHashMap<>();

		static Strings of(MappaMap map) {
			Strings strings = new Strings();
			strings.id(map.title());
			for (MappaMap.Entity entity : map.entities()) {
				strings.id(entity.name());
				for (MappaMap.Field field : entity.fields()) {
					strings.id(field.name());
					strings.id(field.type());
				}
			}
			for (MappaMap.Relationship r : map.relationships()) {
				strings.id(r.fromEntity());
				strings.id(r.fromField());
				strings.id(r.toEntity());
				strings.id(r.toField());
			}
			return strings;
		}

		int id(String value) {
			String text = value == null ? "" : value;
			Integer existing = ids.get(text);
			if (existing != null) {
				return existing;
			}
			int next = values.size();
			values.add(text);
			ids.put(text, next);
			return next;
		}
	}

	private static final class Writer {
		private final ByteArrayOutputStream out;

		private Writer(ByteArrayOutputStream out) {
			this.out = out;
		}

		void var(int value) {
			int v = value;
			while ((v & ~0x7F) != 0) {
				out.write((v & 0x7F) | 0x80);
				v >>>= 7;
			}
			out.write(v);
		}

		void string(String value) {
			byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
			var(bytes.length);
			out.writeBytes(bytes);
		}
	}

	private static final class Reader {
		private final ByteArrayInputStream memory;
		private final java.io.InputStream in;

		private Reader(ByteArrayInputStream in) {
			this.memory = in;
			this.in = in;
		}

		private Reader(java.io.InputStream in) {
			this.memory = null;
			this.in = in;
		}

		int byteValue() throws IOException {
			int b = in.read();
			if (b < 0) {
				throw new EOFException("Unexpected end of Mappa document.");
			}
			return b;
		}

		int var() throws IOException {
			int value = 0;
			int shift = 0;
			for (int i = 0; i < 5; i++) {
				int b = byteValue();
				value |= (b & 0x7F) << shift;
				if ((b & 0x80) == 0) {
					return value;
				}
				shift += 7;
			}
			throw new IOException("Varint is too long.");
		}

		String string() throws IOException {
			int length = var();
			byte[] bytes = in.readNBytes(length);
			if (bytes.length != length) {
				throw new EOFException("Unexpected end of Mappa string.");
			}
			return new String(bytes, StandardCharsets.UTF_8);
		}

		byte[] rest() {
			return memory == null ? new byte[0] : memory.readAllBytes();
		}
	}
}
