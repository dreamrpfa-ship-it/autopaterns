package fr.autopaterns.gui.widget;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Base des widgets du thème : animation de survol, infobulle, activation clavier (Entrée / Espace). */
public abstract class UiWidget extends AbstractWidget {
	protected float hover;
	private long lastFrame = System.nanoTime();

	protected UiWidget(int x, int y, int w, int h, Component message) {
		super(x, y, w, h, message);
	}

	protected Font font() {
		return Minecraft.getInstance().font;
	}

	/** Met à jour l'animation de survol ; à appeler au début du rendu. */
	protected void animate() {
		long now = System.nanoTime();
		float target = (isHoveredOrFocused() && active) ? 1f : 0f;
		hover = fr.autopaterns.gui.Theme.approach(hover, target, lastFrame, now);
		lastFrame = now;
	}

	public UiWidget tooltip(String text) {
		if (text != null && !text.isEmpty()) setTooltip(Tooltip.create(Component.literal(text)));
		return this;
	}

	/** Action au clic ou à la touche Entrée / Espace (rien par défaut). */
	protected void activate() {}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (active && visible && isFocused()) {
			int k = event.key();
			if (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_SPACE || k == GLFW.GLFW_KEY_KP_ENTER) {
				activate();
				return true;
			}
		}
		return super.keyPressed(event);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput out) {
		defaultButtonNarrationText(out);
	}
}
