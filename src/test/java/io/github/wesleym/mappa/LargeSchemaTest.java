package io.github.wesleym.mappa;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A large, modular schema — dense neighbourhoods with sparse bridges between them — big enough to trip
 * community detection, per-cluster layout and packing, and (rendered small) the level-of-detail and
 * viewport-culling paths that only engage past a table-count threshold.
 */
class LargeSchemaTest {

	// 6 groups of 16 tables: dense FKs within a group, one sparse bridge between consecutive groups.
	private static MappaMap modular(int groups, int perGroup) {
		List<MappaMap.Entity> entities = new ArrayList<>();
		List<MappaMap.Relationship> rels = new ArrayList<>();
		Random random = new Random(7);
		for (int g = 0; g < groups; g++) {
			for (int i = 0; i < perGroup; i++) {
				int id = g * perGroup + i;
				entities.add(new MappaMap.Entity("t" + id, MappaMap.EntityKind.TABLE, List.of(
						new MappaMap.Field("id", "int4", true, false),
						new MappaMap.Field("a_id", "int4", false, true),
						new MappaMap.Field("b_id", "int4", false, true),
						new MappaMap.Field("name", "text", false, false))));
				if (i > 0) {
					rels.add(rel("t" + id, "a_id", "t" + (g * perGroup + random.nextInt(i))));
					rels.add(rel("t" + id, "b_id", "t" + (g * perGroup + random.nextInt(i))));
				}
			}
			if (g > 0) {
				rels.add(rel("t" + (g * perGroup), "a_id", "t" + (random.nextInt(g) * perGroup)));
			}
		}
		return new MappaMap("Modular", entities, rels);
	}

	private static MappaMap.Relationship rel(String fromTable, String fromCol, String toTable) {
		return new MappaMap.Relationship(fromTable, fromCol, toTable, "id", false);
	}

	@Test
	void clustersAndRendersAtFullDetail() {
		MappaMap map = modular(6, 16);
		assertTrue(map.entities().size() == 96);
		BufferedImage image = Mappa.view(map).image(2000, 1500);   // large → boxes at full detail
		assertTrue(nonBackground(image) > 5000, "the clustered schema renders");
	}

	@Test
	void simplifiesUnderLevelOfDetailWhenRenderedSmall() {
		// A 96-table map squeezed into a small frame drops to a low zoom, so LOD + culling engage.
		BufferedImage image = Mappa.view(modular(6, 16)).image(700, 500);
		assertTrue(nonBackground(image) > 1000, "the overview still renders visible structure");
	}

	@Test
	void exportsTheLargeSchemaToSvg() {
		String svg = Mappa.view(modular(4, 12)).toSvg();
		assertTrue(svg.startsWith("<svg") && svg.contains("</svg>"));
		assertTrue(svg.split("<path").length > 100, "a big schema yields many vector paths");
	}

	@Test
	void clustersUnderEveryLayoutStyle() {
		// Render the community-clustered schema under each placement mode → per-cluster radial/force/grid paths.
		MappaMap map = modular(6, 16);
		for (MappaLayout layout : MappaLayout.values()) {
			BufferedImage image = Mappa.view(map).layout(layout).image(1800, 1300);
			assertTrue(nonBackground(image) > 3000, layout + " lays out and renders the clustered schema");
		}
	}

	@Test
	void labelsManyEdgesWithoutError() {
		// 8×20 tables ⇒ 300+ edges, past the label-placement forced cap, exercising the crowded-label paths.
		String svg = Mappa.view(modular(8, 20)).relationshipLabels(true).toSvg();
		assertTrue(svg.contains("</svg>"), "a heavily-labelled large schema still exports");
	}

	private static int nonBackground(BufferedImage image) {
		int bg = image.getRGB(2, 2);
		int count = 0;
		for (int y = 0; y < image.getHeight(); y += 4) {
			for (int x = 0; x < image.getWidth(); x += 4) {
				if (image.getRGB(x, y) != bg) {
					count++;
				}
			}
		}
		return count;
	}
}
