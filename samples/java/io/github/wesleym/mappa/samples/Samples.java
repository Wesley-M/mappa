package io.github.wesleym.mappa.samples;

import io.github.wesleym.mappa.Mappa;
import io.github.wesleym.mappa.MappaMap;

import javax.swing.JComponent;
import javax.swing.JFrame;

import java.awt.Color;

/** The two things every sample needs: a window, and the demo schema they all draw. */
final class Samples {

	private Samples() { }

	/** Opens a sample window around {@code content} — plain Swing, nothing Mappa-specific. */
	static JFrame show(String title, JComponent content, int width, int height) {
		JFrame frame = new JFrame("Mappa - " + title);
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.getContentPane().setBackground(new Color(0xF2, 0xEF, 0xE8));
		frame.setContentPane(content);
		frame.setSize(width, height);
		frame.setLocationByPlatform(true);
		frame.setVisible(true);
		return frame;
	}

	/**
	 * The demo schema every sample maps: a small commerce model with the shapes worth seeing drawn — a
	 * self-reference ({@code categories.parent_id}), a VIEW, and one inferred (suggested) relationship.
	 */
	static MappaMap commerce() {
		return Mappa.schema("Commerce")
				.table("customers", t -> t
						.primaryKey("id", "uuid")
						.column("email", "text")
						.column("full_name", "text")
						.column("created_at", "timestamp"))
				.table("categories", t -> t
						.primaryKey("id", "int")
						.reference("parent_id", "int", "categories", "id")
						.column("name", "text"))
				.table("products", t -> t
						.primaryKey("id", "uuid")
						.reference("category_id", "int", "categories", "id")
						.column("sku", "text")
						.column("name", "text")
						.column("price", "decimal"))
				.table("orders", t -> t
						.primaryKey("id", "uuid")
						.reference("customer_id", "uuid", "customers", "id")
						.reference("status_id", "int", "order_status", "id")
						.column("total", "decimal")
						.column("placed_at", "timestamp"))
				.table("order_items", t -> t
						.primaryKey("id", "uuid")
						.reference("order_id", "uuid", "orders", "id")
						.reference("product_id", "uuid", "products", "id")
						.column("quantity", "int")
						.column("unit_price", "decimal"))
				.table("order_status", t -> t
						.primaryKey("id", "int")
						.column("label", "text"))
				.view("revenue_by_day", t -> t
						.column("day", "date")
						.column("gross", "decimal"))
				.reference("revenue_by_day", "day", "orders", "placed_at", true)
				.build();
	}
}
