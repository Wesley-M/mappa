package io.github.wesleym.mappa.internal.model;

import io.github.wesleym.mappa.MappaMap;
import io.github.wesleym.mappa.internal.layout.EdgeRouter;

import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneBuilderTest {

	private static final Font FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
	private static final SceneBuilder.TextWidth WIDTH = (text, font) -> text.length() * 7.0;

	private static Scene build(MappaMap map) {
		return SceneBuilder.build(map, FONT, FONT, WIDTH, false);
	}

	@Test
	void suggestedRelationshipsBecomeInferredEdges() {
		// orders has a declared FK (customer_id) and a suggested one (account_id → accounts).
		MappaMap both = map(
				List.of(
						node("orders", fk("customer_id"), col("account_id")),
						node("customers", pk("id")),
						node("accounts", pk("id"))),
				List.of(rel("orders", "customer_id", "customers", "id"),
						inferred("orders", "account_id", "accounts", "id")));

		List<Link> edges = build(both).edges();
		assertEquals(2, edges.size(), "declared + inferred both draw");
		assertEquals(1, edges.stream().filter(Link::inferred).count());

		// The map keeping only its suggested relationship — what the canvas's "only inferred" view builds.
		MappaMap onlyInferred = map(both.entities(),
				both.relationships().stream().filter(MappaMap.Relationship::suggested).toList());
		List<Link> inferredEdges = build(onlyInferred).edges();
		assertEquals(1, inferredEdges.size(), "only the inferred edge remains");
		assertTrue(inferredEdges.get(0).inferred());
		assertEquals("account_id", inferredEdges.get(0).fromColumn());
	}

	@Test
	void drawsOneEdgePerForeignKeyToTheSameTable() {
		MappaMap map = map(
				List.of(node("orders", fk("created_by"), fk("updated_by")), node("users", pk("id"))),
				List.of(rel("orders", "created_by", "users", "id"), rel("orders", "updated_by", "users", "id")));

		List<Link> edges = build(map).edges();
		assertEquals(2, edges.size(), "both FKs into users should draw");
		assertEquals(List.of("created_by", "updated_by"), edges.stream().map(Link::fromColumn).sorted().toList());
	}

	@Test
	void dropsRelationshipsToUnknownEntities() {
		// A reference whose 'from' or 'to' entity isn't in the map has no box to attach to and is skipped.
		MappaMap map = map(
				List.of(node("a", fk("b_id")), node("b", pk("id"))),
				List.of(rel("a", "b_id", "b", "id"),
						rel("a", "x_id", "ghost", "id"),      // unknown 'to'
						rel("ghost", "y_id", "a", "id")));    // unknown 'from'
		assertEquals(1, build(map).edges().size(), "only the resolvable reference draws");
	}

	@Test
	void restoresSavedPositionsExactlyAndSkipsLayout() {
		MappaMap map = map(
				List.of(node("a", pk("id")), node("b", pk("id")), node("c", pk("id"))),
				List.of(rel("a", "id", "b", "id"), rel("b", "id", "c", "id")));
		Scene auto = build(map);
		java.util.Map<String, MappaMap.Position> saved = SceneBuilder.positionsOf(auto);

		Scene restored = build(map.withPositions(saved));

		for (EntityBox box : restored.tables()) {
			MappaMap.Position p = saved.get(box.name());
			assertEquals(p.x(), box.bounds().getCenterX(), 0.5, box.name() + " x restored");
			assertEquals(p.y(), box.bounds().getCenterY(), 0.5, box.name() + " y restored");
			assertEquals(-1, box.clusterId(), "the skip-layout path leaves boxes un-clustered");
		}
	}

	@Test
	void pinsAnIndividualBoxWhileAutoPlacingTheRest() {
		MappaMap map = map(
				List.of(node("a", pk("id")), node("b", pk("id"))),
				List.of(rel("a", "id", "b", "id")))
				.withPositions(java.util.Map.of("a", new MappaMap.Position(999, 777)));

		EntityBox a = build(map).tables().stream()
				.filter(t -> t.name().equals("a")).findFirst().orElseThrow();
		assertEquals(999, a.bounds().getCenterX(), 0.5);
		assertEquals(777, a.bounds().getCenterY(), 0.5);
	}

	@Test
	void collapsesAnExactDuplicateRelationship() {
		MappaMap map = map(
				List.of(node("orders", fk("user_id")), node("users", pk("id"))),
				List.of(rel("orders", "user_id", "users", "id"), rel("orders", "user_id", "users", "id")));
		assertEquals(1, build(map).edges().size());
	}

	@Test
	void drawsSelfReferentialForeignKeyAsALoopUnderTheTable() {
		MappaMap map = map(
				List.of(node("employee", pk("id"), fk("manager_id"))),
				List.of(rel("employee", "manager_id", "employee", "id")));

		Scene scene = build(map);
		List<Link> edges = scene.edges();
		assertEquals(1, edges.size(), "the self-referential FK must be kept, not dropped");
		assertEquals(edges.get(0).from(), edges.get(0).to(), "it is a self-loop (from == to)");

		EdgeRouter.EdgeGeometry geom = EdgeRouter.route(scene).get(0);
		assertEquals(EdgeRouter.Side.RIGHT, geom.startSide(), "both feet attach to the right edge");
		assertEquals(EdgeRouter.Side.RIGHT, geom.endSide(), "both feet attach to the right edge");
		double right = scene.tables().get(0).bounds().getMaxX();
		double farthest = geom.waypoints().stream().mapToDouble(Point2D::getX).max().orElse(right);
		assertTrue(farthest > right, "the loop bows out to the right of the box");
	}

	@Test
	void keysOnlyRendersOnlyKeyColumnsWhileFullRendersEveryColumn() {
		MappaMap map = map(
				List.of(node("orders", pk("id"), fk("user_id"), col("note"), col("total"))),
				List.of());

		Scene keysOnly = SceneBuilder.build(map, FONT, FONT, WIDTH, false, true, true);
		Scene full = SceneBuilder.build(map, FONT, FONT, WIDTH, false, true, false);

		assertEquals(List.of("id", "user_id"),
				keysOnly.tables().get(0).columns().stream().map(Field::name).toList(),
				"keys-only keeps the PK and FK, drops the plain columns");
		assertEquals(4, full.tables().get(0).columns().size(), "full keeps every column");
	}

	@Test
	void keysOnlyKeepsInferredForeignKeysAndBadgesThem() {
		MappaMap map = map(
				List.of(node("orders", pk("id"), col("company_id"), col("note")), node("companies", pk("id"))),
				List.of(inferred("orders", "company_id", "companies", "id")));

		Scene scene = SceneBuilder.build(map, FONT, FONT, WIDTH, false, true, true);

		List<Field> orders = scene.tables().get(0).columns();
		assertEquals(List.of("id", "company_id"), orders.stream().map(Field::name).toList(),
				"the inferred FK survives keys-only; the plain column is dropped");
		assertTrue(orders.stream().filter(c -> c.name().equals("company_id")).allMatch(Field::foreignKey),
				"the inferred key is badged as a foreign key");
	}

	@Test
	void exportBuildKeepsFullBoxHeightSoEveryColumnShows() {
		List<MappaMap.Field> cols = new ArrayList<>();
		for (int i = 0; i < 30; i++) {
			cols.add(new MappaMap.Field("c" + i, "int4", i == 0, false));
		}
		MappaMap map = map(List.of(new MappaMap.Entity("wide", MappaMap.EntityKind.TABLE, cols)), List.of());

		Scene capped = SceneBuilder.build(map, FONT, FONT, WIDTH, false, true);
		Scene full = SceneBuilder.build(map, FONT, FONT, WIDTH, false, false);

		assertTrue(capped.tables().get(0).bounds().getHeight() <= BoxMetrics.MAX_BOX_HEIGHT,
				"on-screen height caps so the box scrolls");
		assertTrue(full.tables().get(0).bounds().getHeight() > BoxMetrics.MAX_BOX_HEIGHT,
				"export height grows to fit all 30 columns");
	}

	// ---- map fixtures --------------------------------------------------------------------------------

	private static MappaMap.Field pk(String name) {
		return new MappaMap.Field(name, "int4", true, false);
	}

	private static MappaMap.Field fk(String name) {
		return new MappaMap.Field(name, "int4", false, true);
	}

	private static MappaMap.Field col(String name) {
		return new MappaMap.Field(name, "text", false, false);
	}

	private static MappaMap.Entity node(String name, MappaMap.Field... fields) {
		return new MappaMap.Entity(name, MappaMap.EntityKind.TABLE, List.of(fields));
	}

	private static MappaMap.Relationship rel(String ft, String fc, String tt, String tc) {
		return new MappaMap.Relationship(ft, fc, tt, tc, false);
	}

	private static MappaMap.Relationship inferred(String ft, String fc, String tt, String tc) {
		return new MappaMap.Relationship(ft, fc, tt, tc, true);
	}

	private static MappaMap map(List<MappaMap.Entity> nodes, List<MappaMap.Relationship> rels) {
		return new MappaMap("T", nodes, rels);
	}
}
