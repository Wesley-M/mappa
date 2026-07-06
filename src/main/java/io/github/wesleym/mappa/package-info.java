/**
 * Mappa's public surface: opinionated relationship maps for Swing.
 *
 * <p>Start at {@link io.github.wesleym.mappa.Mappa} — build a {@link io.github.wesleym.mappa.MappaMap}
 * with the fluent {@code schema}/{@code map} builder, then {@link io.github.wesleym.mappa.Mappa#view view}
 * it into a live {@code JComponent} or a headless image. The curated choices — placement, edge style,
 * field detail, backdrop — are the enums {@link io.github.wesleym.mappa.MappaLayout},
 * {@link io.github.wesleym.mappa.MappaEdges}, {@link io.github.wesleym.mappa.MappaDetail}, and
 * {@link io.github.wesleym.mappa.MappaBackground}; colour lives in one immutable
 * {@link io.github.wesleym.mappa.MappaTheme}.
 *
 * <p>Layout, rendering, hit-testing, and the native {@code .mappa} codec are implementation detail in the
 * {@code internal} package and are not exported.
 */
package io.github.wesleym.mappa;
