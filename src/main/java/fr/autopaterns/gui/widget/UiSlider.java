package fr.autopaterns.gui.widget;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;

import org.lwjgl.glfw.GLFW;

import fr.autopaterns.gui.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Curseur entier [min, max] par pas de {@code step} ; glisser, cliquer ou flèches gauche/droite. */
public class UiSlider extends UiWidget {
	private final String label;
	private final int min, max, step;
	private final IntSupplier get;
	private final IntConsumer set;
	private final IntFunction<String> format;

	public UiSlider(int x, int y, int w, int h, String label, int min, int max, int step,
					IntSupplier get, IntConsumer set, IntFunction<String> format) {
		super(x, y, w, h, Component.literal(label));
		this.label = label;
		this.min = min;
		this.max = Math.max(min, max);
		this.step = Math.max(1, step);
		this.get = get;
		this.set = set;
		this.format = format;
	}

	private int trackX() { return getX() + 8; }
	private int trackW() { return getWidth() - 16; }

	private void setFromMouse(double mx) {
		double t = (mx - trackX()) / Math.max(1, trackW());
		t = Math.max(0, Math.min(1, t));
		int v = (int) Math.round((min + t * (max - min)) / (double) step) * step;
		v = Math.max(min, Math.min(max, v));
		if (v != get.getAsInt()) set.accept(v);
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		if (active) setFromMouse(event.x());
	}

	@Override
	protected void onDrag(MouseButtonEvent event, double dx, double dy) {
		if (active) setFromMouse(event.x());
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (active && isFocused()) {
			int k = event.key();
			if (k == GLFW.GLFW_KEY_LEFT || k == GLFW.GLFW_KEY_RIGHT) {
				int v = get.getAsInt() + (k == GLFW.GLFW_KEY_LEFT ? -step : step);
				set.accept(Math.max(min, Math.min(max, v)));
				return true;
			}
		}
		return super.keyPressed(event);
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		animate();
		int x = getX(), y = getY(), w = getWidth(), h = getHeight();
		Theme.tile(g, x, y, w, h, Theme.mix(Theme.SURFACE, Theme.SURFACE_HOVER, hover), Theme.mix(Theme.BORDER, Theme.BORDER_HOVER, hover));
		int v = get.getAsInt();
		String val = format.apply(v);
		int ty = y + 4;
		Theme.textShadow(g, font(), Theme.clip(font(), label, w - font().width(val) - 24), x + 8, ty, active ? Theme.TEXT : Theme.TEXT_MUTED);
		Theme.text(g, font(), val, x + w - 8 - font().width(val), ty, active ? Theme.CYAN : Theme.TEXT_MUTED);
		int by = y + h - 7;
		float t = max == min ? 0f : (v - min) / (float) (max - min);
		g.fill(trackX(), by, trackX() + trackW(), by + 2, 0xFF26324A);
		int fx = trackX() + Math.round(trackW() * t);
		g.fill(trackX(), by, fx, by + 2, Theme.mix(Theme.BLUE, Theme.CYAN, hover));
		g.fill(fx - 2, by - 2, fx + 2, by + 4, active ? 0xFFF2F7FF : 0xFF7A879C);
	}
}
