package io.github.wesleym.mappa.internal.layout;

import io.github.wesleym.mappa.internal.model.Link;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JoinPathTest {

	// payment(0) -> rental(1) -> customer(2);  customer(2) -> address(3)
	private static final List<Link> EDGES = List.of(
			new Link(0, 1),   // edge 0: payment -> rental
			new Link(1, 2),   // edge 1: rental -> customer
			new Link(2, 3));  // edge 2: customer -> address

	@Test
	void tracesShortestRouteAcrossHops() {
		// payment -> address must go 0 -> 1 -> 2 -> 3, i.e. edges [0,1,2], regardless of edge direction.
		assertEquals(List.of(0, 1, 2), JoinPath.shortest(EDGES, 0, 3));
	}

	@Test
	void isDirectionAgnostic() {
		// The reverse query yields the same edges in reverse order — edges are undirected for join paths.
		assertEquals(List.of(2, 1, 0), JoinPath.shortest(EDGES, 3, 0));
	}

	@Test
	void prefersFewerHops() {
		// Add a shortcut payment(0) -> customer(2); now payment -> customer is one hop, not two.
		List<Link> withShortcut = List.of(
				new Link(0, 1), new Link(1, 2), new Link(2, 3), new Link(0, 2));
		assertEquals(List.of(3), JoinPath.shortest(withShortcut, 0, 2));
	}

	@Test
	void emptyForSameTableOrDisconnected() {
		assertTrue(JoinPath.shortest(EDGES, 1, 1).isEmpty(), "same table");
		List<Link> split = List.of(new Link(0, 1), new Link(2, 3));
		assertTrue(JoinPath.shortest(split, 0, 3).isEmpty(), "no path between components");
	}
}
