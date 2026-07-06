package io.github.wesleym.mappa;

import io.github.wesleym.mappa.internal.MappaCodec;
import io.github.wesleym.mappa.internal.common.LightweightMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Entry point for building, opening, saving, and viewing Mappa relationship maps. */
public final class Mappa {

	private Mappa() { }

	/** Starts an opinionated schema-style map. */
	public static Builder schema(String title) {
		return new Builder(title);
	}

	/** Starts a relationship map with neutral entity/field terminology. */
	public static Builder map(String title) {
		return new Builder(title);
	}

	/** Creates a Swing view builder for {@code map}. */
	public static MappaView view(MappaMap map) {
		return new MappaView(map);
	}

	/**
	 * Turns lightweight mode on or off for every live view at once. When on, the interactive canvas drops
	 * everything decorative — entry and edge-flow animation, shadows, glows, community regions — and lays out
	 * on a plain grid, to stay fast on a low-resource machine or a huge schema. It's a global runtime switch
	 * (the drawing code reads it deep in paint and animation loops), so a host with its own low-power mode can
	 * mirror it here in one call and every open diagram reconfigures instantly.
	 */
	public static void setLightweight(boolean lightweight) {
		LightweightMode.set(lightweight);
	}

	/** Whether {@link #setLightweight lightweight mode} is currently on. */
	public static boolean isLightweight() {
		return LightweightMode.isOn();
	}

	/** Reads a native {@code .mappa} document from disk. */
	public static MappaMap read(Path path) throws IOException {
		return read(Files.readAllBytes(path));
	}

	/** Reads a native {@code .mappa} document from bytes. */
	public static MappaMap read(byte[] bytes) throws IOException {
		return MappaCodec.read(bytes);
	}

	/** Writes {@code map} as a native {@code .mappa} document. */
	public static void write(MappaMap map, Path path) throws IOException {
		Files.write(path, map.toBytes());
	}

	/** Fluent builder for the small public model. */
	public static final class Builder {
		private final String title;
		private final Map<String, EntityDraft> entities = new LinkedHashMap<>();
		private final List<MappaMap.Relationship> relationships = new ArrayList<>();

		private Builder(String title) {
			this.title = text(title);
		}

		/** Adds or replaces a table-shaped entity. */
		public Builder table(String name, Consumer<EntityBuilder> configure) {
			return entity(name, MappaMap.EntityKind.TABLE, configure);
		}

		/** Adds or replaces a view-shaped entity. */
		public Builder view(String name, Consumer<EntityBuilder> configure) {
			return entity(name, MappaMap.EntityKind.VIEW, configure);
		}

		/** Adds or replaces a neutral entity. */
		public Builder entity(String name, Consumer<EntityBuilder> configure) {
			return entity(name, MappaMap.EntityKind.ENTITY, configure);
		}

		private Builder entity(String name, MappaMap.EntityKind kind, Consumer<EntityBuilder> configure) {
			String key = text(name);
			EntityDraft draft = new EntityDraft(key, kind);
			EntityBuilder builder = new EntityBuilder(this, draft);
			if (configure != null) {
				configure.accept(builder);
			}
			entities.put(key, draft);
			return this;
		}

		/** Adds a relationship between existing or soon-to-be-created entities. */
		public Builder reference(String fromEntity, String fromField, String toEntity, String toField) {
			return reference(fromEntity, fromField, toEntity, toField, false);
		}

		/** Adds a relationship, optionally marking it as suggested rather than declared. */
		public Builder reference(String fromEntity, String fromField, String toEntity, String toField,
				boolean suggested) {
			relationships.add(new MappaMap.Relationship(
					text(fromEntity), text(fromField), text(toEntity), text(toField), suggested));
			EntityDraft from = entities.get(text(fromEntity));
			if (from != null) {
				from.markReference(text(fromField));
			}
			return this;
		}

		/** Builds the immutable map. */
		public MappaMap build() {
			List<MappaMap.Entity> out = new ArrayList<>(entities.size());
			for (EntityDraft draft : entities.values()) {
				out.add(new MappaMap.Entity(draft.name, draft.kind, List.copyOf(draft.fields.values())));
			}
			return new MappaMap(title, out, List.copyOf(relationships));
		}
	}

	/** Fluent builder for one entity. */
	public static final class EntityBuilder {
		private final Builder owner;
		private final EntityDraft draft;

		private EntityBuilder(Builder owner, EntityDraft draft) {
			this.owner = owner;
			this.draft = draft;
		}

		/** Adds a primary key field. */
		public EntityBuilder primaryKey(String name, String type) {
			draft.put(new MappaMap.Field(text(name), text(type), true, false));
			return this;
		}

		/** Alias for {@link #primaryKey}. */
		public EntityBuilder key(String name, String type) {
			return primaryKey(name, type);
		}

		/** Adds a regular field. */
		public EntityBuilder column(String name, String type) {
			return field(name, type);
		}

		/** Adds a regular field. */
		public EntityBuilder field(String name, String type) {
			draft.put(new MappaMap.Field(text(name), text(type), false, false));
			return this;
		}

		/** Adds a field and a declared relationship from it. */
		public EntityBuilder reference(String name, String type, String targetEntity, String targetField) {
			draft.put(new MappaMap.Field(text(name), text(type), false, true));
			owner.relationships.add(new MappaMap.Relationship(
					draft.name, text(name), text(targetEntity), text(targetField), false));
			return this;
		}

		/** Alias for {@link #reference}. */
		public EntityBuilder relates(String name, String type, String targetEntity, String targetField) {
			return reference(name, type, targetEntity, targetField);
		}
	}

	private static final class EntityDraft {
		private final String name;
		private final MappaMap.EntityKind kind;
		private final Map<String, MappaMap.Field> fields = new LinkedHashMap<>();

		private EntityDraft(String name, MappaMap.EntityKind kind) {
			this.name = name;
			this.kind = Objects.requireNonNullElse(kind, MappaMap.EntityKind.ENTITY);
		}

		private void put(MappaMap.Field field) {
			fields.put(field.name(), field);
		}

		private void markReference(String fieldName) {
			MappaMap.Field field = fields.get(fieldName);
			if (field != null) {
				fields.put(fieldName, field.asReference());
			}
		}
	}

	static String text(String value) {
		return value == null ? "" : value;
	}
}
