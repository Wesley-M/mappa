package io.github.wesleym.mappa.internal.model;

import io.github.wesleym.mappa.MappaMap;
import io.github.wesleym.mappa.internal.layout.LayoutStyle;

import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Not a CI gate — a timing harness (prints to stdout) to see where layout spends its time and to confirm the
 * skip-layout fast path a persisted arrangement unlocks. Asserts only the relative fact that must always hold:
 * restoring saved positions is faster than a full auto-layout.
 */
class PerfBenchmarkTest {

	private static final Font FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
	private static final BufferedImage PROBE = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
	private static final SceneBuilder.TextWidth WIDTH =
			(t, f) -> PROBE.createGraphics().getFontMetrics(f).stringWidth(t);

	// A modular schema of n tables: dense FKs within groups of 20, sparse bridges between them.
	private static MappaMap schema(int n) {
		List<MappaMap.Entity> entities = new ArrayList<>(n);
		List<MappaMap.Relationship> rels = new ArrayList<>();
		Random random = new Random(11);
		for (int i = 0; i < n; i++) {
			entities.add(new MappaMap.Entity("t" + i, MappaMap.EntityKind.TABLE, List.of(
					new MappaMap.Field("id", "uuid", true, false),
					new MappaMap.Field("a_id", "uuid", false, true),
					new MappaMap.Field("b_id", "uuid", false, true))));
			int group = i / 20;
			int lo = group * 20;
			if (i > lo) {
				rels.add(new MappaMap.Relationship("t" + i, "a_id", "t" + (lo + random.nextInt(i - lo)), "id", false));
			}
			if (group > 0 && i == lo) {
				rels.add(new MappaMap.Relationship("t" + i, "b_id", "t" + (random.nextInt(group) * 20), "id", false));
			}
		}
		return new MappaMap("Big", entities, rels);
	}

	private static long millis(Supplier<?> work) {
		work.get();   // warm
		long start = System.nanoTime();
		work.get();
		return (System.nanoTime() - start) / 1_000_000;
	}

	@Test
	void restoringPositionsBeatsAutoLayout() {
		int n = 800;
		MappaMap map = schema(n);
		long layered = millis(() -> SceneBuilder.build(map, FONT, FONT, WIDTH, false, true, true, LayoutStyle.LAYERED));
		long force = millis(() -> SceneBuilder.build(map, FONT, FONT, WIDTH, false, true, true, LayoutStyle.FORCE));

		Scene laid = SceneBuilder.build(map, FONT, FONT, WIDTH, false, true, true, LayoutStyle.LAYERED);
		MappaMap positioned = map.withPositions(SceneBuilder.positionsOf(laid));
		long restore = millis(() -> SceneBuilder.build(positioned, FONT, FONT, WIDTH, false, true, true,
				LayoutStyle.LAYERED));

		System.out.printf("%n[layout %d tables]  LAYERED=%dms  FORCE=%dms  restore(skip-layout)=%dms%n",
				n, layered, force, restore);
		assertTrue(restore <= layered, "restoring a saved arrangement must beat a full auto-layout");
	}
}
