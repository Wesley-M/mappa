package io.github.wesleym.mappa.samples;

import io.github.wesleym.mappa.Mappa;
import io.github.wesleym.mappa.MappaLayout;
import io.github.wesleym.mappa.MappaMap;
import io.github.wesleym.mappa.MappaTheme;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import java.awt.BorderLayout;
import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;

/** Re-theming is one immutable value away. A handful of themes, each a short expression, switched live. */
public final class ThemeGallery {
	private ThemeGallery() { }

	private static final MappaMap MAP = Samples.commerce();

	private static Map<String, MappaTheme> themes() {
		Map<String, MappaTheme> themes = new LinkedHashMap<>();
		themes.put("Light (default)", MappaTheme.light());
		themes.put("Dark (default)", MappaTheme.dark());
		// Custom themes in the paper-and-ink spirit: an aged atlas, a newsprint gazette, a viridian nocturne.
		themes.put("Atlas", MappaTheme.light()
				.background(new Color(0xEDE1CC)).surface(new Color(0xF2E8D5)).text(new Color(0x3B2F22))
				.line(new Color(0xDFD2B8)).entityHeader(new Color(0x28568E)).viewHeader(new Color(0x007F68))
				.accent(new Color(0xBF6F33)).suggestedReference(new Color(0xBD9A32)));
		themes.put("Gazette", MappaTheme.light()
				.background(new Color(0xEBEAE4)).surface(new Color(0xF4F3EE)).text(new Color(0x1A1A1A))
				.line(new Color(0xDAD8D0)).entityHeader(new Color(0x1A1A1A)).viewHeader(new Color(0xB02418))
				.accent(new Color(0xB02418)).reference(new Color(0x3A3A3A)));
		themes.put("Nocturne", MappaTheme.dark()
				.background(new Color(0x0E1A17)).surface(new Color(0x152420)).text(new Color(0xE8EDE9))
				.line(new Color(0x25352F)).entityHeader(new Color(0x2C7A5B)).viewHeader(new Color(0xC8A24A))
				.accent(new Color(0xE0B24A)).reference(new Color(0x4FB08A)));
		return themes;
	}

	public static void main(String[] args) {
		SwingUtilities.invokeLater(() -> {
			Map<String, MappaTheme> themes = themes();
			JComboBox<String> picker = new JComboBox<>(themes.keySet().toArray(String[]::new));

			JPanel host = new JPanel(new BorderLayout());
			Runnable rebuild = () -> {
				JComponent map = Mappa.view(MAP)
						.layout(MappaLayout.LAYERED)
						.theme(themes.get((String) picker.getSelectedItem()))
						.component();
				host.removeAll();
				host.add(map, BorderLayout.CENTER);
				host.revalidate();
				host.repaint();
			};
			picker.addActionListener(e -> rebuild.run());
			rebuild.run();

			JPanel bar = new JPanel(new BorderLayout());
			bar.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
			bar.add(picker, BorderLayout.WEST);

			JPanel root = new JPanel(new BorderLayout());
			root.add(bar, BorderLayout.NORTH);
			root.add(host, BorderLayout.CENTER);
			Samples.show("Theme gallery", root, 1160, 780);
		});
	}
}
