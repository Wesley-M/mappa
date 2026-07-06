package io.github.wesleym.mappa.internal.community;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Names each community (cluster) of related tables after the concept its <em>hub</em> tables share. Three signals
 * combine, all deterministic and instant (pure, so it is unit-tested):
 *
 * <ul>
 *   <li><b>Hub weighting</b> — a table's tokens count proportionally to its within-community foreign-key degree,
 *       so the central table(s) drive the name rather than every leaf table voting equally.</li>
 *   <li><b>Connector exclusion</b> — a bridge table linked more to <em>other</em> communities than its own is a
 *       connector, not a defining member, and is dropped from the vote.</li>
 *   <li><b>Schema-wide token filter</b> — a token present in (almost) every community (a table prefix like
 *       {@code app_}, boilerplate like {@code ref}) carries no signal and is removed, so it can't win everywhere
 *       and label them all the same.</li>
 * </ul>
 *
 * The surviving tokens are ranked by hub-weighted frequency; the two strongest are paired when both are broadly
 * shared ("Billing &amp; Payments"). With no graph (degrees all zero) it degrades to plain shared-token frequency.
 */
public final class CommunityNames {

	/** A table in a community: its name plus how many FK edges stay inside the community vs cross out of it. */
	public record TableNode(String name, int withinDegree, int crossDegree) {
		public TableNode(String name) {
			this(name, 0, 0);
		}
	}

	// Tokens that carry no naming signal — dropped so they never win.
	private static final Set<String> STOPWORDS = Set.of(
			"tbl", "table", "tables", "data", "info", "id", "ids", "ref", "refs", "map", "maps",
			"lookup", "lkp", "list", "log", "logs", "the", "of", "and", "rel", "link", "links", "type", "types");

	private CommunityNames() { }

	/**
	 * Name every community together so the schema-wide token filter can see the whole partition. {@code communities}
	 * maps a cluster id to its tables; the result maps each id to a 1–3 word Title Case label.
	 */
	public static Map<Integer, String> nameAll(Map<Integer, List<TableNode>> communities) {
		int numCommunities = Math.max(1, communities.size());
		// How many communities use each token at all (for the schema-wide filter).
		Map<String, Integer> communityFreq = new HashMap<>();
		for (List<TableNode> tables : communities.values()) {
			Set<String> tokensHere = new LinkedHashSet<>();
			for (TableNode t : safe(tables)) {
				tokensHere.addAll(tokens(t.name()));
			}
			for (String token : tokensHere) {
				communityFreq.merge(token, 1, Integer::sum);
			}
		}
		// A token is "schema-wide" once it shows up in most communities.
		int ubiquityThreshold = Math.max(2, (int) Math.ceil(0.7 * numCommunities));

		Map<Integer, String> out = new LinkedHashMap<>();
		for (Map.Entry<Integer, List<TableNode>> e : communities.entrySet()) {
			out.put(e.getKey(), nameOne(safe(e.getValue()), communityFreq, ubiquityThreshold));
		}
		return out;
	}

	/** Convenience for table names with no graph signal (single community, equal weighting). */
	public static String name(List<String> tableNames) {
		List<TableNode> nodes = (tableNames == null ? List.<String>of() : tableNames).stream()
				.filter(n -> n != null && !n.isBlank()).map(TableNode::new).toList();
		return nameAll(Map.of(0, nodes)).get(0);
	}

	private static String nameOne(List<TableNode> all, Map<String, Integer> communityFreq, int ubiquityThreshold) {
		if (all.isEmpty()) {
			return "Group";
		}
		// Drop connector tables (linked more outside than inside) — they bridge concepts, they don't define one.
		List<TableNode> core = all.stream()
				.filter(t -> !(t.crossDegree() > t.withinDegree() && t.crossDegree() >= 2))
				.toList();
		if (core.isEmpty()) {
			core = all;
		}
		if (core.size() == 1) {
			return humanizeName(core.get(0).name());
		}

		// Hub-weighted term frequency: a table's tokens count by 1 + its within-community degree.
		Map<String, Double> weight = new LinkedHashMap<>();
		Map<String, Integer> tableCount = new LinkedHashMap<>();
		for (TableNode t : core) {
			double w = 1.0 + t.withinDegree();
			for (String token : new LinkedHashSet<>(tokens(t.name()))) {
				weight.merge(token, w, Double::sum);
				tableCount.merge(token, 1, Integer::sum);
			}
		}
		// Candidates: tokens shared by ≥2 tables and not schema-wide.
		List<Scored> shared = new ArrayList<>();
		for (Map.Entry<String, Integer> e : tableCount.entrySet()) {
			if (e.getValue() >= 2 && communityFreq.getOrDefault(e.getKey(), 1) < ubiquityThreshold) {
				shared.add(new Scored(e.getKey(), e.getValue(), weight.get(e.getKey())));
			}
		}
		if (shared.isEmpty()) {
			return humanizeName(hub(core).name());   // no shared concept → name after the most central table
		}
		// Strongest first: hub-weighted frequency, then more tables, then longer (more specific) token, then alpha.
		shared.sort((a, b) -> {
			int byWeight = Double.compare(b.weight, a.weight);
			if (byWeight != 0) {
				return byWeight;
			}
			int byTables = Integer.compare(b.tableCount, a.tableCount);
			if (byTables != 0) {
				return byTables;
			}
			int byLen = Integer.compare(b.token.length(), a.token.length());
			return byLen != 0 ? byLen : a.token.compareTo(b.token);
		});

		Scored top = shared.get(0);
		// Pair a second token when it is also broadly shared and comparably strong ("Orders & Payments") — this is
		// how a genuinely two-concept community (two hubs, each with satellites) reads honestly.
		if (shared.size() >= 2) {
			Scored second = shared.get(1);
			if (second.tableCount >= Math.max(2, core.size() / 2)
					&& second.weight >= 0.6 * top.weight && !second.token.equals(top.token)) {
				return humanizeToken(top.token) + " & " + humanizeToken(second.token);
			}
		}
		return humanizeToken(top.token);
	}

	private record Scored(String token, int tableCount, double weight) { }

	private static TableNode hub(List<TableNode> core) {
		TableNode best = core.get(0);
		for (TableNode t : core) {
			if (t.withinDegree() > best.withinDegree()) {
				best = t;
			}
		}
		return best;
	}

	private static List<TableNode> safe(List<TableNode> tables) {
		return tables == null ? List.of()
				: tables.stream().filter(t -> t != null && t.name() != null && !t.name().isBlank()).toList();
	}

	// Split a table name into lowercase word tokens on non-alphanumeric and camelCase boundaries, minus stopwords.
	private static List<String> tokens(String table) {
		String spaced = table
				.replaceAll("([a-z0-9])([A-Z])", "$1 $2")   // camelCase → camel Case
				.replaceAll("[^A-Za-z0-9]+", " ");
		List<String> out = new ArrayList<>();
		for (String raw : spaced.trim().split("\\s+")) {
			String token = raw.toLowerCase(Locale.ROOT);
			if (token.length() >= 2 && !STOPWORDS.contains(token)) {
				out.add(singular(token));
			}
		}
		return out;
	}

	private static String humanizeToken(String token) {
		return Character.toUpperCase(token.charAt(0)) + token.substring(1);
	}

	// Title-case a whole table name as a fallback label ("order_status" → "Order Status").
	private static String humanizeName(String table) {
		StringBuilder out = new StringBuilder();
		for (String token : tokens(table)) {
			out.append(out.isEmpty() ? "" : " ").append(humanizeToken(token));
		}
		return out.isEmpty() ? humanizeToken(table.toLowerCase(Locale.ROOT)) : out.toString();
	}

	// A light de-plural so "orders" and "order" share a token. Not linguistically perfect — just enough to group.
	// Guards the common non-plural endings (status, bonus, basis, address, gas…) so a real word isn't truncated.
	private static final Set<String> KEEP_TRAILING_S = Set.of("us", "is", "ss", "os", "as", "ys");

	private static String singular(String token) {
		if (token.endsWith("ies") && token.length() > 4) {
			return token.substring(0, token.length() - 3) + "y";
		}
		if (token.endsWith("ses") && token.length() > 4) {
			return token.substring(0, token.length() - 2);
		}
		if (token.endsWith("s") && token.length() > 3
				&& !KEEP_TRAILING_S.contains(token.substring(token.length() - 2))) {
			return token.substring(0, token.length() - 1);
		}
		return token;
	}
}
