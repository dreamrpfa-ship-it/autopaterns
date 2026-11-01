package fr.autopaterns.performance;

import fr.autopaterns.gui.UiScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Provisoire (compilation intermédiaire) : remplacé par l'écran définitif. */
public final class PerformanceScreen extends UiScreen {
	public PerformanceScreen(Screen parent) {
		super(Component.literal("Performance"), parent);
	}

	@Override
	protected void build() {}
}
