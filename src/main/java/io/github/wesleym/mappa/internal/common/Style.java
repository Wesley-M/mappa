package io.github.wesleym.mappa.internal.common;

import javax.swing.UIManager;

import java.awt.Font;

/**
 * A small type/spacing token set for the interactive canvas chrome (search field, popups). Mappa has no
 * host UI-zoom, so {@code px} is identity and fonts are unscaled — the canvas draws at device pixels.
 */
public final class Style {

	private Style() { }

	public static final float CAPTION = 11.5f;
	public static final float BODY = 13f;

	static Font base() {
		Font font = UIManager.getFont("defaultFont");
		return font != null ? font : new Font("SansSerif", Font.PLAIN, 13);
	}

	public static Font unscaledFont(float size, int style) {
		return base().deriveFont(style, size);
	}

	public static Font caption() {
		return unscaledFont(CAPTION, Font.PLAIN);
	}

	public static Font body() {
		return unscaledFont(BODY, Font.PLAIN);
	}

	public static int px(int value) {
		return value;
	}
}
