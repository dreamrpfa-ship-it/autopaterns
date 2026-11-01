package fr.autopaterns.gui;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFW;

import fr.autopaterns.core.Notifier;
import fr.autopaterns.gui.widget.UiButton;
import fr.autopaterns.input.KeyDef;
import fr.autopaterns.input.Keys;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * Base de tous les écrans du mod :
 * <ul>
 *   <li>panneau centré sombre avec bandeau de titre, bouton « ‹ Retour » (si parent) et « ✕ » (fermer) ;</li>
 *   <li>zone de contenu défilante à la molette (widgets ajoutés par {@link #content}) et pied fixe ({@link #footer}) ;</li>
 *   <li>animation d'ouverture, notifications discrètes en bas à droite ;</li>
 *   <li>Échap = retour au parent, touche du menu (O par défaut) = tout fermer (sauf saisie de texte en cours).</li>
 * </ul>
 * Les sous-classes implémentent {@link #build()} : elles y créent leurs widgets à partir de {@link #left()},
 * {@link #top()} (déjà décalé du défilement) et {@link #contentWidth()}. {@code build()} est rappelée à chaque
 * défilement / redimensionnement : les valeurs saisies doivent être gardées dans des champs de l'écran.
 */
public abstract class UiScreen extends Screen {
	protected final Screen parent;
	private final long openedAt = System.nanoTime();
	protected int panelX, panelY, panelW, panelH;
	private int scroll;
	private int maxScroll;
	private int contentTop, contentBottom;
	private final List<AbstractWidget> contentWidgets = new ArrayList<>();
	private String subtitleCache;

	protected UiScreen(Component title, Screen parent) {
		super(title);
		this.parent = parent;
	}

	/** Largeur souhaitée du panneau (réduite si la fenêtre est trop petite). */
	protected int preferredWidth() { return 380; }

	/** Hauteur souhaitée du panneau. */
	protected int preferredHeight() { return 260; }

	/** Hauteur réservée en bas pour les boutons fixes (0 = aucune). */
	protected int footerHeight() { return 0; }

	/** Sous-titre dynamique sous le titre (état, aide…), ou null. */
	protected String subtitle() { return null; }

	/** Construit les widgets. */
	protected abstract void build();

	// ----------------------------------------------------------------- géométrie

	protected int left() { return panelX + 12; }
	protected int right() { return panelX + panelW - 12; }
	protected int contentWidth() { return panelW - 24; }
	/** Haut du contenu, décalé du défilement. */
	protected int top() { return contentTop - scroll; }
	protected int contentBottom() { return contentBottom; }
	/** Haut de la zone de pied (boutons fixes). */
	protected int footerY() { return panelY + panelH - footerHeight() - 6; }

	@Override
	protected final void init() {
		panelW = Math.min(preferredWidth(), width - 16);
		panelH = Math.min(preferredHeight(), height - 16);
		panelX = (width - panelW) / 2;
		panelY = (height - panelH) / 2;
		contentTop = panelY + (subtitle() != null ? 40 : 32);
		contentBottom = panelY + panelH - (footerHeight() > 0 ? footerHeight() + 10 : 8);
		contentWidgets.clear();

		if (parent != null) {
			addRenderableWidget(new UiButton(panelX + 6, panelY + 6, 56, 16, "‹ Retour", this::onClose).style(UiButton.Style.GHOST));
		}
		addRenderableWidget(new UiButton(panelX + panelW - 22, panelY + 6, 16, 16, "✕", this::closeAll).style(UiButton.Style.GHOST)
			.tooltip("Fermer (" + Keys.label(KeyDef.MENU) + ")"));

		build();

		int bottom = contentTop;
		for (AbstractWidget w : contentWidgets) bottom = Math.max(bottom, w.getY() + w.getHeight() + scroll);
		maxScroll = Math.max(0, bottom - contentBottom + 4);
		if (scroll > maxScroll) {
			scroll = maxScroll;
			rebuildWidgets();
			return;
		}
		for (AbstractWidget w : contentWidgets) {
			w.visible = w.getY() >= contentTop - 1 && w.getY() + w.getHeight() <= contentBottom + 1;
		}
	}

	/** Ajoute un widget dans la zone de contenu défilante. */
	protected <T extends AbstractWidget> T content(T w) {
		contentWidgets.add(w);
		return addRenderableWidget(w);
	}

	/** Ajoute un widget fixe (pied de panneau). */
	protected <T extends AbstractWidget> T footer(T w) {
		return addRenderableWidget(w);
	}

	/** Reconstruit l'écran (après un changement d'état qui modifie les widgets). */
	public void refresh() {
		rebuildWidgets();
	}

	/** Ferme tous les écrans du mod. */
	public void closeAll() {
		minecraft.gui.setScreen(null);
	}

	/** Notification discrète (HUD + coin de l'écran). */
	protected void notify(String msg) {
		Notifier.hud(msg);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		boolean typing = getFocused() instanceof EditBox eb && eb.isFocused();
		if (!typing && Keys.matches(KeyDef.MENU, event)) {
			closeAll();
			return true;
		}
		if (event.key() == GLFW.GLFW_KEY_ESCAPE && typing) {
			setFocused(null);
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (super.mouseScrolled(mx, my, sx, sy)) return true;
		if (maxScroll > 0 && mx >= panelX && mx <= panelX + panelW && my >= contentTop && my <= contentBottom) {
			int ns = Math.max(0, Math.min(maxScroll, scroll - (int) Math.round(sy * 18)));
			if (ns != scroll) {
				scroll = ns;
				rebuildWidgets();
			}
			return true;
		}
		return false;
	}

	// ----------------------------------------------------------------- rendu

	private float openProgress() {
		return Theme.ease((System.nanoTime() - openedAt) / 180_000_000f);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		g.fill(0, 0, width, height, Theme.alpha(Theme.SCRIM, 0.4f + 0.6f * openProgress()));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		float t = openProgress();
		boolean slide = t < 0.999f;
		if (slide) {
			g.pose().pushMatrix();
			g.pose().translate(0f, (1f - t) * 8f);
		}
		drawPanel(g, t);
		drawContent(g, mouseX, mouseY, a);
		super.extractRenderState(g, mouseX, mouseY, a);
		drawOverlay(g, mouseX, mouseY, a);
		if (slide) g.pose().popMatrix();
		drawNotifications(g);
	}

	private void drawPanel(GuiGraphicsExtractor g, float t) {
		int x = panelX, y = panelY, w = panelW, h = panelH;
		Theme.tile(g, x, y, w, h, Theme.alpha(Theme.PANEL, t), Theme.alpha(Theme.BORDER, t));
		g.fill(x + 1, y + 1, x + w - 1, y + 27, Theme.alpha(Theme.PANEL_HEADER, t));
		// liseré lumineux en haut : dégradé cyan → bleu
		g.fill(x + 1, y, x + w - 1, y + 1, Theme.alpha(Theme.mix(Theme.CYAN, Theme.BLUE, 0.5f), 0.9f * t));
		g.fill(x + 1, y + 27, x + w - 1, y + 28, Theme.alpha(Theme.DIVIDER, t));
		String title = getTitle().getString();
		int tx = parent != null ? x + 70 : x + 12;
		Theme.textShadow(g, font, title, tx, y + 10, Theme.alpha(Theme.TEXT, t));
		String sub = subtitle();
		subtitleCache = sub;
		if (sub != null) {
			Theme.text(g, font, Theme.clip(font, sub, w - 24), x + 12, y + 31, Theme.alpha(Theme.TEXT_DIM, t));
		}
		if (footerHeight() > 0) {
			int fy = footerY() - 4;
			g.fill(x + 1, fy, x + w - 1, fy + 1, Theme.alpha(Theme.DIVIDER, t));
		}
		if (maxScroll > 0) {
			int track = contentBottom - contentTop;
			int thumb = Math.max(14, track * track / (track + maxScroll));
			int ty = contentTop + Math.round((track - thumb) * (scroll / (float) maxScroll));
			g.fill(x + w - 5, contentTop, x + w - 3, contentBottom, 0x22FFFFFF);
			g.fill(x + w - 5, ty, x + w - 3, ty + thumb, Theme.alpha(Theme.CYAN, 0.7f));
		}
	}

	/** Dessin spécifique avant les widgets (fonds, textes fixes). */
	protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {}

	/** Dessin spécifique après les widgets (aperçus, surimpressions). */
	protected void drawOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {}

	private void drawNotifications(GuiGraphicsExtractor g) {
		long now = System.nanoTime();
		List<Notifier.Entry> list = Notifier.recentSince(openedAt - 1);
		int y = height - 8;
		for (int i = list.size() - 1; i >= 0 && y > 20; i--) {
			Notifier.Entry e = list.get(i);
			float age = (now - e.at()) / 1_000_000_000f;
			if (age > 5f) continue;
			float alpha = age < 0.15f ? age / 0.15f : (age > 4.4f ? (5f - age) / 0.6f : 1f);
			String s = Theme.clip(font, fr.autopaterns.core.Texts.strip(e.text()), Math.min(260, width / 2));
			int w = font.width(s) + 14;
			int x = width - w - 8;
			y -= 16;
			Theme.tile(g, x, y, w, 14, Theme.alpha(0xEE0B111C, alpha), Theme.alpha(Theme.BORDER, alpha));
			g.fill(x + 1, y + 2, x + 3, y + 12, Theme.alpha(e.accent(), alpha));
			Theme.text(g, font, s, x + 8, y + 3, Theme.alpha(Theme.TEXT, alpha));
			y -= 2;
		}
	}
}
