package io.github.wesleym.mappa;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The vector + interactive exports: a real SVG document and a self-contained HTML page, both dependency-free. */
class MappaExportTest {

	@Test
	void producesAWellFormedVectorSvg() {
		String svg = Mappa.view(Fixtures.commerce()).relationshipLabels(true).toSvg();

		assertTrue(svg.startsWith("<svg"), "is an SVG document");
		assertTrue(svg.contains("</svg>"), "closes the SVG");
		assertTrue(svg.contains("<path"), "carries vector paths");
		// Every entity's fields draw as outlined glyph paths, so a name never appears as literal <text>.
		assertFalse(svg.contains("<text"), "text is outlined, not literal");
		// A rough well-formedness check: tags balance.
		assertTrue(countOf(svg, "<svg") == countOf(svg, "</svg>"), "svg tags balance");
	}

	@Test
	void gradientEdgesEmitLinearGradients() {
		String svg = Mappa.view(Fixtures.commerce()).edges(MappaEdges.DIRECTIONAL).toSvg();
		assertTrue(svg.contains("<linearGradient"), "directional edges become SVG gradients");
	}

	@Test
	void interactiveHtmlIsSelfContained() {
		String html = Mappa.view(Fixtures.commerce()).toInteractiveHtml("Commerce");

		assertTrue(html.contains("<svg"), "inlines the SVG");
		assertTrue(html.contains("diagram-data") || html.contains("application/json"), "embeds the scene sidecar");
		assertTrue(html.contains("Commerce"), "carries the title");
		assertFalse(html.contains("http://") && html.contains("<script src"), "no external script sources");
	}

	@Test
	void writesFiles(@TempDir Path dir) throws Exception {
		Path svg = dir.resolve("commerce.svg");
		Path html = dir.resolve("commerce.html");
		Mappa.view(Fixtures.commerce()).writeSvg(svg, false);
		Mappa.view(Fixtures.commerce()).writeInteractiveHtml(html, "Commerce");

		assertTrue(Files.size(svg) > 500, "svg written");
		assertTrue(Files.size(html) > 500, "html written");
	}

	@Test
	void writesPreviewArtifacts() throws Exception {
		java.io.File dir = new java.io.File("build");
		dir.mkdirs();
		Files.writeString(dir.toPath().resolve("mappa-commerce.svg"),
				Mappa.view(Fixtures.commerce()).edges(MappaEdges.DIRECTIONAL).relationshipLabels(true)
						.theme(MappaTheme.light()).toSvg());
		Files.writeString(dir.toPath().resolve("mappa-commerce.html"),
				Mappa.view(Fixtures.commerce()).relationshipLabels(true).toInteractiveHtml("Commerce"));
	}

	private static int countOf(String haystack, String needle) {
		int count = 0;
		for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
			count++;
		}
		return count;
	}
}
