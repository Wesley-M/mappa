package io.github.wesleym.mappa.samples;

import io.github.wesleym.mappa.Mappa;
import io.github.wesleym.mappa.MappaBackground;
import io.github.wesleym.mappa.MappaDetail;
import io.github.wesleym.mappa.MappaEdges;
import io.github.wesleym.mappa.MappaLayout;
import io.github.wesleym.mappa.MappaTheme;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import java.awt.Color;

/**
 * The curated controls, all set at once, over the demo commerce schema: a chosen layout, curved edges,
 * key-only detail, a dotted backdrop, a custom dark theme, and a right-click entity action the host owns.
 */
public final class SchemaTour {
	private SchemaTour() { }

	public static void main(String[] args) {
		SwingUtilities.invokeLater(() -> {
			var theme = MappaTheme.light()               // an old atlas: aged paper, cartographer's inks
					.background(new Color(0xEDE1CC))
					.surface(new Color(0xF2E8D5))
					.text(new Color(0x3B2F22))
					.entityHeader(new Color(0x28568E))   // map-navy headers
					.viewHeader(new Color(0x007F68))     // teal views
					.accent(new Color(0xBF6F33))         // sienna keys
					.suggestedReference(new Color(0xBD9A32));   // gold inferred edges

			var view = Mappa.view(Samples.commerce())
					.layout(MappaLayout.LAYERED)
					.edges(MappaEdges.CURVED)
					.detail(MappaDetail.KEYS)
					.background(MappaBackground.DOTS)
					.relationshipLabels(true)
					.theme(theme)
					.action("Describe", entity -> JOptionPane.showMessageDialog(null,
							entity.name() + " - " + entity.kind() + ", " + entity.fields().size() + " fields"))
					.onEntitySelected(entity -> System.out.println(
							"selected: " + (entity == null ? "(none)" : entity.name())));

			Samples.show("Schema tour", view.component(), 1120, 760);
		});
	}
}
