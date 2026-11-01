package fr.autopaterns.waypoints;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import fr.autopaterns.core.AutoPaterns;
import fr.autopaterns.core.Log;
import fr.autopaterns.core.Texts;
import fr.autopaterns.gui.Theme;
import fr.autopaterns.gui.UiScreen;
import fr.autopaterns.gui.widget.UiButton;
import fr.autopaterns.gui.widget.UiCycle;
import fr.autopaterns.gui.widget.UiText;
import fr.autopaterns.gui.widget.UiToggle;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

/**
 * Options d'un pattern de waypoints (2.x : {@code WaypointOptionsScreen}) : nom, actions capturées (miner, poser,
 * clic droit, clic maintenu + durée), couleur, affichage, libellé, distance max, doublons, « Identiques autour »,
 * « À travers les murs », lancement / arrêt, annuler le dernier point, tout effacer (confirmation).
 */
public class WaypointOptionsScreen extends UiScreen {
	private final WaypointPattern p;
	private String nameValue;
	private boolean confirmClear;
	private String message;

	public WaypointOptionsScreen(Screen parent, WaypointPattern p) {
		super(Texts.c("autopaterns.waypoints.options.title"), parent);
		this.p = p;
		this.nameValue = p.name;
	}

	private static Waypoints st() {
		return AutoPaterns.get().waypoints().state();
	}

	@Override
	protected int preferredWidth() {
		return 340;
	}

	@Override
	protected int preferredHeight() {
		return 300;
	}

	@Override
	protected int footerHeight() {
		return 20;
	}

	@Override
	protected String subtitle() {
		if (message != null) return message;
		return Texts.tr("autopaterns.waypoints.options.subtitle", p.points.size(), p.triggersSummary());
	}

	/**
	 * Enveloppe des actions (2.x) : {@code refresh} annule la confirmation « Effacer tout » ; la modification est
	 * marquée à sauvegarder puis l'écran est reconstruit.
	 */
	private void act(boolean refresh, Runnable r) {
		try {
			if (refresh) confirmClear = false;
			r.run();
			st().markDirty();
		} catch (RuntimeException e) {
			Log.error("waypoints : action des options", e);
			message = Texts.tr("autopaterns.waypoints.error", e.getClass().getSimpleName());
		}
		if (minecraft != null && minecraft.gui.screen() == this) refresh();
	}

	private static String onOff(boolean b) {
		return Texts.tr(b ? "autopaterns.waypoints.yes" : "autopaterns.waypoints.no");
	}

	private static List<Integer> list(int[] values) {
		List<Integer> l = new ArrayList<>(values.length);
		for (int v : values) l.add(v);
		return l;
	}

	private static List<Integer> range(int n) {
		List<Integer> l = new ArrayList<>(n);
		for (int i = 0; i < n; i++) l.add(i);
		return l;
	}

	@Override
	protected void build() {
		Waypoints s = st();
		int x = left();
		int cw = contentWidth();
		int colW = (cw - 4) / 2;
		int x2 = x + colW + 4;
		int y = top();

		// Nom (unique ; ignoré s'il est vide ou déjà pris)
		EditBox name = new EditBox(font, x, y, cw, 18, Texts.c("autopaterns.waypoints.options.name"));
		name.setMaxLength(48);
		name.setValue(nameValue);
		name.setResponder(v -> {
			nameValue = v;
			s.rename(p, v);
		});
		content(name);
		y += 26;

		content(new UiText(x, y, cw, 10, Texts.tr("autopaterns.waypoints.options.actions")).section());
		y += 14;
		content(new UiToggle(x, y, colW, 18, Texts.tr("autopaterns.waypoints.options.mine"), () -> p.mine,
			v -> act(true, () -> p.mine = v)));
		content(new UiToggle(x2, y, colW, 18, Texts.tr("autopaterns.waypoints.options.place"), () -> p.place,
			v -> act(true, () -> p.place = v)));
		y += 22;
		content(new UiToggle(x, y, colW, 18, Texts.tr("autopaterns.waypoints.options.click"), () -> p.click,
			v -> act(true, () -> p.click = v)));
		content(new UiToggle(x2, y, colW, 18, Texts.tr("autopaterns.waypoints.options.hold"), () -> p.hold,
			v -> act(true, () -> p.hold = v)));
		y += 22;
		// Durée du maintien : pas de 1 jusqu'à 20 ticks, puis de 5 (2 … 200)
		int w4 = 20;
		content(new UiButton(x, y, w4, 18, "−", () -> act(true, () -> {
			int h = p.holdTicks;
			p.holdTicks = Math.max(2, h - (h > 20 ? 5 : 1));
		})).tooltip(Texts.tr("autopaterns.waypoints.options.hold_less")));
		content(new UiButton(x + colW - w4, y, w4, 18, "+", () -> act(true, () -> {
			int h = p.holdTicks;
			p.holdTicks = Math.min(200, h + (h >= 20 ? 5 : 1));
		})).tooltip(Texts.tr("autopaterns.waypoints.options.hold_more")));
		content(new UiText(x + w4 + 6, y + 5, colW - 2 * w4 - 10, 10, () -> Texts.tr("autopaterns.waypoints.options.hold_value",
			p.holdTicks, String.format(Locale.ROOT, "%.2f", p.holdTicks / 20.0).replace('.', ','))).color(Theme.TEXT));
		content(new UiCycle<>(x2, y, colW, 18, Texts.tr("autopaterns.waypoints.options.color"),
			range(WaypointPattern.COLORS.length), () -> Math.floorMod(p.colorIndex, WaypointPattern.COLORS.length),
			v -> act(true, () -> p.colorIndex = v), v -> WaypointPattern.colorName(v)));
		y += 28;

		content(new UiText(x, y, cw, 10, Texts.tr("autopaterns.waypoints.options.display")).section());
		y += 14;
		content(new UiToggle(x, y, colW, 18, Texts.tr("autopaterns.waypoints.options.visible"), () -> p.visible,
			v -> act(true, () -> p.visible = v)));
		content(new UiCycle<>(x2, y, colW, 18, Texts.tr("autopaterns.waypoints.options.label"),
			range(4), () -> Math.floorMod(p.labelMode, 4), v -> act(true, () -> p.labelMode = v),
			v -> WaypointPattern.labelModeName(v)));
		y += 22;
		content(new UiCycle<>(x, y, colW, 18, Texts.tr("autopaterns.waypoints.options.distance"),
			list(WaypointPattern.DISTANCES), () -> p.maxDistance, v -> act(true, () -> p.maxDistance = v),
			v -> v <= 0 ? Texts.tr("autopaterns.waypoints.options.unlimited") : Texts.tr("autopaterns.waypoints.blocks", v)));
		content(new UiToggle(x2, y, colW, 18, Texts.tr("autopaterns.waypoints.options.dupes"), () -> p.dupes,
			v -> act(true, () -> p.dupes = v)).tooltip(Texts.tr("autopaterns.waypoints.options.dupes_tip")));
		y += 22;
		content(new UiCycle<>(x, y, colW, 18, Texts.tr("autopaterns.waypoints.options.similar"),
			list(WaypointPattern.SIMILAR_RADII), () -> p.similarRadius, v -> act(true, () -> p.similarRadius = v),
			v -> v <= 0 ? onOff(false) : Texts.tr("autopaterns.waypoints.blocks", v))
			.tooltip(Texts.tr("autopaterns.waypoints.options.similar_tip")));
		content(new UiToggle(x2, y, colW, 18, Texts.tr("autopaterns.waypoints.options.walls"), () -> p.throughWalls,
			v -> act(true, () -> p.throughWalls = v)));
		y += 28;

		content(new UiButton(x, y, cw, 20,
			() -> Texts.tr(s.isActive(p) ? "autopaterns.waypoints.options.stop" : "autopaterns.waypoints.options.start"),
			() -> act(true, () -> {
				if (s.isActive(p)) {
					s.stop();
				} else if (!p.anyTrigger()) {
					message = Texts.tr("autopaterns.waypoints.options.need_trigger");
				} else {
					s.start(p);
				}
			})).indicator(() -> s.isActive(p)).style(s.isActive(p) ? UiButton.Style.DANGER : UiButton.Style.PRIMARY));

		// pied fixe : annuler le dernier point, tout effacer (confirmation), terminé
		int fy = footerY();
		int b3 = (cw - 8) / 3;
		footer(new UiButton(x, fy, b3, 18, Texts.tr("autopaterns.waypoints.options.undo"), () -> act(true, () -> {
			Waypoint w = p.removeLast();
			message = w == null ? Texts.tr("autopaterns.waypoints.options.no_point")
				: Texts.tr("autopaterns.waypoints.options.removed", w.x() + " " + w.y() + " " + w.z());
		})));
		footer(new UiButton(x + b3 + 4, fy, b3, 18,
			Texts.tr(confirmClear ? "autopaterns.waypoints.options.clear_confirm" : "autopaterns.waypoints.options.clear"),
			() -> act(false, () -> {
				if (confirmClear) {
					p.clear();
					confirmClear = false;
					message = Texts.tr("autopaterns.waypoints.options.cleared");
				} else {
					confirmClear = true;
				}
			})).style(confirmClear ? UiButton.Style.DANGER : UiButton.Style.NORMAL));
		footer(new UiButton(x + 2 * (b3 + 4), fy, cw - 2 * (b3 + 4), 18, Texts.tr("autopaterns.waypoints.options.done"),
			this::onClose).style(UiButton.Style.PRIMARY));
	}

	@Override
	public void removed() {
		super.removed();
		st().saveNow();
	}
}
