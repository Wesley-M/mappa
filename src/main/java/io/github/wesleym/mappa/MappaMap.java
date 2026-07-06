package io.github.wesleym.mappa;

import io.github.wesleym.mappa.internal.MappaCodec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** An immutable relationship map. Build one with {@link Mappa#schema} or read one from {@code .mappa}. */
public final class MappaMap {
	private final String title;
	private final List<Entity> entities;
	private final List<Relationship> relationships;
	private final Map<String, Position> positions;

	public MappaMap(String title, List<Entity> entities, List<Relationship> relationships) {
		this(title, entities, relationships, Map.of());
	}

	public MappaMap(String title, List<Entity> entities, List<Relationship> relationships,
			Map<String, Position> positions) {
		this.title = Mappa.text(title);
		this.entities = copy(entities);
		this.relationships = copy(relationships);
		this.positions = keepPositionsForEntities(positions, this.entities);
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

	/**
	 * The saved box centres, keyed by entity name — a hand-arranged layout to restore. Empty for a freshly
	 * built map; populated by reading a positioned {@code .mappa} or by the interactive view's arrangement
	 * capture. When every entity has a position, the view restores the layout exactly and skips auto-layout.
	 */
	public Map<String, Position> positions() {
		return positions;
	}

	/** Returns a copy of this map carrying {@code positions} (entries for unknown entities are dropped). */
	public MappaMap withPositions(Map<String, Position> positions) {
		return new MappaMap(title, entities, relationships, positions);
	}

	public boolean isEmpty() {
		return entities.isEmpty();
	}

	/** A saved box centre for an entity, in world coordinates. */
	public record Position(double x, double y) { }

	/** Returns a map containing only {@code names} and relationships whose ends remain visible. */
	public MappaMap select(Collection<String> names) {
		Set<String> selected = new LinkedHashSet<>(names == null ? List.of() : names);
		List<Entity> kept = entities.stream()
				.filter(e -> selected.contains(e.name()))
				.toList();
		List<Relationship> edges = relationships.stream()
				.filter(r -> selected.contains(r.fromEntity()) && selected.contains(r.toEntity()))
				.toList();
		return new MappaMap(title, kept, edges, positions);
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
		return new MappaMap(title, kept, edges, positions);
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

	// Positions keyed by an entity name that no longer exists (after a select/focus, or a stale document) are
	// dropped, so positions() never references a box that isn't in the map.
	private static Map<String, Position> keepPositionsForEntities(Map<String, Position> positions,
			List<Entity> entities) {
		if (positions == null || positions.isEmpty()) {
			return Map.of();
		}
		Set<String> names = new LinkedHashSet<>();
		for (Entity entity : entities) {
			names.add(entity.name());
		}
		Map<String, Position> kept = new LinkedHashMap<>();
		for (Map.Entry<String, Position> e : positions.entrySet()) {
			if (e.getValue() != null && names.contains(e.getKey())) {
				kept.put(e.getKey(), e.getValue());
			}
		}
		return Map.copyOf(kept);
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
