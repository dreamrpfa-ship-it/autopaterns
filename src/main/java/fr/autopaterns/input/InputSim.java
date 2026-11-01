package fr.autopaterns.input;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import fr.autopaterns.core.Log;
import fr.autopaterns.mixin.KeyMappingAccessor;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

/**
 * Simulation des entrées du joueur (touches maintenues et clics brefs), à utiliser par tous les modules.
 * Les touches pressées par le mod sont suivies pour garantir qu'aucune ne reste bloquée après un arrêt.
 * Thread client uniquement.
 */
public final class InputSim {
	private static final Set<KeyMapping> HELD = Collections.newSetFromMap(new IdentityHashMap<>());
	private static boolean clickWarned;

	private InputSim() {}

	/**
	 * Force l'état d'une touche. Gère les touches en mode « bascule » (accroupi / sprint en toggle),
	 * pour lesquelles setDown(true) inverse l'état : même algorithme qu'en 2.x.
	 */
	public static void setKey(KeyMapping k, boolean down) {
		if (k == null) return;
		if (k.isDown() != down) {
			k.setDown(down);
			if (k.isDown() != down) k.setDown(!down);
		}
		if (down) HELD.add(k);
		else HELD.remove(k);
	}

	/** Nombre de clics brefs en attente sur la touche. */
	public static int clickCount(KeyMapping k) {
		try {
			return ((KeyMappingAccessor) k).autopaterns$getClickCount();
		} catch (RuntimeException | LinkageError e) {
			return 0;
		}
	}

	/** Ajoute {@code n} clics brefs (consommés par le jeu au tick suivant). */
	public static void addClicks(KeyMapping k, int n) {
		if (k == null || n <= 0) return;
		try {
			KeyMappingAccessor a = (KeyMappingAccessor) k;
			a.autopaterns$setClickCount(a.autopaterns$getClickCount() + n);
		} catch (RuntimeException | LinkageError e) {
			if (!clickWarned) {
				clickWarned = true;
				Log.error("clics brefs impossibles à simuler", e);
			}
		}
	}

	/** Relâche les touches de jeu (déplacements, saut, accroupi, sprint, attaque, utiliser) et toute touche pressée par le mod. */
	public static void releaseAll(Minecraft mc) {
		Options o = mc == null ? null : mc.options;
		if (o != null) {
			KeyMapping[] keys = {o.keyUp, o.keyDown, o.keyLeft, o.keyRight, o.keyJump, o.keyShift, o.keySprint, o.keyAttack, o.keyUse};
			for (KeyMapping k : keys) {
				try {
					setKey(k, false);
				} catch (RuntimeException e) {
					Log.error("touche non relâchée : " + k.getName(), e);
				}
			}
		}
		for (KeyMapping k : HELD.toArray(new KeyMapping[0])) {
			try {
				setKey(k, false);
			} catch (RuntimeException ignored) {
				HELD.remove(k);
			}
		}
		HELD.clear();
	}
}
