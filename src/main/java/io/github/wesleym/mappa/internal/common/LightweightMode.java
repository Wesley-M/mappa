package io.github.wesleym.mappa.internal.common;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A "lightweight" switch. When on, the canvas drops everything decorative (animations, shadows, glows,
 * community regions) to stay fast on a low-resource machine. A static holder because the flag is read from
 * deep inside paint code and animation timers, where threading it through every constructor is impractical;
 * it broadcasts to listeners so every surface reconfigures the instant it's toggled.
 *
 * <p>Listeners are held strongly — a component that registers one MUST remove it when disposed.
 */
public final class LightweightMode {

	private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
	private static boolean on;

	private LightweightMode() { }

	/** Whether lightweight mode is currently active. */
	public static boolean isOn() {
		return on;
	}

	/** Sets the mode and notifies listeners (no-op when unchanged). */
	public static void set(boolean value) {
		if (value == on) {
			return;
		}
		on = value;
		for (Runnable listener : LISTENERS) {
			listener.run();
		}
	}

	public static void toggle() {
		set(!on);
	}

	/** Registers a listener fired whenever the mode changes. Remove it when the owning component is disposed. */
	public static void addListener(Runnable listener) {
		LISTENERS.add(listener);
	}

	public static void removeListener(Runnable listener) {
		LISTENERS.remove(listener);
	}
}
