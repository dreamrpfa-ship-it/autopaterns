package fr.autopaterns.core;

import fr.autopaterns.input.Keys;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;

/**
 * Point d'entrée client d'AutoPaterns 3.0. Crée les systèmes, enregistre les raccourcis et branche
 * les événements Fabric à travers {@link Guard} (aucune exception ne remonte au jeu).
 */
public final class AutoPaternsClient implements ClientModInitializer {
	public static final String VERSION = "3.0.0";

	@Override
	public void onInitializeClient() {
		try {
			Keys.register();
			AutoPaterns ap = AutoPaterns.create();
			ClientTickEvents.START_CLIENT_TICK.register(mc -> Guard.run("début de tick", () -> ap.automation().onStartTick(mc)));
			ClientTickEvents.END_CLIENT_TICK.register(mc -> Guard.run("fin de tick", () -> ap.automation().onEndTick(mc)));
			ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
				for (ClientModule m : ap.modules()) Guard.runAlways("fermeture de " + m.name(), () -> m.onStopping(mc));
			});
			try {
				LevelRenderEvents.END_MAIN.register(ctx -> Guard.run("rendu", () -> ap.automation().onFrame(Minecraft.getInstance(), false)));
			} catch (RuntimeException | LinkageError e) {
				Log.error("événement de rendu indisponible, caméra lissée par le jeu seulement", e);
			}
			for (ClientModule m : ap.modules()) {
				try {
					m.onInit();
				} catch (RuntimeException | LinkageError e) {
					Log.error("initialisation de " + m.name() + " incomplète", e);
				}
			}
			Log.info("AutoPaterns " + VERSION + " chargé (menu : " + Keys.label(fr.autopaterns.input.KeyDef.MENU) + ").");
		} catch (VirtualMachineError e) {
			throw e;
		} catch (Throwable t) {
			Log.error("initialisation impossible, mod inactif", t);
		}
	}

	/** Appelé par {@link fr.autopaterns.mixin.GameRendererMixin} avant la mise à jour de la caméra de chaque image. */
	public static void onPreFrame() {
		AutoPaterns ap = AutoPaterns.getOrNull();
		if (ap == null) return;
		Guard.run("image", () -> ap.automation().onFrame(Minecraft.getInstance(), true));
	}
}
