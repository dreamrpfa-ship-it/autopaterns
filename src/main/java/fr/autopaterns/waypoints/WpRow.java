package fr.autopaterns.waypoints;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

import fr.autopaterns.gui.Theme;
import fr.autopaterns.gui.widget.UiWidget;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Ligne d'information des écrans waypoints : pastille de couleur, titre, sous-titre gris.
 * Cliquable seulement si une action est fournie (ouvre alors les options, par exemple).
 */
final class WpRow extends UiWidget {
	private final IntSupplier swatch;
	private final Supplier<String> title;
	private final IntSupplier titleColor;
	private final Supplier<String> subtitle;
	private final Runnable onClick;

	WpRow(int x, int y, int w, int h, IntSupplier swatch, Supplier<String> title, IntSupplier titleColor,
		  Supplier<String> subtitle, Runnable onClick) {
		super(x, y, w, h, Component.literal(title.get()));
		this.swatch = swatch;
		this.title = title;
		this.titleColor = titleColor;
		this.subtitle = subtitle;
		this.onClick = onClick;
		this.active = onClick != null;
	}

	@Override
	public boolean isMouseOver(double mx, double my) {
		return onClick != null && super.isMouseOver(mx, my);
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		activate();
	}

	@Override
	protected void activate() {
		if (onClick != null) onClick.run();
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		animate();
		int x = getX(), y = getY(), w = getWidth(), h = getHeight();
		if (onClick != null && hover > 0.01f) {
			Theme.tile(g, x, y, w, h, Theme.alpha(Theme.SURFACE_HOVER, hover), Theme.alpha(Theme.BORDER, hover));
		}
		int sy = y + (h - 10) / 2;
		if (swatch != null) {
			g.fill(x + 3, sy, x + 13, sy + 10, 0xFF000000);
			g.fill(x + 4, sy + 1, x + 12, sy + 9, swatch.getAsInt() | 0xFF000000);
		}
		int tx = x + (swatch != null ? 18 : 4);
		int maxW = x + w - 4 - tx;
		String sub = subtitle == null ? null : subtitle.get();
		String t = Theme.clip(font(), title.get(), maxW);
		int tc = titleColor == null ? Theme.TEXT : titleColor.getAsInt();
		if (sub == null || h < 20) {
			Theme.textShadow(g, font(), t, tx, y + (h - 8) / 2, tc);
		} else {
			int ty = y + h / 2 - 9;
			Theme.textShadow(g, font(), t, tx, ty, tc);
			Theme.text(g, font(), Theme.clip(font(), sub, maxW), tx, ty + 10, Theme.TEXT_DIM);
		}
	}
}
