package fr.autopaterns.gui.widget;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import fr.autopaterns.gui.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Sélecteur à valeurs successives : libellé à gauche, valeur à droite (clic = valeur suivante, Maj+clic = précédente). */
public class UiCycle<T> extends UiWidget {
	private final String label;
	private final List<T> values;
	private final Supplier<T> get;
	private final Consumer<T> set;
	private final Function<T, String> format;

	public UiCycle(int x, int y, int w, int h, String label, List<T> values, Supplier<T> get, Consumer<T> set, Function<T, String> format) {
		super(x, y, w, h, Component.literal(label));
		this.label = label;
		this.values = values;
		this.get = get;
		this.set = set;
		this.format = format;
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		step(event.hasShiftDown() ? -1 : 1);
	}

	@Override
	protected void activate() {
		step(1);
	}

	private void step(int dir) {
		if (!active || values.isEmpty()) return;
		int i = values.indexOf(get.get());
		int n = values.size();
		int next = i < 0 ? 0 : ((i + dir) % n + n) % n;
		set.accept(values.get(next));
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		animate();
		int x = getX(), y = getY(), w = getWidth(), h = getHeight();
		Theme.tile(g, x, y, w, h, Theme.mix(Theme.SURFACE, Theme.SURFACE_HOVER, hover), Theme.mix(Theme.BORDER, Theme.BORDER_HOVER, hover));
		String v = "‹ " + format.apply(get.get()) + " ›";
		int vw = font().width(v);
		Theme.text(g, font(), v, x + w - 7 - vw, y + (h - 8) / 2, active ? Theme.CYAN : Theme.TEXT_MUTED);
		Theme.textShadow(g, font(), Theme.clip(font(), label, w - vw - 22), x + 8, y + (h - 8) / 2, active ? Theme.TEXT : Theme.TEXT_MUTED);
	}
}
