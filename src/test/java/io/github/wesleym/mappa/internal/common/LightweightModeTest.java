package io.github.wesleym.mappa.internal.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightweightModeTest {

	@AfterEach
	void reset() {
		LightweightMode.set(false);
	}

	@Test
	void setTogglesAndNotifiesOnlyOnChange() {
		AtomicInteger fired = new AtomicInteger();
		Runnable listener = fired::incrementAndGet;
		LightweightMode.addListener(listener);
		try {
			assertFalse(LightweightMode.isOn());
			LightweightMode.set(true);
			assertTrue(LightweightMode.isOn());
			LightweightMode.set(true);   // no change → no fire
			assertEquals(1, fired.get());
			LightweightMode.toggle();
			assertFalse(LightweightMode.isOn());
			assertEquals(2, fired.get());
		}
		finally {
			LightweightMode.removeListener(listener);
		}
		LightweightMode.set(true);   // listener removed → count unchanged
		assertEquals(2, fired.get());
	}
}
