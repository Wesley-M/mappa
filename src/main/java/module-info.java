/**
 * Mappa's public surface: the facade, map model, curated view options, and theme value.
 * Rendering, layout, hit-testing, and the native file codec live in internal packages.
 */
module io.github.wesleym.mappa {
	requires transitive java.desktop;

	exports io.github.wesleym.mappa;
}
