package io.github.wesleym.mappa.samples;

import io.github.wesleym.mappa.Mappa;
import io.github.wesleym.mappa.MappaMap;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import java.awt.BorderLayout;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted layout: drag the boxes into an arrangement, and every drag saves it to a native {@code .mappa}
 * document (positions and all). "Reopen from disk" reads that file back and the diagram returns exactly as
 * you left it — no re-layout, no reshuffle. This is also the fast path: a saved map skips auto-layout entirely.
 */
public final class SavedLayout {
	private SavedLayout() { }

	public static void main(String[] args) throws Exception {
		Path file = Files.createTempFile("mappa-arrangement", ".mappa");
		MappaMap map = Samples.commerce();

		SwingUtilities.invokeLater(() -> {
			JPanel host = new JPanel(new BorderLayout());
			JLabel status = new JLabel("  drag the boxes — every move saves to " + file.getFileName());
			status.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

			// Show a map, saving the arrangement on every drag. Reopening reads the file and restores it.
			Runnable[] show = new Runnable[1];
			show[0] = () -> show(host, load(file, map, status), file, status, show);
			show[0].run();

			JButton reopen = new JButton("Reopen from disk");
			reopen.addActionListener(e -> show[0].run());

			JPanel bar = new JPanel(new BorderLayout());
			bar.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
			bar.add(reopen, BorderLayout.WEST);

			JPanel root = new JPanel(new BorderLayout());
			root.add(bar, BorderLayout.NORTH);
			root.add(host, BorderLayout.CENTER);
			root.add(status, BorderLayout.SOUTH);
			Samples.show("Saved layout", root, 1120, 780);
		});
	}

	private static void show(JPanel host, MappaMap map, Path file, JLabel status, Runnable[] show) {
		host.removeAll();
		host.add(Mappa.view(map)
				.onArranged(arranged -> {
					try {
						arranged.write(file);
						status.setText("  saved " + arranged.positions().size() + " box positions to "
								+ file.getFileName());
					}
					catch (Exception ex) {
						status.setText("  could not save: " + ex.getMessage());
					}
				})
				.component(), BorderLayout.CENTER);
		host.revalidate();
		host.repaint();
	}

	// The saved arrangement if one exists yet, otherwise the fresh (auto-laid-out) map.
	private static MappaMap load(Path file, MappaMap fallback, JLabel status) {
		try {
			if (Files.size(file) > 0) {
				MappaMap saved = Mappa.read(file);
				status.setText("  restored " + saved.positions().size() + " box positions from "
						+ file.getFileName());
				return saved;
			}
		}
		catch (Exception ignored) {
			// no readable arrangement yet — fall back to the auto-laid-out map
		}
		return fallback;
	}
}
