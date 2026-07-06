package io.github.wesleym.mappa;

import java.awt.Color;
import java.util.Objects;

/** A complete colour palette for Mappa. Immutable; fluent setters return a new theme. */
public final class MappaTheme {
	private final boolean dark;
	private final Color background;
	private final Color surface;
	private final Color text;
	private final Color muted;
	private final Color line;
	private final Color accent;
	private final Color entityHeader;
	private final Color viewHeader;
	private final Color reference;
	private final Color suggestedReference;
	private final Color inbound;
	private final Color outbound;
	private final Color clusterRegion;

	public MappaTheme(boolean dark, Color background, Color surface, Color text, Color muted, Color line,
			Color accent, Color entityHeader, Color viewHeader, Color reference, Color suggestedReference,
			Color inbound, Color outbound, Color clusterRegion) {
		// A warm, print-like palette: paper and dried-ink. Light is warm paper with terracotta headers, deep
		// teal marks, and ochre for inferred edges; dark is the near-black of an inkwell lit by ember, teal
		// verdigris, and brass. Header fills are dark enough for the white title text (AA-large contrast).
		this.dark = dark;
		this.background = color(background, dark ? 0x201B16 : 0xF2ECE0);
		this.surface = color(surface, dark ? 0x2C251E : 0xFBF7F0);
		this.text = color(text, dark ? 0xF0E9DC : 0x2B2620);
		this.muted = color(muted, dark ? 0x9A9083 : 0x8C8478);
		this.line = color(line, dark ? 0x383028 : 0xE6DFD2);
		this.accent = color(accent, dark ? 0xC7683A : 0xB1512D);
		this.entityHeader = color(entityHeader, dark ? 0xC7683A : 0xB1512D);
		this.viewHeader = color(viewHeader, dark ? 0x399E78 : 0x007C5D);
		this.reference = color(reference, dark ? 0x399E78 : 0x007C5D);
		this.suggestedReference = color(suggestedReference, dark ? 0xB58B22 : 0xB68B16);
		this.inbound = color(inbound, dark ? 0x399E78 : 0x007C5D);
		this.outbound = color(outbound, dark ? 0xC7683A : 0xB1512D);
		this.clusterRegion = color(clusterRegion, dark ? 0x2A2019 : 0xECE4D6);
	}

	public static MappaTheme light() {
		return new MappaTheme(false, null, null, null, null, null, null, null, null, null, null, null, null, null);
	}

	public static MappaTheme dark() {
		return new MappaTheme(true, null, null, null, null, null, null, null, null, null, null, null, null, null);
	}

	public static MappaTheme of(boolean dark) {
		return dark ? dark() : light();
	}

	public boolean isDark() {
		return dark;
	}

	public Color background() {
		return background;
	}

	public Color surface() {
		return surface;
	}

	public Color text() {
		return text;
	}

	public Color muted() {
		return muted;
	}

	public Color line() {
		return line;
	}

	public Color accent() {
		return accent;
	}

	public Color entityHeader() {
		return entityHeader;
	}

	public Color viewHeader() {
		return viewHeader;
	}

	public Color reference() {
		return reference;
	}

	public Color suggestedReference() {
		return suggestedReference;
	}

	public Color inbound() {
		return inbound;
	}

	public Color outbound() {
		return outbound;
	}

	public Color clusterRegion() {
		return clusterRegion;
	}

	public MappaTheme background(Color value) {
		return copy(value, surface, text, muted, line, accent, entityHeader, viewHeader, reference,
				suggestedReference, inbound, outbound, clusterRegion);
	}

	public MappaTheme surface(Color value) {
		return copy(background, value, text, muted, line, accent, entityHeader, viewHeader, reference,
				suggestedReference, inbound, outbound, clusterRegion);
	}

	public MappaTheme text(Color value) {
		return copy(background, surface, value, muted, line, accent, entityHeader, viewHeader, reference,
				suggestedReference, inbound, outbound, clusterRegion);
	}

	public MappaTheme muted(Color value) {
		return copy(background, surface, text, value, line, accent, entityHeader, viewHeader, reference,
				suggestedReference, inbound, outbound, clusterRegion);
	}

	public MappaTheme line(Color value) {
		return copy(background, surface, text, muted, value, accent, entityHeader, viewHeader, reference,
				suggestedReference, inbound, outbound, clusterRegion);
	}

	public MappaTheme accent(Color value) {
		return copy(background, surface, text, muted, line, value, entityHeader, viewHeader, reference,
				suggestedReference, inbound, outbound, clusterRegion);
	}

	public MappaTheme entityHeader(Color value) {
		return copy(background, surface, text, muted, line, accent, value, viewHeader, reference,
				suggestedReference, inbound, outbound, clusterRegion);
	}

	public MappaTheme viewHeader(Color value) {
		return copy(background, surface, text, muted, line, accent, entityHeader, value, reference,
				suggestedReference, inbound, outbound, clusterRegion);
	}

	public MappaTheme reference(Color value) {
		return copy(background, surface, text, muted, line, accent, entityHeader, viewHeader, value,
				suggestedReference, inbound, outbound, clusterRegion);
	}

	public MappaTheme suggestedReference(Color value) {
		return copy(background, surface, text, muted, line, accent, entityHeader, viewHeader, reference,
				value, inbound, outbound, clusterRegion);
	}

	public MappaTheme inbound(Color value) {
		return copy(background, surface, text, muted, line, accent, entityHeader, viewHeader, reference,
				suggestedReference, value, outbound, clusterRegion);
	}

	public MappaTheme outbound(Color value) {
		return copy(background, surface, text, muted, line, accent, entityHeader, viewHeader, reference,
				suggestedReference, inbound, value, clusterRegion);
	}

	public MappaTheme clusterRegion(Color value) {
		return copy(background, surface, text, muted, line, accent, entityHeader, viewHeader, reference,
				suggestedReference, inbound, outbound, value);
	}

	private MappaTheme copy(Color background, Color surface, Color text, Color muted, Color line, Color accent,
			Color entityHeader, Color viewHeader, Color reference, Color suggestedReference, Color inbound,
			Color outbound, Color clusterRegion) {
		return new MappaTheme(dark, background, surface, text, muted, line, accent, entityHeader, viewHeader,
				reference, suggestedReference, inbound, outbound, clusterRegion);
	}

	private static Color color(Color value, int fallback) {
		return Objects.requireNonNullElseGet(value, () -> new Color(fallback));
	}
}
