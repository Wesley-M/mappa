package io.github.wesleym.mappa;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders every example in the README, code-for-code — each test body is the snippet the README shows, so
 * the published code and the published diagram can never drift apart. Writes {@code build/readme-*.png}.
 */
class MappaReadmeExamplesTest {

	@Test
	void quickstart() throws Exception {
		var map = Mappa.schema("Store")
				.table("orders", t -> t
						.primaryKey("id", "uuid")
						.reference("customer_id", "uuid", "customers", "id")
						.column("total", "decimal"))
				.table("customers", t -> t
						.primaryKey("id", "uuid")
						.column("email", "text"))
				.build();

		write("readme-quickstart", Mappa.view(map).image(760, 460));
	}

	@Test
	void curatedControls() throws Exception {
		var map = Fixtures.commerce();

		BufferedImage image = Mappa.view(map)
				.layout(MappaLayout.LAYERED)
				.edges(MappaEdges.CURVED)
				.detail(MappaDetail.KEYS)
				.background(MappaBackground.DOTS)
				.theme(MappaTheme.dark())
				.image(900, 620);

		write("readme-controls", image);
	}

	@Test
	void focusOnOneEntity() throws Exception {
		MappaMap neighbourhood = Fixtures.commerce().focus("orders");

		write("readme-focus", Mappa.view(neighbourhood)
				.layout(MappaLayout.LAYERED)
				.relationshipLabels(true)
				.image(820, 560));
	}

	@Test
	void directionalEdges() throws Exception {
		write("readme-edges", Mappa.view(Fixtures.commerce())
				.layout(MappaLayout.LAYERED)
				.edges(MappaEdges.DIRECTIONAL)
				.detail(MappaDetail.KEYS)
				.image(900, 600));
	}

	static void write(String name, BufferedImage image) throws Exception {
		File dir = new File("build");
		dir.mkdirs();
		File out = new File(dir, name + ".png");
		ImageIO.write(image, "png", out);
		assertTrue(out.length() > 0, "wrote " + out);
	}
}
