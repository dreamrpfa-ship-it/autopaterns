package fr.autopaterns.gui.widget;

import java.util.function.Supplier;

import fr.autopaterns.gui.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Texte non interactif (une ou plusieurs lignes, retour à la ligne automatique sur la largeur).
 * Variante « section » : titre en cyan avec trait de séparation.
 */
public class UiText extends UiWidget {
	private final Supplier<String> text;
	private int color = Theme.TEXT_DIM;
	private boolean section;

	public UiText(int x, int y, int w, int h, Supplier<String> text) {
		super(x, y, w, h, Component.empty());
		this.text = text;
		this.active = false;
	}

	public UiText(int x, int y, int w, int h, String text) {
		this(x, y, w, h, () -> text);
	}

	public UiText color(int c) { this.color = c; return this; }
	public UiText section() { this.section = true; return this; }

	@Override
	public boolean isMouseOver(double mx, double my) {
		return false;
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		String s = text.get();
		if (s == null) return;
		int x = getX(), y = getY(), w = getWidth();
		if (section) {
			Theme.text(g, font(), s.toUpperCase(java.util.Locale.ROOT), x, y + 1, Theme.CYAN);
			int lx = x + font().width(s.toUpperCase(java.util.Locale.ROOT)) + 6;
			g.fill(lx, y + 5, x + w, y + 6, Theme.DIVIDER);
			return;
		}
		int ly = y;
		for (String para : s.split("\n")) {
			for (var line : font().split(Component.literal(para), Math.max(10, w))) {
				if (ly + 9 > y + getHeight() + 1) return;
				g.text(font(), line, x, ly, color, false);
				ly += 10;
			}
		}
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput out) {}
}
