package io.github.wesleym.mappa.samples;

import io.github.wesleym.mappa.Mappa;
import io.github.wesleym.mappa.MappaMap;

import javax.swing.SwingUtilities;

/**
 * A big schema — fifty tables across ten domains — so there's something to get lost in. Mappa detects the
 * communities, names them, and lays out each as its own region. Because the diagram is large, the live view
 * shows an overview <b>minimap</b> in the corner: drag its frame to jump the camera around, or pan and
 * wheel-zoom the canvas directly and watch the minimap track you. Click any table to spotlight it.
 */
public final class BigSchema {
	private BigSchema() { }

	public static void main(String[] args) {
		SwingUtilities.invokeLater(() -> Samples.show("Big schema", Mappa.view(platform()).component(), 1200, 820));
	}

	// Ten domains, each a densely-linked cluster of five tables, with a few sparse references bridging them.
	private static MappaMap platform() {
		String[] domains = { "customer", "product", "order", "payment", "shipment",
				"invoice", "ticket", "review", "campaign", "warehouse" };
		var b = Mappa.schema("Platform");
		for (String d : domains) {
			b.table(d, t -> t
					.primaryKey("id", "uuid")
					.column("name", "text")
					.column("status", "text")
					.column("created_at", "timestamp"));
			b.table(d + "_item", t -> t.primaryKey("id", "uuid").reference(d + "_id", "uuid", d, "id")
					.column("qty", "int"));
			b.table(d + "_status", t -> t.primaryKey("id", "int").reference(d + "_id", "uuid", d, "id")
					.column("label", "text"));
			b.table(d + "_note", t -> t.primaryKey("id", "uuid").reference(d + "_id", "uuid", d, "id")
					.column("body", "text"));
			b.table(d + "_event", t -> t.primaryKey("id", "uuid").reference(d + "_id", "uuid", d, "id")
					.column("kind", "text"));
		}
		String[][] bridges = {
				{ "order", "customer" }, { "order", "product" }, { "payment", "order" }, { "shipment", "order" },
				{ "invoice", "payment" }, { "review", "product" }, { "review", "customer" }, { "ticket", "customer" },
				{ "warehouse", "product" }, { "campaign", "customer" }, { "order_item", "product" },
				{ "invoice", "customer" } };
		for (String[] bridge : bridges) {
			b.reference(bridge[0], bridge[1] + "_id", bridge[1], "id");
		}
		return b.build();
	}
}
