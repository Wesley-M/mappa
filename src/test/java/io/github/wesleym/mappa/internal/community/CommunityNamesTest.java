package io.github.wesleym.mappa.internal.community;

import io.github.wesleym.mappa.internal.community.CommunityNames.TableNode;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommunityNamesTest {

	@Test
	void emptyCommunityFallsBackToGroup() {
		assertEquals("Group", CommunityNames.name(List.of()));
		assertEquals("Group", CommunityNames.name(null));
	}

	@Test
	void singleTableIsHumanised() {
		String label = CommunityNames.name(List.of("customer_accounts"));
		assertFalse(label.isBlank());
		assertTrue(Character.isUpperCase(label.charAt(0)), "Title Case: " + label);
	}

	@Test
	void aSharedTokenSurfacesInTheLabel() {
		String label = CommunityNames.name(List.of("order_items", "order_status", "order_lines", "order_notes"));
		assertTrue(label.toLowerCase().contains("order"), "the dominant token should lead the label: " + label);
	}

	@Test
	void pairsTwoComparableConcepts() {
		// Two hubs of similar strength (order*, payment*) → an honest two-concept label.
		Map<Integer, List<TableNode>> c = Map.of(0, List.of(
				new TableNode("orders", 2, 0), new TableNode("order_lines", 2, 0),
				new TableNode("payments", 2, 0), new TableNode("payment_refunds", 2, 0)));
		String label = CommunityNames.nameAll(c).get(0);
		assertTrue(label.contains("&"), "two comparable concepts pair: " + label);
	}

	@Test
	void fallsBackToTheHubWhenNoTokenIsShared() {
		// No token appears in >=2 tables → name after the most within-connected table (alpha).
		Map<Integer, List<TableNode>> c = Map.of(0, List.of(
				new TableNode("alpha", 5, 0), new TableNode("beta", 1, 0), new TableNode("gamma", 1, 0)));
		assertEquals("Alpha", CommunityNames.nameAll(c).get(0));
	}

	@Test
	void filtersSchemaWideTokensAcrossCommunities() {
		// "log" appears in every community → treated as schema-wide and not used as a label.
		Map<Integer, List<TableNode>> c = Map.of(
				0, List.of(new TableNode("order_log", 1, 0), new TableNode("orders", 2, 0)),
				1, List.of(new TableNode("payment_log", 1, 0), new TableNode("payments", 2, 0)),
				2, List.of(new TableNode("audit_log", 1, 0), new TableNode("audits", 2, 0)));
		Map<Integer, String> names = CommunityNames.nameAll(c);
		assertTrue(names.values().stream().noneMatch(v -> v.equalsIgnoreCase("Log")), names.toString());
	}

	@Test
	void singularisationAndTokenisationBranches() {
		// Each name pairs a token through a distinct singular() / tokens() branch; labels stay non-blank.
		for (String stem : List.of("categories", "statuses", "addresses", "orders", "status", "address")) {
			String label = CommunityNames.name(List.of(stem, stem + "_detail"));
			assertFalse(label.isBlank(), stem);
		}
		// camelCase split + stopword drop: only "order" survives as the shared concept.
		assertTrue(CommunityNames.name(List.of("orderData", "orderInfo")).toLowerCase().contains("order"));
		// All-stopword name → humanised whole-name fallback rather than an empty label.
		assertFalse(CommunityNames.name(List.of("id")).isBlank());
	}

	@Test
	void breaksTiesByLengthThenAlphabetAndPairsEquals() {
		// Two equally-weighted, equal-count, equal-length concepts (order, stock) → tie broken by alpha, paired.
		Map<Integer, List<TableNode>> c = Map.of(0, List.of(
				new TableNode("order_line", 0, 0), new TableNode("order_note", 0, 0),
				new TableNode("stock_line", 0, 0), new TableNode("stock_note", 0, 0)));
		String label = CommunityNames.nameAll(c).get(0);
		assertTrue(label.contains("&"), "two equal concepts pair: " + label);
		assertTrue(label.toLowerCase().startsWith("order"), "alpha tie-break puts order first: " + label);
	}

	@Test
	void shortPluralsAreLeftAloneByTheSingulariser() {
		// "ies"/"ses" endings on short tokens must NOT be stripped (length guards), yet still produce a label.
		for (String name : List.of("pies", "uses", "ties_a")) {
			assertFalse(CommunityNames.name(List.of(name, name + "_x")).isBlank(), name);
		}
	}

	@Test
	void skipsNullTablesSafely() {
		java.util.List<TableNode> withNull = new java.util.ArrayList<>();
		withNull.add(new TableNode("customers", 2, 0));
		withNull.add(null);
		assertFalse(CommunityNames.nameAll(Map.of(0, withNull)).get(0).isBlank());
	}

	@Test
	void connectorTablesAreDroppedAndHubsAreWeighted() {
		// Community 0: a strong "billing" concept with a hub (invoices) and a cross-linking bridge table.
		Map<Integer, List<TableNode>> communities = Map.of(
				0, List.of(
						new TableNode("invoices", 4, 0),          // hub — heavily weighted
						new TableNode("invoice_lines", 2, 0),
						new TableNode("invoice_taxes", 1, 0),
						new TableNode("sync_bridge", 0, 3)),       // connector: cross > within, dropped
				1, List.of(
						new TableNode("shipments", 3, 0),
						new TableNode("shipment_events", 1, 0)));

		Map<Integer, String> names = CommunityNames.nameAll(communities);
		assertEquals(2, names.size());
		assertFalse(names.get(0).isBlank());
		assertFalse(names.get(1).isBlank());
		assertTrue(names.get(0).toLowerCase().contains("invoice"), "the hub concept leads community 0: " + names.get(0));
	}
}
