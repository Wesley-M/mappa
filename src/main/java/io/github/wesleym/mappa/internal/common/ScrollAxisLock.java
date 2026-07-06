package io.github.wesleym.mappa.internal.common;

/**
 * Dominant-axis filter for trackpad wheel streams. A two-finger trackpad pan emits vertical and
 * horizontal wheel events together (Swing delivers the horizontal ones shift-modified), so a
 * mostly-vertical swipe over a wide grid also wanders sideways with every slight finger angle —
 * while a mouse wheel emits no horizontal component at all. The filter splits the stream into
 * gestures (a quiet gap starts a new one), accumulates per-axis travel, and admits an event only
 * while its axis carries the majority of the gesture's travel. A deliberate horizontal swipe
 * scrolls from its first tick; a vertical scroll no longer drifts sideways.
 */
public final class ScrollAxisLock {

	/** A pause longer than this starts a new gesture (an active trackpad stream ticks every few ms). */
	static final long GESTURE_GAP_MILLIS = 200;

	private long lastEventMillis;
	private double horizontalTravel;
	private double verticalTravel;

	/** Whether a wheel event on this axis may scroll. Feed every event, in delivery order. */
	public boolean allow(long whenMillis, boolean horizontal, double magnitude) {
		if (whenMillis - lastEventMillis > GESTURE_GAP_MILLIS) {
			horizontalTravel = 0;
			verticalTravel = 0;
		}
		lastEventMillis = whenMillis;
		if (horizontal) {
			horizontalTravel += Math.abs(magnitude);
			return horizontalTravel >= verticalTravel;
		}
		verticalTravel += Math.abs(magnitude);
		return verticalTravel >= horizontalTravel;
	}
}
