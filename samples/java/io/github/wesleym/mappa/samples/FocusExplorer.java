package io.github.wesleym.mappa.samples;

import io.github.wesleym.mappa.Mappa;
import io.github.wesleym.mappa.MappaLayout;
import io.github.wesleym.mappa.MappaMap;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;

import java.awt.BorderLayout;
import java.awt.Dimension;

/**
 * A big schema is easier read one neighbourhood at a time. Pick an entity and the view narrows to it and
 * its direct relationships via {@link MappaMap#focus} - the same model operation a "focus this table"
 * button in a real tool would call. "Whole schema" restores the full map.
 */
public final class FocusExplorer {
	private FocusExplorer() { }

	private static final String WHOLE = "(whole schema)";
	private static final MappaMap MAP = Samples.commerce();

	public static void main(String[] args) {
		SwingUtilities.invokeLater(() -> {
			DefaultListModel<String> model = new DefaultListModel<>();
			model.addElement(WHOLE);
			MAP.entities().forEach(e -> model.addElement(e.name()));
			JList<String> list = new JList<>(model);
			list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
			list.setSelectedIndex(0);
			list.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

			JLabel status = new JLabel("  the whole schema");
			status.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

			JPanel host = new JPanel(new BorderLayout());
			Runnable rebuild = () -> {
				String pick = list.getSelectedValue();
				MappaMap shown = pick == null || pick.equals(WHOLE) ? MAP : MAP.focus(pick);
				JComponent map = Mappa.view(shown)
						.layout(MappaLayout.LAYERED)
						.relationshipLabels(!shown.equals(MAP) && shown.relationships().size() <= 6)
						.onEntitySelected(entity -> status.setText(
								"  " + (entity == null ? "(nothing selected)" : entity.name() + " - click a box")))
						.component();
				host.removeAll();
				host.add(map, BorderLayout.CENTER);
				host.revalidate();
				host.repaint();
				status.setText("  showing " + shown.entities().size() + " of " + MAP.entities().size()
						+ " entities" + (pick.equals(WHOLE) ? "" : ", focused on " + pick));
			};
			list.addListSelectionListener(e -> {
				if (!e.getValueIsAdjusting()) {
					rebuild.run();
				}
			});
			rebuild.run();

			JScrollPane side = new JScrollPane(list);
			side.setPreferredSize(new Dimension(190, 0));

			JPanel root = new JPanel(new BorderLayout());
			root.add(side, BorderLayout.WEST);
			root.add(host, BorderLayout.CENTER);
			root.add(status, BorderLayout.SOUTH);
			Samples.show("Focus explorer", root, 1200, 780);
		});
	}
}
