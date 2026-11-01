package fr.autopaterns.waypoints;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import fr.autopaterns.core.AutoPaterns;
import fr.autopaterns.core.Log;
import fr.autopaterns.core.Texts;
import fr.autopaterns.gui.Theme;
import fr.autopaterns.gui.UiScreen;
import fr.autopaterns.gui.widget.UiButton;
import fr.autopaterns.gui.widget.UiText;
import net.minecraft.client.gui.screens.Screen;

/**
 * Import de waypoints (2.x : {@code WaypointImportScreen}) : fichiers {@code .txt} du dossier
 * {@code autopaterns/waypoints-export} (un bouton « Importer » par fichier) ou texte du presse-papiers.
 * Les patterns importés reçoivent un nom libre (« nom (2) »…) ; les lignes L et F d'un fichier complet sont ignorées.
 */
public class WaypointImportScreen extends UiScreen {
	private final List<Path> files = new ArrayList<>();
	private boolean scanned;
	private String status;

	public WaypointImportScreen(Screen parent) {
		super(Texts.c("autopaterns.waypoints.import.title"), parent);
	}

	private static Waypoints st() {
		return AutoPaterns.get().waypoints().state();
	}

	@Override
	protected int preferredWidth() {
		return 400;
	}

	@Override
	protected int preferredHeight() {
		return 280;
	}

	@Override
	protected int footerHeight() {
		return 20;
	}

	@Override
	protected String subtitle() {
		return status != null ? status : Texts.tr("autopaterns.waypoints.import.help");
	}

	/** Liste des fichiers .txt (tri naturel des chemins, comme en 2.x). */
	private void scan() {
		files.clear();
		try {
			Path dir = WaypointIO.exportDir();
			Files.createDirectories(dir);
			try (Stream<Path> s = Files.list(dir)) {
				s.filter(f -> Files.isRegularFile(f) && f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt"))
					.sorted()
					.forEach(files::add);
			}
		} catch (Exception e) {
			Log.error("liste des exports de waypoints illisible", e);
		}
		scanned = true;
	}

	/** Action puis nouvelle lecture du dossier et reconstruction (2.x : à chaque action). */
	private void act(Runnable r) {
		try {
			r.run();
		} catch (RuntimeException e) {
			Log.error("waypoints : action d'import", e);
			status = Texts.tr("autopaterns.waypoints.error", e.getClass().getSimpleName());
		}
		scanned = false;
		if (minecraft != null && minecraft.gui.screen() == this) refresh();
	}

	@Override
	protected void build() {
		if (!scanned) scan();
		int x = left();
		int cw = contentWidth();
		int y = top();
		if (files.isEmpty()) {
			content(new UiText(x, y + 12, cw, 12, Texts.tr("autopaterns.waypoints.import.no_file")).color(Theme.TEXT_MUTED));
		}
		for (Path f : files) {
			final Path file = f;
			String name = file.getFileName().toString();
			content(new WpRow(x, y, cw - 78, 20, null, () -> name, null, null, null));
			content(new UiButton(x + cw - 72, y, 72, 20, Texts.tr("autopaterns.waypoints.import.one"),
				() -> act(() -> status = st().importFile(file))).style(UiButton.Style.PRIMARY));
			y += 24;
		}

		int fy = footerY();
		int b3 = (cw - 8) / 3;
		footer(new UiButton(x, fy, b3, 18, Texts.tr("autopaterns.waypoints.import.clipboard"),
			() -> act(() -> status = st().importClipboard(minecraft))));
		footer(new UiButton(x + b3 + 4, fy, b3, 18, Texts.tr("autopaterns.waypoints.open_folder"), () -> act(() -> {
			if (!WaypointsScreen.openFolder(WaypointIO.exportDir(), false)) {
				status = Texts.tr("autopaterns.waypoints.open_folder_failed");
			}
		})));
		footer(new UiButton(x + 2 * (b3 + 4), fy, cw - 2 * (b3 + 4), 18, Texts.tr("autopaterns.waypoints.import.refresh"),
			() -> act(() -> { })));
	}
}
