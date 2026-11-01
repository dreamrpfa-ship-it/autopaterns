package fr.autopaterns.hud;

import java.util.Properties;

import fr.autopaterns.core.ClientModule;
import fr.autopaterns.core.Guard;
import fr.autopaterns.core.Log;
import fr.autopaterns.core.Notifier;
import fr.autopaterns.gui.UiScreen;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * HUD modulaire du mod (élément HUD Fabric {@code autopaterns:hud}).
 * <ul>
 *   <li>Rendu protégé : une erreur de rendu est journalisée UNE fois, le HUD se coupe et les notifications repassent
 *       par la barre d'action (corrige le spam d'erreurs à chaque image de la 2.x, bug n°1). L'éditeur peut le relancer.</li>
 *   <li>Masqué avec F1 ({@code Hud#isHidden}), sans joueur, et quand un écran du mod est ouvert (l'éditeur
 *       {@link HudEditScreen} dessine lui-même l'aperçu).</li>
 *   <li>Mod en pause après une erreur : seul le module de notifications reste affiché (pour que le message
 *       d'erreur soit vu).</li>
 *   <li>HUD indisponible : ligne d'état dans la barre d'action toutes les 10 ticks (2.x {@code showStatus}).</li>
 * </ul>
 */
public final class HudManager implements ClientModule {
	public static final Identifier ID = Identifier.fromNamespaceAndPath("autopaterns", "hud");
	private static final int STATUS_PERIOD = 10;

	private final HudLayout layout = new HudLayout();
	private final HudRenderer renderer = new HudRenderer(layout);
	private final HudStates states = new HudStates();
	private boolean registered;
	private boolean broken;
	private int statusTicks;

	public HudManager() {
		// aucun accès au jeu ici
	}

	@Override
	public String name() {
		return "HUD";
	}

	@Override
	public void onInit() {
		try {
			HudElementRegistry.addLast(ID, this::extract);
			registered = true;
			Notifier.setHudOk(true);
		} catch (RuntimeException | LinkageError e) {
			registered = false;
			Notifier.setHudOk(false);
			Log.error("HUD indisponible, messages dans la barre d'action", e);
		}
	}

	@Override
	public void onLoad(Minecraft mc, Properties config) {
		layout.load();
	}

	@Override
	public void onEndTick(Minecraft mc) {
		layout.flushIfDue();
		if (Notifier.hudOk()) {
			statusTicks = 0;
			return;
		}
		if (++statusTicks < STATUS_PERIOD) return;
		statusTicks = 0;
		if (mc.player == null) return;
		String s = states.fallbackStatus(mc);
		if (s != null) Notifier.overlay(s);
	}

	@Override
	public void onStopping(Minecraft mc) {
		layout.flush();
	}

	// ------------------------------------------------------------------ accès (paquet + éditeur)

	public HudLayout layout() {
		return layout;
	}

	HudRenderer renderer() {
		return renderer;
	}

	/** État réel. */
	HudState state(Minecraft mc) {
		return states.build(mc);
	}

	/** État de l'aperçu de l'éditeur (réel pendant une action, sinon exemple). */
	HudState previewState(Minecraft mc) {
		return states.preview(mc);
	}

	/** Le HUD est-il coupé après une erreur de rendu ? */
	public boolean isBroken() {
		return broken;
	}

	/** Relance le HUD après une erreur de rendu (bouton de l'éditeur). */
	public void retry() {
		broken = false;
		Notifier.setHudOk(registered);
	}

	/** Coupe le HUD après une erreur (journal une seule fois, repli sur la barre d'action). */
	void fail(String where, Throwable t) {
		if (broken) return;
		broken = true;
		Notifier.setHudOk(false);
		Log.error("HUD désactivé après une erreur (" + where + "), messages dans la barre d'action", t);
	}

	// ------------------------------------------------------------------ rendu en jeu

	private void extract(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		if (broken) return;
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc == null || mc.player == null || mc.options == null) return;
			if (mc.gui.hud.isHidden()) return;
			if (mc.gui.screen() instanceof UiScreen) return;
			renderer.render(g, mc, states.build(mc), g.guiWidth(), g.guiHeight(), false, null, null, Guard.disabled());
		} catch (VirtualMachineError e) {
			throw e;
		} catch (Throwable t) {
			fail("rendu", t);
		}
	}
}
