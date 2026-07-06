package io.github.wesleym.mappa.internal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlExportTest {

	private static final String SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect/></svg>";

	@Test
	void inlinesSvgTitleBackgroundAndData() {
		String html = HtmlExport.wrap(SVG, "Sales & <Ops>", "#123456", "{\"tables\":[]}");
		assertTrue(html.contains("<rect/>"), "the SVG body is inlined");
		assertTrue(html.contains("#123456"), "the given background is used");
		assertTrue(html.contains("Sales &amp; &lt;Ops&gt;"), "the title is HTML-escaped");
		assertFalse(html.contains("{{"), "every placeholder was substituted");
	}

	@Test
	void fallsBackForBlankTitleAndBackground() {
		String html = HtmlExport.wrap(SVG, "  ", null, "{}");
		assertTrue(html.contains("Schema diagram"), "blank title falls back");
		assertTrue(html.contains("#0f1115"), "null background falls back");
	}

	@Test
	void handlesBlankNullAndUntaggedPieces() {
		assertTrue(HtmlExport.wrap(SVG, "T", "#000000", null).contains("\"tables\":[]"), "null data → default json");
		assertTrue(HtmlExport.wrap(SVG, "T", "#000000", "   ").contains("\"tables\":[]"), "blank data → default json");
		assertTrue(HtmlExport.wrap(null, "T", "#000000", "{}").contains("width=\"0\""), "null svg → placeholder");
		assertTrue(HtmlExport.wrap("junk<svg/>tail", "T", "#000000", "{}").contains("<svg/>tail"), "trims to <svg");
		assertTrue(HtmlExport.wrap("no tag here", "T", "#000000", "{}").contains("no tag here"), "kept as-is");
		assertTrue(HtmlExport.wrap(SVG, "a\"b", "#000000", "{}").contains("&quot;"), "quotes escaped");
	}

	@Test
	void nullTitleBlankBackgroundAndBlankSvgFallBack() {
		assertTrue(HtmlExport.wrap(SVG, null, "  ", "  ").contains("Schema diagram"), "null title → default");
		assertTrue(HtmlExport.wrap(SVG, "T", "  ", "{}").contains("#0f1115"), "blank background → default");
		assertTrue(HtmlExport.wrap("  ", "T", "#000000", "{}").contains("width=\"0\""), "blank svg → placeholder");
	}

	@Test
	void neutralisesAScriptCloseInsideTheData() {
		// A table name containing "</script>" must not be able to close the data <script> block.
		String html = HtmlExport.wrap(SVG, "T", "#000000", "{\"n\":\"</script>\"}");
		assertFalse(html.contains("\"</script>\""), "the closing sequence is escaped");
		assertTrue(html.contains("<\\/script>"));
	}
}
