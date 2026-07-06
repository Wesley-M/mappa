package io.github.wesleym.mappa.internal.render;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Wraps an exported schema-diagram SVG in a single, self-contained interactive HTML page. The page inlines the
 * SVG (the diagram is already fully rendered as vectors, glyphs outlined, so nothing is re-rendered) and adds a
 * small pan/zoom/fit viewer over its {@code viewBox}. The result opens in any browser — no app, no
 * server, no extension — so a diagram can be exported once and shared/viewed anywhere.
 */
final class HtmlExport {

	private static final String TEMPLATE = loadTemplate();

	private HtmlExport() { }

	/**
	 * Build the full HTML document embedding {@code svg}, titled {@code title}, on page background {@code background}
	 * (a CSS colour — matched to the diagram so there's no jarring letterbox; falls back to a neutral dark).
	 * {@code data} is the scene sidecar JSON ({@code {tables:[{n,x,y,w,h}], edges:[[from,to]]}}) the in-page viewer
	 * uses to overlay interactive table targets for hover-to-highlight and search.
	 */
	static String wrap(String svg, String title, String background, String data) {
		String json = data == null || data.isBlank() ? "{\"tables\":[],\"edges\":[]}" : data;
		return TEMPLATE
				.replace("{{TITLE}}", escape(title == null || title.isBlank() ? "Schema diagram" : title))
				.replace("{{BG}}", background == null || background.isBlank() ? "#0f1115" : background)
				.replace("{{DATA}}", json.replace("</", "<\\/"))   // keep a table name can't close the <script>
				.replace("{{SVG_CONTENT}}", inlineSvg(svg));
	}

	private static String inlineSvg(String svg) {
		if (svg == null || svg.isBlank()) {
			return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"0\" height=\"0\"></svg>";
		}
		int start = svg.indexOf("<svg");
		return start >= 0 ? svg.substring(start) : svg;
	}

	private static String escape(String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	private static String loadTemplate() {
		try (InputStream in = HtmlExport.class.getResourceAsStream("/io/github/wesleym/mappa/internal/render/viewer.html")) {
			if (in == null) {
				throw new IOException("viewer.html is missing from the jar");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
