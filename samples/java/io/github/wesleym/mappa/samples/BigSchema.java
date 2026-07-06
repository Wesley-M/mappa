package io.github.wesleym.mappa.samples;

import io.github.wesleym.mappa.Mappa;
import io.github.wesleym.mappa.MappaBackground;
import io.github.wesleym.mappa.MappaDetail;
import io.github.wesleym.mappa.MappaEdges;
import io.github.wesleym.mappa.MappaLayout;
import io.github.wesleym.mappa.MappaMap;
import io.github.wesleym.mappa.MappaMinimap;
import io.github.wesleym.mappa.MappaTheme;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;

/**
 * A stress-test playground: dial the schema up with the knobs on top — domains, tables per domain, columns
 * per table (into the tens of thousands of tables), plus layout, detail, edges, backdrop, theme, and minimap.
 * Every change re-renders (Build forces it); the schema is generated off the UI thread and the status line
 * reports the table/column count and how long generation took (the layout then runs in the background).
 * Drag the minimap to fly around, pan and wheel-zoom, click a table to spotlight it.
 */
public final class BigSchema {
	private BigSchema() { }

	private static final String[] NOUNS = { "customer", "product", "order", "payment", "shipment", "invoice",
			"ticket", "review", "campaign", "warehouse", "vendor", "contract", "employee", "account",
			"subscription", "device", "location", "project", "task", "message", "document", "asset", "policy",
			"claim", "booking", "session", "transaction", "notification" };
	private static final String[] SATELLITES = { "item", "status", "note", "event", "tag", "log", "attachment",
			"assignment", "history" };
	private static final String[] TYPES = { "text", "int", "decimal", "timestamp", "boolean", "date", "uuid",
			"json", "bigint", "numeric", "varchar", "float" };

	public static void main(String[] args) {
		SwingUtilities.invokeLater(BigSchema::buildUi);
	}

	private static void buildUi() {
		JSpinner domains = spinner(50, 1, 5000, 5);
		JSpinner perDomain = spinner(10, 1, 60, 1);
		JSpinner columns = spinner(8, 1, 60, 1);
		JComboBox<MappaLayout> layout = combo(MappaLayout.values(), MappaLayout.FORCE);
		JComboBox<MappaDetail> detail = combo(MappaDetail.values(), MappaDetail.AUTO);
		JComboBox<MappaEdges> edges = combo(MappaEdges.values(), MappaEdges.AUTO);
		JComboBox<MappaBackground> backdrop = combo(MappaBackground.values(), MappaBackground.DOTS);
		JComboBox<MappaMinimap> minimap = combo(MappaMinimap.values(), MappaMinimap.BOTTOM_RIGHT);
		JComboBox<String> theme = combo(new String[] { "Light", "Dark" }, "Light");
		JButton build = new JButton("Build");
		JLabel status = new JLabel("  ");
		JPanel center = new JPanel(new BorderLayout());

		// A FlowLayout bar in BorderLayout.NORTH only gets one row of height (a second wrapped row is clipped),
		// so lay the controls out as two explicit rows that are both always visible.
		JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
		add(row1, "Domains", domains);
		add(row1, "Tables/domain", perDomain);
		add(row1, "Columns", columns);
		row1.add(build);
		JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
		add(row2, "Layout", layout);
		add(row2, "Detail", detail);
		add(row2, "Edges", edges);
		add(row2, "Backdrop", backdrop);
		add(row2, "Minimap", minimap);
		add(row2, "Theme", theme);
		JPanel bar = new JPanel(new GridLayout(2, 1));
		bar.add(row1);
		bar.add(row2);

		boolean[] building = { false };
		Runnable[] rebuildRef = new Runnable[1];
		Timer[] debounceRef = new Timer[1];
		Runnable rebuild = () -> {
			if (building[0]) {
				debounceRef[0].restart();   // a build is in flight — retry with the latest values once it finishes
				return;
			}
			building[0] = true;
			int nDomains = (int) domains.getValue();
			int perDom = (int) perDomain.getValue();
			int nCols = (int) columns.getValue();
			int tables = nDomains * perDom;
			build.setEnabled(false);
			status.setText("  building " + tables + " tables...");
			new SwingWorker<MappaMap, Void>() {
				private final long start = System.nanoTime();

				@Override
				protected MappaMap doInBackground() {
					return generate(nDomains, perDom, nCols);   // heavy work off the UI thread
				}

				@Override
				protected void done() {
					long ms = (System.nanoTime() - start) / 1_000_000;
					building[0] = false;
					build.setEnabled(true);
					MappaMap map;
					try {
						map = get();
					}
					catch (Exception ex) {
						status.setText("  failed: " + ex.getMessage());
						return;
					}
					MappaTheme th = "Dark".equals(theme.getSelectedItem()) ? MappaTheme.dark() : MappaTheme.light();
					center.removeAll();
					center.add(Mappa.view(map)
							.layout((MappaLayout) layout.getSelectedItem())
							.detail((MappaDetail) detail.getSelectedItem())
							.edges((MappaEdges) edges.getSelectedItem())
							.background((MappaBackground) backdrop.getSelectedItem())
							.minimap((MappaMinimap) minimap.getSelectedItem())
							.theme(th)
							.component(), BorderLayout.CENTER);
					center.revalidate();
					center.repaint();
					status.setText("  " + tables + " tables, " + (tables * nCols) + " columns - generated in "
							+ ms + " ms (layout runs in the background)");
				}
			}.execute();
		};
		rebuildRef[0] = rebuild;

		// Apply changes live, debounced, so rapid edits (holding a spinner, flipping combos) coalesce into one
		// rebuild — no need to hunt for the Build button.
		Timer debounce = new Timer(280, e -> rebuildRef[0].run());
		debounce.setRepeats(false);
		debounceRef[0] = debounce;
		Runnable schedule = debounce::restart;
		for (JComboBox<?> combo : new JComboBox<?>[] { layout, detail, edges, backdrop, minimap, theme }) {
			combo.addActionListener(e -> schedule.run());
		}
		for (JSpinner spinner : new JSpinner[] { domains, perDomain, columns }) {
			spinner.addChangeListener(e -> schedule.run());
		}
		build.addActionListener(e -> rebuild.run());

		JPanel root = new JPanel(new BorderLayout());
		root.add(bar, BorderLayout.NORTH);
		root.add(center, BorderLayout.CENTER);
		root.add(status, BorderLayout.SOUTH);
		Samples.show("Big schema - stress test", root, 1360, 900);
		rebuild.run();   // build the initial diagram
	}

	// A schema of {@code nDomains} domains, each a densely-linked star of {@code perDomain} tables (a root plus
	// satellites that reference it), every table carrying {@code nCols} columns; domains form a branching tree.
	private static MappaMap generate(int nDomains, int perDomain, int nCols) {
		var b = Mappa.schema("Platform");
		for (int d = 0; d < nDomains; d++) {
			String base = domain(d);
			String parent = d == 0 ? null : domain((d - 1) / 2);
			b.table(base, t -> {
				t.primaryKey("id", "uuid");
				if (parent != null) {
					t.reference(parent + "_id", "uuid", parent, "id");   // cross-domain bridge
				}
				for (int c = 0; c < nCols; c++) {
					t.column("col_" + c, TYPES[c % TYPES.length]);
				}
			});
			for (int s = 1; s < perDomain; s++) {
				String name = base + "_" + satellite(s - 1);
				b.table(name, t -> {
					t.primaryKey("id", "uuid").reference(base + "_id", "uuid", base, "id");
					for (int c = 0; c < nCols; c++) {
						t.column("col_" + c, TYPES[c % TYPES.length]);
					}
				});
			}
		}
		return b.build();
	}

	private static String domain(int d) {
		return NOUNS[d % NOUNS.length] + (d >= NOUNS.length ? "_" + (d / NOUNS.length + 1) : "");
	}

	private static String satellite(int s) {
		return SATELLITES[s % SATELLITES.length] + (s >= SATELLITES.length ? "_" + (s / SATELLITES.length) : "");
	}

	private static JSpinner spinner(int value, int min, int max, int step) {
		return new JSpinner(new SpinnerNumberModel(value, min, max, step));
	}

	private static <T> JComboBox<T> combo(T[] values, T selected) {
		JComboBox<T> box = new JComboBox<>(values);
		box.setSelectedItem(selected);
		return box;
	}

	private static void add(JPanel bar, String label, JComponent field) {
		bar.add(new JLabel(label));
		bar.add(field);
	}
}
