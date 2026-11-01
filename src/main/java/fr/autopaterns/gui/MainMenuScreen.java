package fr.autopaterns.gui;

import fr.autopaterns.core.AutoPaterns;
import fr.autopaterns.core.AutoPaternsClient;
import fr.autopaterns.core.Guard;
import fr.autopaterns.core.Phase;
import fr.autopaterns.core.Texts;
import fr.autopaterns.fishing.FishingScreen;
import fr.autopaterns.gui.widget.UiButton;
import fr.autopaterns.hud.HudEditScreen;
import fr.autopaterns.input.KeyDef;
import fr.autopaterns.input.Keys;
import fr.autopaterns.patterns.PatternsScreen;
import fr.autopaterns.performance.PerformanceScreen;
import fr.autopaterns.waypoints.WaypointsScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

/**
 * Menu principal (touche O par défaut ; la même touche le referme) : exactement cinq boutons principaux,
 * chacun avec son état (ON/OFF ou résumé), plus un pied discret (HUD, arrêt, réactivation après erreur).
 */
public final class MainMenuScreen extends UiScreen {
	public MainMenuScreen(Screen parent) {
		super(Texts.c("autopaterns.menu.title"), parent);
	}

	@Override
	protected int preferredWidth() { return 330; }

	@Override
	protected int preferredHeight() { return 268; }

	@Override
	protected int footerHeight() { return 22; }

	@Override
	protected String subtitle() {
		if (Guard.disabled()) return "§c" + Texts.tr("autopaterns.menu.paused", Guard.lastError());
		return AutoPaterns.get().automation().statusLine();
	}

	@Override
	protected void build() {
		AutoPaterns ap = AutoPaterns.get();
		int x = left(), w = contentWidth(), h = 34, gap = 5;
		int y = top();

		content(new UiButton(x, y, w, h, () -> Texts.tr("autopaterns.menu.fishing"), () -> minecraft.gui.setScreen(new FishingScreen(this)))
			.icon("≋").indicator(() -> ap.automation().isFishing()).subtitle(this::fishingSub));
		y += h + gap;
		content(new UiButton(x, y, w, h, () -> Texts.tr("autopaterns.menu.waypoints"), () -> minecraft.gui.setScreen(new WaypointsScreen(this)))
			.icon("◈").indicator(() -> ap.waypoints().isRecording()).subtitle(this::waypointsSub));
		y += h + gap;
		content(new UiButton(x, y, w, h, () -> Texts.tr("autopaterns.menu.patterns"), () -> minecraft.gui.setScreen(new PatternsScreen(this)))
			.icon("▶").indicator(this::patternsOn).subtitle(this::patternsSub));
		y += h + gap;
		content(new UiButton(x, y, w, h, () -> Texts.tr("autopaterns.menu.keys"), () -> minecraft.gui.setScreen(new KeysScreen(this)))
			.icon("⌨").subtitle(this::keysSub));
		y += h + gap;
		content(new UiButton(x, y, w, h, () -> Texts.tr("autopaterns.menu.performance"), () -> minecraft.gui.setScreen(new PerformanceScreen(this)))
			.icon("⚡").indicator(() -> ap.performance().isEnabled()).subtitle(this::perfSub));

		int fy = footerY();
		footer(new UiButton(left(), fy, 70, 18, () -> Texts.tr("autopaterns.menu.hud"), () -> minecraft.gui.setScreen(new HudEditScreen(this)))
			.style(UiButton.Style.GHOST).icon("▣").tooltip(Texts.tr("autopaterns.menu.hud.tip")));
		if (Guard.disabled()) {
			footer(new UiButton(right() - 96, fy, 96, 18, () -> Texts.tr("autopaterns.menu.reenable"), () -> {
				Guard.reenable();
				refresh();
			}).style(UiButton.Style.PRIMARY));
		} else {
			footer(new UiButton(right() - 96, fy, 96, 18,
				() -> Texts.tr("autopaterns.menu.stop", Keys.label(KeyDef.STOP)),
				() -> ap.automation().stopAll(minecraft, Texts.tr("autopaterns.menu.stopped")))
				.style(UiButton.Style.DANGER).enabledWhen(() -> ap.automation().isBusy()));
		}
	}

	private boolean patternsOn() {
		Phase p = AutoPaterns.get().automation().phase();
		return p == Phase.PREPARING || p == Phase.RECORDING || p.isPlayback() || p == Phase.CAPTURE
			|| (p == Phase.SELLING && !AutoPaterns.get().automation().isFishing());
	}

	private String fishingSub() {
		AutoPaterns ap = AutoPaterns.get();
		if (ap.automation().isFishing()) {
			String d = ap.fishing().detail();
			return d != null ? Texts.strip(d) : Texts.tr("autopaterns.menu.fishing.on");
		}
		return Texts.tr("autopaterns.menu.fishing.off", ap.fishing().caught(), ap.fishing().missed(), Keys.label(KeyDef.FISHING));
	}

	private String waypointsSub() {
		AutoPaterns ap = AutoPaterns.get();
		if (ap.waypoints().isRecording()) return Texts.tr("autopaterns.menu.waypoints.on", Keys.label(KeyDef.WP_RECORD));
		return Texts.tr("autopaterns.menu.waypoints.off", ap.waypoints().highlightCount(), Keys.label(KeyDef.WP_MENU));
	}

	private String patternsSub() {
		AutoPaterns ap = AutoPaterns.get();
		if (patternsOn()) return ap.automation().statusLine();
		if (!ap.patterns().hasPattern()) return Texts.tr("autopaterns.menu.patterns.none", Keys.label(KeyDef.RECORD));
		String name = ap.patterns().currentName();
		return Texts.tr("autopaterns.menu.patterns.loaded",
			name == null ? Texts.tr("autopaterns.menu.patterns.unnamed") : "« " + name + " »",
			Texts.duration(ap.patterns().patternSize()), Keys.label(KeyDef.PLAY), Keys.label(KeyDef.LOOP));
	}

	private String keysSub() {
		int conflicts = 0;
		for (KeyDef d : KeyDef.values()) {
			KeyMapping km = Keys.mapping(d);
			if (km != null && !Keys.conflicts(km).isEmpty()) conflicts++;
		}
		String base = Texts.tr("autopaterns.menu.keys.sub", Keys.label(KeyDef.MENU), Keys.label(KeyDef.STOP));
		return conflicts == 0 ? base : base + " · §c" + Texts.tr("autopaterns.menu.keys.conflicts", conflicts);
	}

	private String perfSub() {
		AutoPaterns ap = AutoPaterns.get();
		String s = ap.performance().summary();
		if (ap.performance().isActive()) s = Texts.tr("autopaterns.menu.performance.active") + " · " + s;
		return s;
	}

	@Override
	protected void drawOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		String v = "v" + AutoPaternsClient.VERSION;
		Theme.text(g, font, v, panelX + panelW - 28 - font.width(v), panelY + 10, Theme.TEXT_MUTED);
	}
}
