package io.github.wesleym.mappa.internal.common;

import io.github.wesleym.mappa.MappaMap;

import java.awt.Font;
import java.io.InputStream;

/**
 * The bundled diagram typefaces: Inter for entity titles and chrome, JetBrains Mono for field rows. The
 * logical {@code SansSerif}/{@code Monospaced} fonts map to a different face on every OS (Helvetica/Menlo
 * on macOS, Arial/Courier New on Windows), so the same diagram used to render with very different weight
 * and rhythm per platform. Bundling pins the face — and the text metrics, so box sizes and layout are
 * identical everywhere too. Both fonts are SIL OFL 1.1 licensed; the license texts ship in the same
 * resource folder as the font files.
 *
 * <p>The bundled fonts cover Latin, Greek and Cyrillic. Fonts created from a resource get no system glyph
 * fallback in Java2D (missing glyphs draw as boxes), so {@link #covers} checks a map's text up front and
 * the render path falls back to the logical fonts — which composite in system glyphs for every script —
 * for maps the bundled coverage can't display.
 */
public final class Fonts {

	private static final Font SANS = load("fonts/Inter-Regular.otf");
	private static final Font SANS_SEMIBOLD = load("fonts/Inter-SemiBold.otf");
	private static final Font MONO = load("fonts/JetBrainsMono-Regular.ttf");

	private Fonts() { }

	/** Bundled Inter Regular at {@code size}; logical SansSerif if the resource failed to load. */
	public static Font sans(float size) {
		return SANS != null ? SANS.deriveFont(size) : new Font(Font.SANS_SERIF, Font.PLAIN, Math.round(size));
	}

	/**
	 * Bundled Inter SemiBold at {@code size}; logical bold SansSerif if the resource failed to load. The
	 * weight lives in the outlines (the Font's style flags stay PLAIN), so nothing re-derives BOLD on top.
	 */
	public static Font sansSemiBold(float size) {
		return SANS_SEMIBOLD != null ? SANS_SEMIBOLD.deriveFont(size)
				: new Font(Font.SANS_SERIF, Font.BOLD, Math.round(size));
	}

	/** Bundled JetBrains Mono Regular at {@code size}; logical Monospaced if the resource failed to load. */
	public static Font mono(float size) {
		return MONO != null ? MONO.deriveFont(size) : new Font(Font.MONOSPACED, Font.PLAIN, Math.round(size));
	}

	/**
	 * Whether the bundled fonts can display every string {@code map} renders — entity names in the title
	 * font; field names, types and join-column labels in the row font. False when a bundled font failed to
	 * load, or when any string needs glyphs outside the bundled coverage (e.g. CJK names).
	 */
	public static boolean covers(MappaMap map) {
		if (SANS_SEMIBOLD == null || MONO == null) {
			return false;
		}
		if (map == null) {
			return true;
		}
		for (MappaMap.Entity entity : map.entities()) {
			if (!displays(SANS_SEMIBOLD, entity.name())) {
				return false;
			}
			for (MappaMap.Field field : entity.fields()) {
				if (!displays(MONO, field.name()) || !displays(MONO, field.type())) {
					return false;
				}
			}
		}
		for (MappaMap.Relationship r : map.relationships()) {
			// Join-column labels render as "fk → pk" in the row font; both fonts carry → and ….
			if (!displays(MONO, r.fromField()) || !displays(MONO, r.toField())) {
				return false;
			}
		}
		return true;
	}

	private static boolean displays(Font font, String text) {
		return text == null || font.canDisplayUpTo(text) == -1;
	}

	private static Font load(String resource) {
		try (InputStream in = Fonts.class.getResourceAsStream(resource)) {
			return in == null ? null : Font.createFont(Font.TRUETYPE_FONT, in);
		}
		catch (Exception e) {
			return null;   // fall back to the logical fonts rather than fail the whole canvas
		}
	}
}
