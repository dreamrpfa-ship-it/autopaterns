package fr.autopaterns.waypoints;

import java.util.HashMap;
import java.util.Map;

import fr.autopaterns.core.Guard;
import fr.autopaterns.core.Log;

/**
 * Isolation des erreurs du module waypoints. Le module ne fait qu'observer et afficher : une erreur dans un rendu ou
 * dans un crochet d'interaction ne doit ni faire planter le jeu, ni couper définitivement l'affichage (bug 2.x :
 * {@code worldBroken} / {@code extractionBroken} jusqu'au redémarrage), ni mettre en pause tout le mod.
 * L'erreur est journalisée (trace complète la première fois, puis au plus une fois toutes les 30 s par contexte)
 * et l'opération est simplement sautée.
 */
final class WpSafe {
	private static final long LOG_INTERVAL_NANOS = 30_000_000_000L;
	private static final Map<String, long[]> LAST = new HashMap<>();

	private WpSafe() {}

	/** Exécute {@code r} sauf si le mod est en pause après une erreur ({@link Guard#disabled()}). */
	static void run(String what, Runnable r) {
		if (Guard.disabled()) return;
		try {
			r.run();
		} catch (RuntimeException | LinkageError e) {
			logLimited(what, e);
		}
	}

	/** Journalise une erreur avec limitation de fréquence (compteur des occurrences masquées). */
	static synchronized void logLimited(String what, Throwable t) {
		long now = System.nanoTime();
		long[] s = LAST.get(what);
		if (s == null) {
			LAST.put(what, new long[] {now, 0});
			Log.error("waypoints : erreur (" + what + ")", t);
			return;
		}
		s[1]++;
		if (now - s[0] >= LOG_INTERVAL_NANOS) {
			Log.warn("waypoints : erreur (" + what + ") répétée " + s[1] + " fois : " + t);
			s[0] = now;
			s[1] = 0;
		}
	}
}
