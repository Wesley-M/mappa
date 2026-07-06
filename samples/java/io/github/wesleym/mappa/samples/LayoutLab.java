package io.github.wesleym.mappa.samples;

import io.github.wesleym.mappa.Mappa;
import io.github.wesleym.mappa.MappaBackground;
import io.github.wesleym.mappa.MappaDetail;
import io.github.wesleym.mappa.MappaEdges;
import io.github.wesleym.mappa.MappaLayout;
import io.github.wesleym.mappa.MappaMap;
import io.github.wesleym.mappa.MappaTheme;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;

/**
 * Every curated control on a picker: change layout, edges, detail, background, or theme and the map
 * re-lays-out live. Shows the shape of a host that reconfigures a view - swap the centre component,
 * nothing else. Mappa owns the drawing; the host owns the choices.
 */
public final class LayoutLab {
	private LayoutLab() { }

	private static final MappaMap MAP = Samples.commerce();

	public static void main(String[] args) {
		SwingUtilities.invokeLater(() -> {
			JComboBox<MappaLayout> layout = new JComboBox<>(MappaLayout.values());
			JComboBox<MappaEdges> edges = new JComboBox<>(MappaEdges.values());
			JComboBox<MappaDetail> detail = new JComboBox<>(MappaDetail.values());
			JComboBox<MappaBackground> background = new JComboBox<>(MappaBackground.values());
			JCheckBox dark = new JCheckBox("dark");
			JCheckBox labels = new JCheckBox("labels");
			layout.setSelectedItem(MappaLayout.LAYERED);

			JPanel host = new JPanel(new BorderLayout());
			Runnable rebuild = () -> {
				JComponent map = Mappa.view(MAP)
						.layout((MappaLayout) layout.getSelectedItem())
						.edges((MappaEdges) edges.getSelectedItem())
						.detail((MappaDetail) detail.getSelectedItem())
						.background((MappaBackground) background.getSelectedItem())
						.relationshipLabels(labels.isSelected())
						.theme(dark.isSelected() ? MappaTheme.dark() : MappaTheme.light())
						.component();
				host.removeAll();
				host.add(map, BorderLayout.CENTER);
				host.revalidate();
				host.repaint();
			};
			for (JComboBox<?> picker : new JComboBox<?>[] { layout, edges, detail, background }) {
				picker.addActionListener(e -> rebuild.run());
			}
			dark.addActionListener(e -> rebuild.run());
			labels.addActionListener(e -> rebuild.run());
			rebuild.run();

			JPanel bar = new JPanel();
			bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
			bar.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
			add(bar, "Layout", layout);
			add(bar, "Edges", edges);
			add(bar, "Detail", detail);
			add(bar, "Background", background);
			bar.add(dark);
			bar.add(labels);

			JPanel root = new JPanel(new BorderLayout());
			root.add(bar, BorderLayout.NORTH);
			root.add(host, BorderLayout.CENTER);
			Samples.show("Layout lab", root, 1180, 780);
		});
	}

	private static void add(JPanel bar, String label, JComponent field) {
		JLabel caption = new JLabel(label + "  ");
		caption.setAlignmentY(Component.CENTER_ALIGNMENT);
		field.setMaximumSize(new Dimension(150, 28));
		bar.add(caption);
		bar.add(field);
		bar.add(javax.swing.Box.createHorizontalStrut(14));
	}
}
