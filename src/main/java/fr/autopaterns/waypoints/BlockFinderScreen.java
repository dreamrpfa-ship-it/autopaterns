package fr.autopaterns.waypoints;

import java.util.ArrayList;
import java.util.List;

import fr.autopaterns.core.AutoPaterns;
import fr.autopaterns.core.Log;
import fr.autopaterns.core.Texts;
import fr.autopaterns.gui.Theme;
import fr.autopaterns.gui.UiScreen;
import fr.autopaterns.gui.widget.UiButton;
import fr.autopaterns.gui.widget.UiCycle;
import fr.autopaterns.gui.widget.UiText;
import fr.autopaterns.gui.widget.UiToggle;
import fr.autopaterns.input.KeyDef;
import fr.autopaterns.input.Keys;
import net.minecraft.client.gui.screens.Screen;

/**
 * Surbrillance de blocs (2.x : {@code BlockFinderScreen}) : ajout du bloc visé, activation, rayon, couleur,
 * regroupement des variantes deepslate, « À travers les murs », création d'un pattern de waypoints à partir des
 * blocs trouvés, liste des blocs sélectionnés (retirer / tout retirer). Sauvegarde à la fermeture.
 */
public class BlockFinderScreen extends UiScreen {
	private String status;

	public BlockFinderScreen(Screen parent) {
		super(Texts.c("autopaterns.waypoints.finder.title"), parent);
	}

	private static WaypointsModule module() {
		return AutoPaterns.get().waypoints();
	}

	@Override
	protected int preferredWidth() {
		return 360;
	}

	@Override
	protected int preferredHeight() {
		return 290;
	}

	@Override
	protected int footerHeight() {
		return 20;
	}

	@Override
	protected String subtitle() {
		if (status != null) return status;
		return Texts.tr("autopaterns.waypoints.finder.help", Keys.label(KeyDef.FINDER_SELECT), module().finder().resultCount());
	}

	/** Action, puis modification à sauvegarder et reconstruction (2.x). */
	private void act(Runnable r) {
		try {
			r.run();
			module().state().markDirty();
		} catch (RuntimeException e) {
			Log.error("waypoints : action de la surbrillance", e);
			status = Texts.tr("autopaterns.waypoints.error", e.getClass().getSimpleName());
		}
		if (minecraft != null && minecraft.gui.screen() == this) refresh();
	}

	private static List<Integer> list(int[] values) {
		List<Integer> l = new ArrayList<>(values.length);
		for (int v : values) l.add(v);
		return l;
	}

	@Override
	protected void build() {
		WaypointsModule m = module();
		BlockFinder f = m.finder();
		Waypoints s = m.state();
		s.ensureLoaded();
		int x = left();
		int cw = contentWidth();
		int colW = (cw - 4) / 2;
		int x2 = x + colW + 4;
		int y = top();

		content(new UiButton(x, y, colW, 18, Texts.tr("autopaterns.waypoints.finder.add_aimed"),
			() -> act(() -> status = f.addAimed(minecraft, s))).style(UiButton.Style.PRIMARY)
			.tooltip(Texts.tr("autopaterns.waypoints.finder.add_aimed_tip", Keys.label(KeyDef.FINDER_SELECT))));
		content(new UiToggle(x2, y, colW, 18, Texts.tr("autopaterns.waypoints.finder.enabled"), () -> f.enabled,
			v -> act(() -> {
				f.enabled = v;
				f.restart();
			})));
		y += 22;
		content(new UiCycle<>(x, y, colW, 18, Texts.tr("autopaterns.waypoints.finder.radius"),
			list(BlockFinder.RADII), () -> f.radius, v -> act(() -> {
				f.radius = v;
				f.restart();
			}), v -> Texts.tr("autopaterns.waypoints.blocks", v)));
		content(new UiCycle<>(x2, y, colW, 18, Texts.tr("autopaterns.waypoints.options.color"),
			colorIndexes(), () -> Math.floorMod(f.colorIndex, WaypointPattern.COLORS.length),
			v -> act(() -> f.colorIndex = v), v -> WaypointPattern.colorName(v)));
		y += 22;
		content(new UiToggle(x, y, colW, 18, Texts.tr("autopaterns.waypoints.finder.variants"), () -> f.variants,
			v -> act(() -> f.setVariants(v))).tooltip(Texts.tr("autopaterns.waypoints.finder.variants_tip")));
		content(new UiToggle(x2, y, colW, 18, Texts.tr("autopaterns.waypoints.options.walls"), () -> f.throughWalls,
			v -> act(() -> f.throughWalls = v)));
		y += 22;
		content(new UiButton(x, y, cw, 18, Texts.tr("autopaterns.waypoints.finder.to_waypoints"),
			() -> act(() -> status = s.toWaypoints(minecraft))));
		y += 28;

		content(new UiText(x, y, cw, 10, Texts.tr("autopaterns.waypoints.finder.selected")).section());
		y += 14;
		if (f.targets.isEmpty()) {
			content(new UiText(x, y + 4, cw, 10, Texts.tr("autopaterns.waypoints.finder.none")).color(Theme.TEXT_MUTED));
		}
		for (String key : new ArrayList<>(f.targets)) {
			final String k = key;
			content(new WpRow(x, y, cw - 66, 20, () -> f.color(), () -> BlockFinder.display(k) + " §8" + k, null, null, null));
			content(new UiButton(x + cw - 60, y + 1, 60, 18, Texts.tr("autopaterns.waypoints.finder.remove"), () -> act(() -> {
				f.targets.remove(k);
				f.restart();
			})));
			y += 22;
		}

		int fy = footerY();
		footer(new UiButton(x, fy, cw, 18, Texts.tr("autopaterns.waypoints.finder.remove_all"), () -> act(() -> {
			f.targets.clear();
			f.restart();
		})).style(UiButton.Style.DANGER).enabledWhen(() -> !f.targets.isEmpty()));
	}

	private static List<Integer> colorIndexes() {
		List<Integer> l = new ArrayList<>(WaypointPattern.COLORS.length);
		for (int i = 0; i < WaypointPattern.COLORS.length; i++) l.add(i);
		return l;
	}

	@Override
	public void removed() {
		super.removed();
		module().state().saveNow();
	}
}
