package io.github.wesleym.mappa;

import io.github.wesleym.mappa.internal.MappaCodec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** An immutable relationship map. Build one with {@link Mappa#schema} or read one from {@code .mappa}. */
public final class MappaMap {
	private final String title;
	private final List<Entity> entities;
	private final List<Relationship> relationships;

	public MappaMap(String title, List<Entity> entities, List<Relationship> relationships) {
		this.title = Mappa.text(title);
		this.entities = copy(entities);
		this.relationships = copy(relationships);
	}

	public String title() {
		return title;
	}

	public List<Entity> entities() {
		return entities;
	}

	public List<Relationship> relationships() {
		return relationships;
	}

	public boolean isEmpty() {
		return entities.isEmpty();
	}

	/** Returns a map containing only {@code names} and relationships whose ends remain visible. */
	public MappaMap select(Collection<String> names) {
		Set<String> selected = new LinkedHashSet<>(names == null ? List.of() : names);
		List<Entity> kept = entities.stream()
				.filter(e -> selected.contains(e.name()))
				.toList();
		List<Relationship> edges = relationships.stream()
				.filter(r -> selected.contains(r.fromEntity()) && selected.contains(r.toEntity()))
				.toList();
		return new MappaMap(title, kept, edges);
	}

	/** Returns {@code entityName} plus every directly related entity. */
	public MappaMap focus(String entityName) {
		Set<String> selected = new LinkedHashSet<>();
		String key = key(entityName);
		entities.stream()
				.map(Entity::name)
				.filter(name -> key(name).equals(key))
				.forEach(selected::add);
		for (Relationship r : relationships) {
			if (key(r.fromEntity()).equals(key)) {
				selected.add(r.toEntity());
			}
			if (key(r.toEntity()).equals(key)) {
				selected.add(r.fromEntity());
			}
		}
		List<Relationship> edges = relationships.stream()
				.filter(r -> selected.contains(r.fromEntity()) && selected.contains(r.toEntity()))
				.filter(r -> key(r.fromEntity()).equals(key) || key(r.toEntity()).equals(key))
				.toList();
		List<Entity> kept = entities.stream()
				.filter(e -> selected.contains(e.name()))
				.toList();
		return new MappaMap(title, kept, edges);
	}

	/** Serializes this map as a compact native {@code .mappa} document. */
	public byte[] toBytes() {
		return MappaCodec.write(this);
	}

	/** Writes this map as a compact native {@code .mappa} document. */
	public void write(Path path) throws IOException {
		Files.write(path, toBytes());
	}

	/** What kind of entity this box represents. */
	public enum EntityKind { ENTITY, TABLE, VIEW }

	/** An entity shown as one box. */
	public record Entity(String name, EntityKind kind, List<Field> fields) {
		public Entity {
			name = Mappa.text(name);
			kind = Objects.requireNonNullElse(kind, EntityKind.ENTITY);
			fields = copy(fields);
		}
	}

	/** A field shown inside an entity box. */
	public record Field(String name, String type, boolean key, boolean reference) {
		public Field {
			name = Mappa.text(name);
			type = Mappa.text(type);
		}

		Field asReference() {
			return new Field(name, type, key, true);
		}
	}

	/** A child/source field pointing at a parent/target field. */
	public record Relationship(String fromEntity, String fromField, String toEntity, String toField,
			boolean suggested) {
		public Relationship {
			fromEntity = Mappa.text(fromEntity);
			fromField = Mappa.text(fromField);
			toEntity = Mappa.text(toEntity);
			toField = Mappa.text(toField);
		}
	}

	private static <T> List<T> copy(List<T> values) {
		if (values == null || values.isEmpty()) {
			return List.of();
		}
		List<T> out = new ArrayList<>();
		for (T value : values) {
			if (value != null) {
				out.add(value);
			}
		}
		return List.copyOf(out);
	}

	private static String key(String value) {
		return Mappa.text(value).toLowerCase(Locale.ROOT);
	}
}
