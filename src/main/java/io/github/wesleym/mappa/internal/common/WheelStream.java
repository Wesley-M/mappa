package io.github.wesleym.mappa.internal.common;

/**
 * Classifies a wheel-event stream by the device that produced it: a mouse notch is a whole-unit
 * rotation, a trackpad gesture opens with a fractional one. The stream is classified once, by its
 * first event — mid-stream deltas that happen to land on an exact integer keep the stream's mode,
 * so a trackpad gesture never flips into notch handling halfway through. A quiet gap ends the
 * stream; the next event opens (and reclassifies) a new one.
 */
public final class WheelStream {

	/** Events further apart than this belong to separate streams (an active device ticks every few ms). */
	static final long GAP_MILLIS = 150;

	private long lastEventMillis;
	private boolean notch;

	/** Feed every wheel event, in order; true when this event opens a new stream. */
	public boolean advance(long whenMillis, double preciseRotation) {
		boolean opens = whenMillis - lastEventMillis > GAP_MILLIS;
		if (opens) {
			notch = preciseRotation == Math.rint(preciseRotation);
		}
		lastEventMillis = whenMillis;
		return opens;
	}

	/** Whether the current stream is discrete mouse-wheel notches (else a trackpad gesture). */
	public boolean notch() {
		return notch;
	}
}
