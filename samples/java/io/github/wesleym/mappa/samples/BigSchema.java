package io.github.wesleym.mappa.samples;

import io.github.wesleym.mappa.Mappa;
import io.github.wesleym.mappa.MappaLayout;
import io.github.wesleym.mappa.MappaMap;
import io.github.wesleym.mappa.MappaMinimap;

import javax.swing.SwingUtilities;

/**
 * A stress test — five hundred tables across fifty domains, columns and all — to see Mappa at the limit.
 * It detects the communities, names them, and lays out each as its own region; the live view virtualises the
 * draw (only what's near the viewport is rendered in full, the rest as low geometry) and shows an overview
 * <b>minimap</b>. Drag the minimap to fly around, pan and wheel-zoom the canvas, or click a table to
 * spotlight it. This is deliberately huge: it should stay responsive.
 */
public final class BigSchema {
	private BigSchema() { }

	private static final int DOMAINS = 50;
	private static final String[] NOUNS = { "customer", "product", "order", "payment", "shipment", "invoice",
			"ticket", "review", "campaign", "warehouse", "vendor", "contract", "employee", "account",
			"subscription", "device", "location", "project", "task", "message", "document", "asset", "policy",
			"claim", "booking", "session", "transaction", "notification" };
	private static final String[] SATELLITES = { "item", "status", "note", "event", "tag", "log", "attachment",
			"assignment", "history" };

	public static void main(String[] args) {
		SwingUtilities.invokeLater(() -> Samples.show("Big schema", Mappa.view(platform())
				.layout(MappaLayout.FORCE)            // spread the fifty communities organically to all sides
				.minimap(MappaMinimap.BOTTOM_RIGHT)   // AUTO already shows it here; a corner (or OFF) is your call
				.component(), 1280, 860));
	}

	// Fifty domains of ten tables each: a root plus nine satellites that reference it (a dense star), with each
	// domain's root also referencing the previous domain's root (the sparse bridges between communities).
	private static MappaMap platform() {
		var b = Mappa.schema("Platform");
		for (int d = 0; d < DOMAINS; d++) {
			String base = domain(d);
			String prev = d == 0 ? null : domain((d - 1) / 2);   // a branching tree of domains, not a chain
			b.table(base, t -> {
				t.primaryKey("id", "uuid")
						.column("name", "text").column("code", "text").column("status", "text")
						.column("amount", "decimal").column("quantity", "int").column("active", "boolean")
						.column("created_at", "timestamp").column("updated_at", "timestamp");
				if (prev != null) {
					t.reference(prev + "_id", "uuid", prev, "id");   // cross-domain bridge, as a real FK column
				}
			});
			for (String s : SATELLITES) {
				b.table(base + "_" + s, t -> t
						.primaryKey("id", "uuid")
						.reference(base + "_id", "uuid", base, "id")
						.column("label", "text").column("position", "int").column("value", "decimal")
						.column("note", "text").column("flag", "boolean").column("created_at", "timestamp"));
			}
		}
		return b.build();
	}

	private static String domain(int d) {
		return NOUNS[d % NOUNS.length] + (d >= NOUNS.length ? "_" + (d / NOUNS.length + 1) : "");
	}
}
