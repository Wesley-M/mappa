package io.github.wesleym.mappa;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders the README's snippet-adjacent examples, code-for-code — each test body is the snippet the README
 * shows, ending in {@code toSvg()}, so the published code and the published diagram can never drift apart.
 * Writes {@code build/readme-*.svg} (vector, glyphs outlined — no font dependency).
 */
class MappaReadmeExamplesTest {

	@Test
	void quickstart() throws Exception {
		var map = Mappa.schema("Store")
				.table("customers", t -> t
						.primaryKey("id", "uuid")
						.column("email", "text")
						.column("full_name", "text"))
				.table("products", t -> t
						.primaryKey("id", "uuid")
						.column("name", "text")
						.column("price", "decimal"))
				.table("orders", t -> t
						.primaryKey("id", "uuid")
						.reference("customer_id", "uuid", "customers", "id")
						.column("total", "decimal"))
				.table("order_items", t -> t
						.primaryKey("id", "uuid")
						.reference("order_id", "uuid", "orders", "id")
						.reference("product_id", "uuid", "products", "id")
						.column("quantity", "int"))
				.build();

		write("readme-quickstart", Mappa.view(map).toSvg());
	}

	@Test
	void curatedControls() throws Exception {
		var map = Fixtures.commerce();

		String svg = Mappa.view(map)
				.layout(MappaLayout.LAYERED)
				.edges(MappaEdges.CURVED)
				.detail(MappaDetail.KEYS)
				.background(MappaBackground.DOTS)
				.theme(MappaTheme.dark())
				.toSvg();

		write("readme-controls", svg);
	}

	@Test
	void directionalEdges() throws Exception {
		write("readme-edges", Mappa.view(Fixtures.commerce())
				.layout(MappaLayout.LAYERED)
				.edges(MappaEdges.DIRECTIONAL)
				.detail(MappaDetail.KEYS)
				.relationshipLabels(true)
				.toSvg());
	}

	static void write(String name, String svg) throws Exception {
		Path dir = Path.of("build");
		Files.createDirectories(dir);
		Path out = dir.resolve(name + ".svg");
		Files.writeString(out, svg, StandardCharsets.UTF_8);
		assertTrue(Files.size(out) > 0 && svg.contains("</svg>"), "wrote " + out);
	}
}
