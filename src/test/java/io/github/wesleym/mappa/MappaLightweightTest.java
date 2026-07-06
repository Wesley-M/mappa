package io.github.wesleym.mappa;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The global lightweight switch a host mirrors from its own low-power mode. */
class MappaLightweightTest {

	@AfterEach
	void reset() {
		Mappa.setLightweight(false);   // leave the global at its default so test order can't leak state
	}

	@Test
	void togglesTheGlobalFlag() {
		assertFalse(Mappa.isLightweight(), "off by default");
		Mappa.setLightweight(true);
		assertTrue(Mappa.isLightweight());
		Mappa.setLightweight(false);
		assertFalse(Mappa.isLightweight());
	}
}
