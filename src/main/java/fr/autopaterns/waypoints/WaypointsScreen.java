package fr.autopaterns.waypoints;

import java.nio.file.Files;
import java.nio.file.Path;

import fr.autopaterns.core.AutoPaterns;
import fr.autopaterns.core.Log;
import fr.autopaterns.core.Texts;
import fr.autopaterns.gui.Theme;
import fr.autopaterns.gui.UiScreen;
import fr.autopaterns.gui.widget.UiButton;
import fr.autopaterns.gui.widget.UiText;
import fr.autopaterns.input.KeyDef;
import fr.autopaterns.input.Keys;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Util;

/**
 * Menu WAYPOINTS (2.x : {@code WaypointListScreen}) : liste des patterns de waypoints, affichage, lancement /
 * arrêt de l'enregistrement (indicateur ON/OFF), options, export, suppression (double clic de confirmation),
 * création, import, surbrillance de blocs, ouverture du dossier. Sauvegarde à la fermeture.
 * La pagination 2.x est remplacée par le défilement de la liste.
 */
public class WaypointsScreen extends UiScreen {
	private static final int ROW_H = 22;
	private static final int ROW_STEP = 25;

	private String status;
	private WaypointPattern pendingDelete;

	public WaypointsScreen(Screen parent) {
		super(Texts.c("autopaterns.waypoints.list.title"), parent);
	}

	private static WaypointsModule module() {
		return AutoPaterns.get().waypoints();
	}

	private static Waypoints st() {
		return module().state();
	}

	@Override
	protected int preferredWidth() {
		return 480;
	}

	@Override
	protected int preferredHeight() {
		return 300;
	}

	@Override
	protected int footerHeight() {
		return 42;
	}

	@Override
	protected String subtitle() {
		if (status != null) return status;
		Waypoints s = st();
		WaypointPattern a = s.active();
		if (a == null) return Texts.tr("autopaterns.waypoints.list.idle", Keys.label(KeyDef.WP_RECORD));
		return Texts.tr("autopaterns.waypoints.list.recording", a.name, a.triggersSummary());
	}

	/** Exécute une action puis reconstruit l'écran ; une erreur est journalisée et affichée sans fermer l'écran. */
	private void act(Runnable r) {
		try {
			r.run();
		} catch (RuntimeException e) {
			Log.error("waypoints : action du menu", e);
			status = Texts.tr("autopaterns.waypoints.error", e.getClass().getSimpleName());
		}
		if (minecraft != null && minecraft.gui.screen() == this) refresh();
	}

	@Override
	protected void build() {
		Waypoints s = st();
		s.ensureLoaded();
		int x = left();
		int cw = contentWidth();
		int y = top();
		if (s.patterns.isEmpty()) {
			content(new UiText(x, y + 12, cw, 24, Texts.tr("autopaterns.waypoints.list.empty")).color(Theme.TEXT_MUTED));
		}
		int wShow = 54, wRun = 76, wOpt = 52, wExp = 56, wDel = 48, gap = 3;
		int buttons = wShow + wRun + wOpt + wExp + wDel + 4 * gap;
		int nameW = Math.max(60, cw - buttons - 6);
		for (WaypointPattern p : s.patterns) {
			final WaypointPattern pat = p;
			content(new WpRow(x, y, nameW, ROW_H, pat::color, () -> pat.name,
				() -> s.isActive(pat) ? 0xFFFF5555 : Theme.TEXT,
				() -> Texts.tr("autopaterns.waypoints.list.row", pat.points.size(), pat.triggersSummary()),
				() -> minecraft.gui.setScreen(new WaypointOptionsScreen(this, pat))));
			int bx = x + cw - buttons;
			int by = y + 1;
			int bh = ROW_H - 2;
			content(new UiButton(bx, by, wShow, bh,
				() -> Texts.tr(pat.visible ? "autopaterns.waypoints.list.shown" : "autopaterns.waypoints.list.hidden"),
				() -> act(() -> {
					pat.visible = !pat.visible;
					s.markDirty();
				})).style(pat.visible ? UiButton.Style.NORMAL : UiButton.Style.GHOST)
				.tooltip(Texts.tr("autopaterns.waypoints.list.shown_tip")));
			bx += wShow + gap;
			content(new UiButton(bx, by, wRun, bh,
				() -> Texts.tr(s.isActive(pat) ? "autopaterns.waypoints.list.stop" : "autopaterns.waypoints.list.start"),
				() -> act(() -> {
					if (s.isActive(pat)) {
						s.stop();
					} else if (!pat.anyTrigger()) {
						minecraft.gui.setScreen(new WaypointOptionsScreen(this, pat));
					} else {
						s.start(pat);
					}
				})).indicator(() -> s.isActive(pat))
				.style(s.isActive(pat) ? UiButton.Style.DANGER : UiButton.Style.PRIMARY)
				.tooltip(Texts.tr("autopaterns.waypoints.list.start_tip", Keys.label(KeyDef.WP_RECORD))));
			bx += wRun + gap;
			content(new UiButton(bx, by, wOpt, bh, Texts.tr("autopaterns.waypoints.list.options"),
				() -> minecraft.gui.setScreen(new WaypointOptionsScreen(this, pat))));
			bx += wOpt + gap;
			content(new UiButton(bx, by, wExp, bh, Texts.tr("autopaterns.waypoints.list.export"),
				() -> act(() -> status = s.export(minecraft, pat)))
				.tooltip(Texts.tr("autopaterns.waypoints.list.export_tip")));
			bx += wExp + gap;
			boolean confirm = pendingDelete == pat;
			content(new UiButton(bx, by, wDel, bh,
				Texts.tr(confirm ? "autopaterns.waypoints.list.confirm" : "autopaterns.waypoints.list.delete"),
				() -> act(() -> {
					if (pendingDelete == pat) {
						s.delete(pat);
						pendingDelete = null;
					} else {
						pendingDelete = pat;
					}
				})).style(confirm ? UiButton.Style.DANGER : UiButton.Style.NORMAL));
			y += ROW_STEP;
		}

		// pied fixe
		int fy = footerY();
		int fw = panelW - 24;
		int b4 = (fw - 12) / 4;
		footer(new UiButton(x, fy, b4, 18, Texts.tr("autopaterns.waypoints.list.new"), () -> act(() -> {
			WaypointPattern p = s.create();
			minecraft.gui.setScreen(new WaypointOptionsScreen(this, p));
		})).style(UiButton.Style.PRIMARY));
		footer(new UiButton(x + (b4 + 4), fy, b4, 18, Texts.tr("autopaterns.waypoints.list.import"),
			() -> minecraft.gui.setScreen(new WaypointImportScreen(this))));
		footer(new UiButton(x + 2 * (b4 + 4), fy, b4, 18, Texts.tr("autopaterns.waypoints.list.finder"),
			() -> minecraft.gui.setScreen(new BlockFinderScreen(this))));
		footer(new UiButton(x + 3 * (b4 + 4), fy, fw - 3 * (b4 + 4), 18, Texts.tr("autopaterns.waypoints.open_folder"),
			() -> act(() -> {
				if (!openFolder(WaypointIO.exportDir(), true)) status = Texts.tr("autopaterns.waypoints.open_folder_failed");
			})));
		int b3 = (fw - 8) / 3;
		int fy2 = fy + 22;
		footer(new UiButton(x, fy2, b3, 18, Texts.tr("autopaterns.waypoints.list.show_all"), () -> act(() -> {
			for (WaypointPattern p : s.patterns) p.visible = true;
			s.markDirty();
		})));
		footer(new UiButton(x + b3 + 4, fy2, b3, 18, Texts.tr("autopaterns.waypoints.list.hide_all"), () -> act(() -> {
			for (WaypointPattern p : s.patterns) p.visible = false;
			s.markDirty();
		})));
		footer(new UiButton(x + 2 * (b3 + 4), fy2, fw - 2 * (b3 + 4), 18, Texts.tr("autopaterns.waypoints.close"),
			this::onClose).style(UiButton.Style.GHOST));
	}

	/**
	 * Ouvre le dossier (créé si besoin) dans l'explorateur. {@code parentFolder} : ouvre le dossier parent
	 * ({@code autopaterns}) comme le bouton 2.x du menu principal.
	 */
	static boolean openFolder(Path dir, boolean parentFolder) {
		try {
			Files.createDirectories(dir);
			Path target = parentFolder && dir.getParent() != null ? dir.getParent() : dir;
			Util.getPlatform().openPath(target);
			return true;
		} catch (Exception e) {
			Log.error("ouverture du dossier " + dir + " impossible", e);
			return false;
		}
	}

	@Override
	public void removed() {
		super.removed();
		st().saveNow();
	}

	@Override
	public void onClose() {
		status = null;
		super.onClose();
	}
}
