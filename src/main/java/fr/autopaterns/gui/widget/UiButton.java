package fr.autopaterns.gui.widget;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import fr.autopaterns.gui.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Bouton du thème. Options : style, icône (un caractère), sous-titre (bouton haut ≥ 30 px, « carte »),
 * indicateur ON/OFF (pastille + texte), libellé dynamique.
 */
public class UiButton extends UiWidget {
	public enum Style { NORMAL, PRIMARY, DANGER, GHOST }

	private final Supplier<String> label;
	private final Runnable onPress;
	private Style style = Style.NORMAL;
	private String icon;
	private Supplier<String> subtitle;
	private BooleanSupplier indicator;
	private BooleanSupplier enabledWhen;

	public UiButton(int x, int y, int w, int h, String label, Runnable onPress) {
		this(x, y, w, h, () -> label, onPress);
	}

	public UiButton(int x, int y, int w, int h, Supplier<String> label, Runnable onPress) {
		super(x, y, w, h, Component.literal(label.get()));
		this.label = label;
		this.onPress = onPress;
	}

	public UiButton style(Style s) { this.style = s; return this; }
	public UiButton icon(String icon) { this.icon = icon; return this; }
	public UiButton subtitle(Supplier<String> s) { this.subtitle = s; return this; }
	/** Affiche un indicateur ON (vert) / OFF (gris) à droite. */
	public UiButton indicator(BooleanSupplier on) { this.indicator = on; return this; }
	/** Bouton actif seulement si la condition est vraie (réévaluée à chaque image). */
	public UiButton enabledWhen(BooleanSupplier c) { this.enabledWhen = c; return this; }

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		activate();
	}

	@Override
	protected void activate() {
		if (active && onPress != null) onPress.run();
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		if (enabledWhen != null) active = enabledWhen.getAsBoolean();
		animate();
		String text = label.get();
		setMessage(Component.literal(text));
		int x = getX(), y = getY(), w = getWidth(), h = getHeight();

		int accent = switch (style) {
			case PRIMARY -> Theme.CYAN;
			case DANGER -> Theme.BAD;
			default -> Theme.BLUE;
		};
		int bg;
		int border;
		if (!active) {
			bg = Theme.SURFACE_DISABLED;
			border = Theme.alpha(Theme.BORDER, 0.5f);
		} else if (style == Style.GHOST) {
			bg = Theme.alpha(Theme.SURFACE_HOVER, hover);
			border = Theme.alpha(Theme.BORDER_HOVER, hover);
		} else {
			bg = Theme.mix(Theme.SURFACE, Theme.SURFACE_HOVER, hover);
			border = Theme.mix(style == Style.NORMAL ? Theme.BORDER : Theme.alpha(accent, 0.55f), Theme.alpha(accent, 0.95f), hover);
		}
		Theme.tile(g, x, y, w, h, bg, border);
		// liseré d'accent à gauche, qui s'allonge au survol
		if (active && style != Style.GHOST) {
			int barH = Math.max(2, Math.round((h - 6) * (0.35f + 0.65f * hover)));
			int by = y + (h - barH) / 2;
			g.fill(x + 1, by, x + 3, by + barH, Theme.alpha(accent, 0.6f + 0.4f * hover));
		}

		int textColor = active ? Theme.TEXT : Theme.TEXT_MUTED;
		int left = x + 8;
		int right = x + w - 6;
		if (indicator != null) {
			boolean on = indicator.getAsBoolean();
			String st = on ? "ON" : "OFF";
			int sw = font().width(st);
			Theme.text(g, font(), st, right - sw, y + (h - 8) / 2, on ? Theme.ON : Theme.TEXT_MUTED);
			Theme.dot(g, right - sw - 8, y + (h - 4) / 2, on ? Theme.ON : Theme.OFF);
			right -= sw + 12;
		}
		boolean card = subtitle != null && h >= 30;
		if (icon != null) {
			int iy = card ? y + (h - 8) / 2 : y + (h - 8) / 2;
			Theme.text(g, font(), icon, left, iy, active ? Theme.mix(accent, Theme.TEXT, 0.25f) : Theme.TEXT_MUTED);
			left += font().width(icon) + 6;
		}
		if (card) {
			String sub = subtitle.get();
			int ty = y + h / 2 - 9;
			Theme.textShadow(g, font(), Theme.clip(font(), text, right - left), left, ty, textColor);
			Theme.text(g, font(), Theme.clip(font(), sub == null ? "" : sub, right - left), left, ty + 11, Theme.TEXT_DIM);
		} else if (indicator == null && icon == null && style != Style.GHOST) {
			String t = Theme.clip(font(), text, w - 12);
			Theme.textShadow(g, font(), t, x + (w - font().width(t)) / 2 + 1, y + (h - 8) / 2, textColor);
		} else {
			Theme.textShadow(g, font(), Theme.clip(font(), text, right - left), left, y + (h - 8) / 2, textColor);
		}
	}
}
