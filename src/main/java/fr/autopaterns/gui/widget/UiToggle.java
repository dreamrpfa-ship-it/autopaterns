package fr.autopaterns.gui.widget;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import fr.autopaterns.gui.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Interrupteur ON/OFF animé avec libellé à gauche. */
public class UiToggle extends UiWidget {
	private final String label;
	private final BooleanSupplier get;
	private final Consumer<Boolean> set;
	private float knob;
	private long last = System.nanoTime();

	public UiToggle(int x, int y, int w, int h, String label, BooleanSupplier get, Consumer<Boolean> set) {
		super(x, y, w, h, Component.literal(label));
		this.label = label;
		this.get = get;
		this.set = set;
		this.knob = get.getAsBoolean() ? 1f : 0f;
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		activate();
	}

	@Override
	protected void activate() {
		if (active) set.accept(!get.getAsBoolean());
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		animate();
		boolean on = get.getAsBoolean();
		long now = System.nanoTime();
		knob = Theme.approach(knob, on ? 1f : 0f, last, now);
		last = now;
		int x = getX(), y = getY(), w = getWidth(), h = getHeight();
		Theme.tile(g, x, y, w, h, Theme.mix(Theme.SURFACE, Theme.SURFACE_HOVER, hover), Theme.mix(Theme.BORDER, Theme.BORDER_HOVER, hover));
		int sw = 22, sh = 10;
		int sx = x + w - sw - 7, sy = y + (h - sh) / 2;
		int track = Theme.mix(0xFF26324A, Theme.alpha(Theme.CYAN, 0.85f), knob);
		Theme.tile(g, sx, sy, sw, sh, track, 0);
		int kx = sx + 1 + Math.round((sw - 10) * knob);
		Theme.tile(g, kx, sy + 1, 8, sh - 2, active ? 0xFFF2F7FF : 0xFF7A879C, 0);
		String st = on ? "ON" : "OFF";
		Theme.text(g, font(), st, sx - 4 - font().width(st), y + (h - 8) / 2, on ? Theme.ON : Theme.TEXT_MUTED);
		int maxW = w - sw - 20 - font().width("OFF") - 8;
		Theme.textShadow(g, font(), Theme.clip(font(), label, maxW), x + 8, y + (h - 8) / 2, active ? Theme.TEXT : Theme.TEXT_MUTED);
	}
}
