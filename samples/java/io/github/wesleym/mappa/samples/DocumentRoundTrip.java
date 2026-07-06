package io.github.wesleym.mappa.samples;

import io.github.wesleym.mappa.Mappa;
import io.github.wesleym.mappa.MappaMap;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import java.awt.BorderLayout;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The native {@code .mappa} document round trip: build a map, write the compact binary to disk, read it
 * straight back, and draw the reopened copy. What you see is the map that survived serialization.
 */
public final class DocumentRoundTrip {
	private DocumentRoundTrip() { }

	public static void main(String[] args) throws Exception {
		MappaMap original = Samples.commerce();

		Path file = Files.createTempFile("commerce", ".mappa");
		original.write(file);
		MappaMap reopened = Mappa.read(file);

		String note = String.format("  %s -> %,d bytes on disk -> %d entities, %d relationships reopened",
				file.getFileName(), Files.size(file), reopened.entities().size(),
				reopened.relationships().size());
		System.out.println(note.strip());

		SwingUtilities.invokeLater(() -> {
			JLabel status = new JLabel(note);
			status.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
			JPanel root = new JPanel(new BorderLayout());
			root.add(Mappa.view(reopened).component(), BorderLayout.CENTER);
			root.add(status, BorderLayout.SOUTH);
			Samples.show("Document round trip", root, 1120, 760);
		});
	}
}
