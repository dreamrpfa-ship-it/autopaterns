package fr.autopaterns.hud;

import org.lwjgl.glfw.GLFW;

import fr.autopaterns.core.AutoPaterns;
import fr.autopaterns.core.Log;
import fr.autopaterns.core.Texts;
import fr.autopaterns.gui.Theme;
import fr.autopaterns.gui.UiScreen;
import fr.autopaterns.gui.widget.UiButton;
import fr.autopaterns.gui.widget.UiSlider;
import fr.autopaterns.gui.widget.UiText;
import fr.autopaterns.gui.widget.UiToggle;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * Éditeur du HUD en temps réel (remplace HudEditScreen + HudSettingsScreen + HudModuleScreen de la 2.x).
 * <ul>
 *   <li>Aperçu plein écran : état réel si une action tourne, sinon état d'exemple (butin réel) ; modules désactivés
 *       visibles en transparence pour pouvoir les sélectionner (2.x : invisibles, bug n°3).</li>
 *   <li>Clic gauche : sélection + déplacement (aimantation 4 px aux bords, au centre et aux autres modules, guides) ;
 *       poignée du coin bas-droit : largeur ; molette : échelle ±5 % ; flèches : déplacement au pixel ;
 *       clic droit : réglages du module ; clic dans le vide : réglages généraux.</li>
 *   <li>Panneau latéral (côté opposé au module sélectionné, ou bouton ⇆) : réglages généraux (opacité globale, liste
 *       des modules, tout activer/désactiver, réinitialiser la disposition) ou réglages du module (activé, toujours
 *       visible, fond, échelle, opacité, largeur, informations affichées, position automatique, réinitialiser).</li>
 *   <li>Toute modification est sauvegardée automatiquement (hud.txt, au plus toutes les 0,5 s et à la fermeture).</li>
 * </ul>
 */
public final class HudEditScreen extends UiScreen {
	private static final int SNAP = 4;
	private static final int PANEL_W = 200;
	private static final long CONFIRM_NANOS = 4_000_000_000L;

	private final HudManager hud;
	private final HudLayout layout;
	private HudModule selected;
	private HudModule hover;
	private HudModule dragging;
	private boolean resizing;
	private int offX, offY;
	private int guideX = -1, guideY = -1;
	private boolean panelLeft;
	private boolean sideChosen;
	private long confirmResetUntil;
	private boolean previewFailed;

	public HudEditScreen(Screen parent) {
		super(Texts.c("autopaterns.hud.edit.title"), parent);
		this.hud = AutoPaterns.get().hud();
		this.layout = hud.layout();
	}

	@Override
	protected int preferredWidth() {
		return PANEL_W;
	}

	@Override
	protected int preferredHeight() {
		return 4000; // toute la hauteur disponible
	}

	@Override
	protected String subtitle() {
		if (selected == null) return Texts.tr("autopaterns.hud.edit.subtitle", layout.enabledCount(), HudModule.values().length);
		return Texts.tr("autopaterns.hud.edit.subtitle.module", selected.label());
	}

	// ------------------------------------------------------------------ panneau latéral

	@Override
	protected void build() {
		placePanel();
		footer(new UiButton(panelX + panelW - 40, panelY + 6, 16, 16, "⇆", this::swapSide)
			.style(UiButton.Style.GHOST).tooltip(Texts.tr("autopaterns.hud.edit.swap")));
		if (selected == null) buildGlobal(left(), top(), contentWidth());
		else buildModule(left(), top(), contentWidth(), selected);
	}

	/** Déplace le panneau (centré par UiScreen) sur le côté choisi, avec les boutons d'en-tête déjà créés. */
	private void placePanel() {
		int desired = Math.max(0, panelLeft ? 8 : width - panelW - 8);
		int dx = desired - panelX;
		if (dx == 0) return;
		for (GuiEventListener l : children()) {
			if (l instanceof AbstractWidget w) w.setX(w.getX() + dx);
		}
		panelX = desired;
	}

	private void buildGlobal(int x, int y, int w) {
		content(new UiText(x, y, w, 10, Texts.tr("autopaterns.hud.edit.section.display")).section());
		y += 14;
		content(new UiSlider(x, y, w, 22, Texts.tr("autopaterns.hud.edit.opacity.global"), HudConf.MIN_OPACITY, 100, 5,
			layout::opacity, layout::setOpacity, v -> v + " %"));
		y += 26;
		content(new UiText(x, y, w, 10, () -> hud.previewState(minecraft).active()
			? Texts.tr("autopaterns.hud.edit.preview.real") : Texts.tr("autopaterns.hud.edit.preview.sample")));
		y += 14;
		if (hud.isBroken()) {
			content(new UiButton(x, y, w, 18, Texts.tr("autopaterns.hud.edit.retry"), () -> {
				hud.retry();
				refresh();
			}).style(UiButton.Style.PRIMARY).tooltip(Texts.tr("autopaterns.hud.edit.retry.tip")));
			y += 22;
		}

		content(new UiText(x, y, w, 10, Texts.tr("autopaterns.hud.edit.section.modules")).section());
		y += 14;
		for (HudModule m : HudModule.values()) {
			content(new UiButton(x, y, w, 18, m::label, () -> select(m))
				.indicator(() -> layout.conf(m).enabled).tooltip(Texts.tr("autopaterns.hud.edit.module.tip")));
			y += 20;
		}
		y += 2;
		int half = (w - 4) / 2;
		content(new UiButton(x, y, half, 18, Texts.tr("autopaterns.hud.edit.all.on"), () -> layout.setAllEnabled(true)));
		content(new UiButton(x + half + 4, y, w - half - 4, 18, Texts.tr("autopaterns.hud.edit.all.off"), () -> layout.setAllEnabled(false)));
		y += 22;

		content(new UiText(x, y, w, 10, Texts.tr("autopaterns.hud.edit.section.layout")).section());
		y += 14;
		content(new UiButton(x, y, w, 18, () -> confirming()
			? Texts.tr("autopaterns.hud.edit.reset.all.confirm") : Texts.tr("autopaterns.hud.edit.reset.all"), () -> {
				if (!confirming()) {
					confirmResetUntil = System.nanoTime() + CONFIRM_NANOS;
					return;
				}
				confirmResetUntil = 0;
				layout.resetAll();
				notify(Texts.tr("autopaterns.hud.edit.reset.all.done"));
			}).style(UiButton.Style.DANGER));
		y += 24;
		content(new UiText(x, y, w, 60, Texts.tr("autopaterns.hud.edit.help")));
	}

	private void buildModule(int x, int y, int w, HudModule m) {
		HudConf c = layout.conf(m);
		content(new UiButton(x, y, w, 16, Texts.tr("autopaterns.hud.edit.back"), () -> select(null)).style(UiButton.Style.GHOST));
		y += 20;
		content(new UiToggle(x, y, w, 20, Texts.tr("autopaterns.hud.edit.enabled"), () -> c.enabled, v -> {
			c.enabled = v;
			layout.changed();
		}));
		y += 22;
		if (m != HudModule.NOTIF) {
			content(new UiToggle(x, y, w, 20, Texts.tr("autopaterns.hud.edit.always"), () -> c.always, v -> {
				c.always = v;
				layout.changed();
			}).tooltip(Texts.tr("autopaterns.hud.edit.always.tip")));
			y += 22;
		}
		content(new UiToggle(x, y, w, 20, Texts.tr("autopaterns.hud.edit.background"), () -> c.background, v -> {
			c.background = v;
			layout.changed();
		}));
		y += 24;
		content(new UiSlider(x, y, w, 22, Texts.tr("autopaterns.hud.edit.scale"), HudConf.MIN_SCALE, HudConf.MAX_SCALE, 5,
			() -> c.scale, v -> {
				c.scale = v;
				layout.changed();
			}, v -> v + " %"));
		y += 26;
		content(new UiSlider(x, y, w, 22, Texts.tr("autopaterns.hud.edit.opacity"), HudConf.MIN_OPACITY, 100, 5,
			() -> c.opacity, v -> {
				c.opacity = v;
				layout.changed();
			}, v -> v + " %"));
		y += 26;
		if (m.resizable) {
			int auto = m.minWidth - 5;
			content(new UiSlider(x, y, w, 22, Texts.tr("autopaterns.hud.edit.width"), auto, m.maxWidth, 5,
				() -> c.width <= 0 ? auto : c.width, v -> {
					c.width = v < m.minWidth ? 0 : m.clampWidth(v);
					layout.changed();
				}, v -> v < m.minWidth ? Texts.tr("autopaterns.hud.edit.width.auto") : v + " px")
				.tooltip(Texts.tr("autopaterns.hud.edit.width.tip")));
			y += 26;
		}

		content(new UiText(x, y, w, 10, Texts.tr("autopaterns.hud.edit.section.infos")).section());
		y += 14;
		if (m.infos.isEmpty()) {
			content(new UiText(x, y, w, 10, Texts.tr("autopaterns.hud.edit.infos.none")));
			y += 14;
		} else {
			for (HudModule.Info i : m.infos) {
				content(new UiToggle(x, y, w, 18, m.infoLabel(i.key()), () -> c.shows(i.key()), v -> {
					c.setShows(i.key(), v);
					layout.changed();
				}));
				y += 20;
			}
			y += 2;
		}

		content(new UiText(x, y, w, 10, Texts.tr("autopaterns.hud.edit.section.position")).section());
		y += 14;
		content(new UiText(x, y, w, 10, () -> c.auto()
			? Texts.tr("autopaterns.hud.edit.position.auto")
			: Texts.tr("autopaterns.hud.edit.position.fixed", Math.round(c.fx * 100), Math.round(c.fy * 100))));
		y += 14;
		content(new UiButton(x, y, w, 18, Texts.tr("autopaterns.hud.edit.position.reset"), () -> {
			c.fx = -1;
			c.fy = -1;
			layout.changed();
		}).enabledWhen(() -> !c.auto()));
		y += 22;
		content(new UiButton(x, y, w, 18, Texts.tr("autopaterns.hud.edit.reset.module"), () -> {
			layout.resetModule(m);
			notify(Texts.tr("autopaterns.hud.edit.reset.module.done", m.label()));
		}).style(UiButton.Style.DANGER));
	}

	private boolean confirming() {
		return confirmResetUntil != 0 && System.nanoTime() < confirmResetUntil;
	}

	private void select(HudModule m) {
		selected = m;
		confirmResetUntil = 0;
		updatePanelSide();
		refresh();
	}

	private void swapSide() {
		panelLeft = !panelLeft;
		sideChosen = true;
		refresh();
	}

	/** Panneau du côté opposé au module sélectionné, pour ne pas le masquer. */
	private void updatePanelSide() {
		if (selected == null) return;
		int[] b = hud.renderer().bounds(selected);
		if (b != null) panelLeft = b[0] + b[2] / 2 > width / 2;
	}

	/** À l'ouverture : côté qui masque le moins de modules (après le premier rendu de l'aperçu). */
	@Override
	public void tick() {
		super.tick();
		if (sideChosen) return;
		boolean any = false;
		for (HudModule m : HudModule.values()) any |= hud.renderer().bounds(m) != null;
		if (!any) return;
		sideChosen = true;
		boolean left = overlap(true) < overlap(false);
		if (left != panelLeft) {
			panelLeft = left;
			refresh();
		}
	}

	private long overlap(boolean left) {
		int px = Math.max(0, left ? 8 : width - panelW - 8);
		long area = 0;
		for (HudModule m : HudModule.values()) {
			int[] b = hud.renderer().bounds(m);
			if (b == null || !layout.conf(m).enabled) continue;
			int ix = Math.max(0, Math.min(b[0] + b[2], px + panelW) - Math.max(b[0], px));
			int iy = Math.max(0, Math.min(b[1] + b[3], panelY + panelH) - Math.max(b[1], panelY));
			area += (long) ix * iy;
		}
		return area;
	}

	private boolean insidePanel(double x, double y) {
		return x >= panelX && x < panelX + panelW && y >= panelY && y < panelY + panelH;
	}

	// ------------------------------------------------------------------ aperçu

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		g.fill(0, 0, width, height, minecraft.level == null ? 0xFF070B12 : 0x38000000);
		hover = dragging != null ? dragging : (insidePanel(mouseX, mouseY) ? null : hud.renderer().at(mouseX, mouseY));
		try {
			hud.renderer().render(g, minecraft, hud.previewState(minecraft), width, height, true, hover, selected, false);
			previewFailed = false;
		} catch (RuntimeException | LinkageError e) {
			if (!previewFailed) Log.error("aperçu du HUD impossible", e);
			previewFailed = true;
		}
		if (guideX >= 0) g.fill(guideX, 0, guideX + 1, height, Theme.alpha(Theme.CYAN, 0.6f));
		if (guideY >= 0) g.fill(0, guideY, width, guideY + 1, Theme.alpha(Theme.CYAN, 0.6f));

		// aide en bas de la zone d'aperçu
		String help = previewFailed ? Texts.tr("autopaterns.hud.edit.preview.error") : Texts.tr("autopaterns.hud.edit.hint");
		int areaX = panelLeft ? panelX + panelW : 0;
		int areaW = width - panelW - 8;
		help = Theme.clip(font, help, Math.max(40, areaW - 20));
		int tw = font.width(help) + 12;
		int hx = areaX + (areaW - tw) / 2;
		Theme.tile(g, hx, height - 18, tw, 14, 0xD00A0F1A, Theme.BORDER);
		Theme.text(g, font, help, hx + 6, height - 15, previewFailed ? Theme.BAD : Theme.TEXT_DIM);
	}

	@Override
	protected void drawOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		HudModule m = hover;
		if (m == null) return;
		int[] b = hud.renderer().bounds(m);
		if (b == null) return;
		HudConf c = layout.conf(m);
		String t = m.label() + " · " + c.scale + " %";
		if (resizing && m == dragging) t += " · " + (c.width <= 0 ? Texts.tr("autopaterns.hud.edit.width.auto") : c.width + " px");
		if (!c.enabled) t += " · " + Texts.tr("autopaterns.hud.edit.disabled");
		int tw = font.width(t) + 6;
		int tx = Math.max(1, Math.min(width - tw - 1, b[0] - 1));
		int ty = b[1] >= 12 ? b[1] - 11 : b[1] + b[3] + 2;
		Theme.tile(g, tx, ty - 1, tw, 11, 0xE00A0F1A, Theme.BORDER);
		Theme.text(g, font, t, tx + 3, ty + 1, Theme.TEXT);
	}

	// ------------------------------------------------------------------ souris / clavier

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		double mx = e.x(), my = e.y();
		if (insidePanel(mx, my)) return super.mouseClicked(e, doubleClick);
		HudRenderer r = hud.renderer();
		HudModule m;
		boolean handle = false;
		if (selected != null && r.onHandle(selected, mx, my) && layout.conf(selected).enabled) {
			m = selected;
			handle = true;
		} else {
			m = r.at(mx, my);
			handle = m != null && r.onHandle(m, mx, my) && layout.conf(m).enabled;
		}
		if (e.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
			select(m);
			return true;
		}
		if (e.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(e, doubleClick);
		setFocused(null);
		if (m == null) {
			if (selected != null) select(null);
			return true;
		}
		int[] b = r.bounds(m);
		HudConf c = layout.conf(m);
		dragging = m;
		resizing = handle;
		if (!handle && b != null) {
			offX = (int) Math.round(mx) - b[0];
			offY = (int) Math.round(my) - b[1];
			if (c.auto()) { // conversion du placement automatique en position fixe (2.x)
				c.fx = frac(b[0], width, b[2]);
				c.fy = frac(b[1], height, b[3]);
				layout.changed();
			}
		}
		if (selected != m) {
			selected = m;
			confirmResetUntil = 0;
			refresh();
		}
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		if (dragging == null) return super.mouseDragged(e, dx, dy);
		if (resizing) resize(e.x());
		else moveDragged((int) Math.round(e.x()), (int) Math.round(e.y()));
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		if (dragging == null) return super.mouseReleased(e);
		dragging = null;
		resizing = false;
		guideX = guideY = -1;
		layout.changed();
		boolean before = panelLeft;
		updatePanelSide();
		if (before != panelLeft) refresh();
		return true;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (!insidePanel(mx, my) && sy != 0) {
			HudModule m = hud.renderer().at(mx, my);
			if (m != null) {
				HudConf c = layout.conf(m);
				c.scale = Math.max(HudConf.MIN_SCALE, Math.min(HudConf.MAX_SCALE, c.scale + (sy > 0 ? 5 : -5)));
				layout.changed();
				return true;
			}
		}
		return super.mouseScrolled(mx, my, sx, sy);
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (selected != null && !(getFocused() instanceof UiSlider)) {
			int k = e.key();
			int dx = k == GLFW.GLFW_KEY_LEFT ? -1 : k == GLFW.GLFW_KEY_RIGHT ? 1 : 0;
			int dy = k == GLFW.GLFW_KEY_UP ? -1 : k == GLFW.GLFW_KEY_DOWN ? 1 : 0;
			if (dx != 0 || dy != 0) {
				nudge(dx, dy);
				return true;
			}
		}
		return super.keyPressed(e);
	}

	/** Redimensionnement à la poignée : largeur non mise à l'échelle, bornée au min/max du module. */
	private void resize(double mx) {
		int[] b = hud.renderer().bounds(dragging);
		if (b == null) return;
		HudConf c = layout.conf(dragging);
		float sc = c.scale / 100f;
		int nw = (int) Math.round((mx - b[0]) / sc);
		c.width = dragging.clampWidth(Math.max(dragging.minWidth, nw));
		layout.changed();
	}

	/** Déplacement au pixel (flèches). */
	private void nudge(int dx, int dy) {
		int[] b = hud.renderer().bounds(selected);
		if (b == null) return;
		HudConf c = layout.conf(selected);
		int nx = Math.max(3, Math.min(width - b[2] - 3, b[0] + dx));
		int ny = Math.max(3, Math.min(height - b[3] - 3, b[1] + dy));
		c.fx = frac(nx, width, b[2]);
		c.fy = frac(ny, height, b[3]);
		layout.changed();
	}

	/**
	 * Déplacement avec aimantation (algorithme 2.x) : bords et centre de l'écran, alignement et contact avec les
	 * autres modules activés ; seuil 4 px. Guides affichés pour chaque aimantation (2.x : centre seulement).
	 */
	private void moveDragged(int mx, int my) {
		int[] b = hud.renderer().bounds(dragging);
		if (b == null) return;
		int w = b[2], h = b[3], W = width, H = height;
		int nx = Math.max(3, Math.min(W - w - 3, mx - offX));
		int ny = Math.max(3, Math.min(H - h - 3, my - offY));
		int bestX = nx, dX = SNAP + 1, gX = -1;
		int bestY = ny, dY = SNAP + 1, gY = -1;
		int[][] sx = {{3, -1}, {W - w - 3, -1}, {(W - w) / 2, W / 2}};
		for (int[] cand : sx) {
			int d = Math.abs(cand[0] - nx);
			if (d < dX) { dX = d; bestX = cand[0]; gX = cand[1]; }
		}
		int[][] sy = {{3, -1}, {H - h - 3, -1}, {(H - h) / 2, H / 2}};
		for (int[] cand : sy) {
			int d = Math.abs(cand[0] - ny);
			if (d < dY) { dY = d; bestY = cand[0]; gY = cand[1]; }
		}
		for (HudModule o : HudModule.values()) {
			if (o == dragging || !layout.conf(o).enabled) continue;
			int[] ob = hud.renderer().bounds(o);
			if (ob == null) continue;
			int ox = ob[0], oy = ob[1], ow = ob[2], oh = ob[3];
			int[][] cx = {{ox, ox}, {ox + ow - w, ox + ow}, {ox + ow + 2, ox + ow + 1}, {ox - w - 2, ox - 1}};
			for (int[] cand : cx) {
				int d = Math.abs(cand[0] - nx);
				if (d < dX) { dX = d; bestX = cand[0]; gX = cand[1]; }
			}
			int[][] cy = {{oy, oy}, {oy + oh - h, oy + oh}, {oy + oh + 2, oy + oh + 1}, {oy - h - 2, oy - 1}};
			for (int[] cand : cy) {
				int d = Math.abs(cand[0] - ny);
				if (d < dY) { dY = d; bestY = cand[0]; gY = cand[1]; }
			}
		}
		if (dX <= SNAP) nx = bestX;
		else gX = -1;
		if (dY <= SNAP) ny = bestY;
		else gY = -1;
		guideX = gX;
		guideY = gY;
		HudConf c = layout.conf(dragging);
		c.fx = frac(nx, W, w);
		c.fy = frac(ny, H, h);
		layout.changed();
	}

	/** Position en pixels → fraction de l'espace libre (2.x). */
	static double frac(int pos, int size, int dim) {
		int avail = size - dim - 6;
		return avail > 0 ? Math.max(0.0, Math.min(1.0, (pos - 3) / (double) avail)) : 0.0;
	}

	// ------------------------------------------------------------------ fermeture

	@Override
	public void onClose() {
		layout.flush();
		super.onClose();
	}

	@Override
	public void removed() {
		layout.flush();
		super.removed();
	}
}
