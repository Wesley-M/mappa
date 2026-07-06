package io.github.wesleym.mappa.samples;

import io.github.wesleym.mappa.Mappa;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/** The starter: one schema, one window. Everything past this is refinement. */
public final class HelloMappa {
	private HelloMappa() { }

	public static void main(String[] args) {
		SwingUtilities.invokeLater(() -> {
			var map = Mappa.schema("Store")
					.table("orders", t -> t
							.primaryKey("id", "uuid")
							.reference("customer_id", "uuid", "customers", "id")
							.reference("status_id", "int", "order_status", "id")
							.column("total", "decimal"))
					.table("customers", t -> t
							.primaryKey("id", "uuid")
							.column("email", "text"))
					.table("order_status", t -> t
							.primaryKey("id", "int")
							.column("name", "text"))
					.build();

			JFrame frame = new JFrame("Mappa - Hello");
			frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
			frame.setContentPane(Mappa.view(map).component());   // live: pan, wheel-zoom, drag, fit
			frame.setSize(960, 640);
			frame.setLocationByPlatform(true);
			frame.setVisible(true);
		});
	}
}
