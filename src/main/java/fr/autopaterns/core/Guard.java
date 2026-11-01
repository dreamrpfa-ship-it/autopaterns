package fr.autopaterns.core;

import fr.autopaterns.input.InputSim;
import net.minecraft.client.Minecraft;

/**
 * Gestion centralisée des erreurs. Chaque événement Fabric du mod passe par {@link #run}.
 * Sur une exception inattendue : journal complet (autopaterns/journal/autopaterns.log), touches relâchées,
 * automate à l'arrêt, mode performance et réglages d'arrière-plan restaurés, puis le mod se met en pause
 * (plus aucun tick exécuté) jusqu'à sa réactivation depuis le menu (O) ou un redémarrage.
 */
public final class Guard {
	private static volatile boolean disabled;
	private static String lastError;
	private static boolean failing;

	private Guard() {}

	public static boolean disabled() {
		return disabled;
	}

	/** Dernière erreur fatale (affichée dans le menu principal), ou null. */
	public static String lastError() {
		return lastError;
	}

	/** Réactive le mod après une erreur (bouton du menu principal). */
	public static void reenable() {
		disabled = false;
		lastError = null;
		Notifier.hud(Texts.tr("autopaterns.guard.reenabled"));
	}

	/** Exécute {@code r} si le mod est actif ; toute exception désactive le mod proprement. */
	public static void run(String context, Runnable r) {
		if (disabled) return;
		try {
			r.run();
		} catch (VirtualMachineError e) {
			throw e;
		} catch (Throwable t) {
			fail(context, t);
		}
	}

	/** Exécute {@code r} même si le mod est désactivé (fermeture du jeu, restaurations). */
	public static void runAlways(String context, Runnable r) {
		try {
			r.run();
		} catch (VirtualMachineError e) {
			throw e;
		} catch (Throwable t) {
			Log.error("erreur (" + context + ")", t);
		}
	}

	/** Désactive le mod après une erreur inattendue et remet tout dans un état sûr. */
	public static void fail(String context, Throwable t) {
		if (failing) {
			Log.error("erreur pendant la mise en sécurité (" + context + ")", t);
			return;
		}
		failing = true;
		try {
			disabled = true;
			lastError = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : " : " + t.getMessage());
			Log.error("erreur dans " + context + ", mod mis en pause", t);
			Minecraft mc = Minecraft.getInstance();
			runAlways("relâcher les touches", () -> InputSim.releaseAll(mc));
			AutoPaterns ap = AutoPaterns.getOrNull();
			if (ap != null) {
				runAlways("arrêt de l'automate", () -> ap.automation().forceIdle(mc));
				for (ClientModule m : ap.modules()) runAlways("arrêt de " + m.name(), () -> m.onHalt(mc));
				runAlways("restauration de l'arrière-plan", () -> ap.automation().restoreBackground(mc));
			}
			runAlways("notification", () -> Notifier.hud(Texts.tr("autopaterns.guard.error", t.getClass().getSimpleName(),
				fr.autopaterns.input.Keys.label(fr.autopaterns.input.KeyDef.MENU))));
		} finally {
			failing = false;
		}
	}
}
