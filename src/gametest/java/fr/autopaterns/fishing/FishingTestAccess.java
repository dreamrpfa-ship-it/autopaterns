package fr.autopaterns.fishing;

/**
 * Accès en lecture, pour les tests en jeu uniquement, à l'état interne du robot de pêche ({@link FishingBot} est
 * package-private). Cette classe vit dans le source set {@code gametest} mais dans le paquet du module : elle est
 * chargée par le même chargeur de classes (Knot) que le mod, l'accès package-private fonctionne donc à l'exécution.
 * Aucune écriture, aucun effet de bord.
 */
public final class FishingTestAccess {
	private FishingTestAccess() {}

	/** Captures d'écran (ou copies GPU) analysées depuis le lancement. */
	public static int captures(FishingService s) {
		return s.bot().captures();
	}

	/** Erreurs de capture depuis le lancement. */
	public static int errors(FishingService s) {
		return s.bot().errorCount();
	}

	/** Captures sans réponse (délai dépassé) depuis le lancement. */
	public static int timeouts(FishingService s) {
		return s.bot().timeouts();
	}

	/** Erreurs d'analyse d'image depuis le lancement. */
	public static int analysisErrors(FishingService s) {
		return s.bot().analysisErrors();
	}

	/** État de la machine à états (CAST, CASTING, WAIT, GAME, AFTER, REEL). */
	public static String state(FishingService s) {
		return s.bot().state().name();
	}

	/** Méthode de capture courante (libellé). */
	public static String method(FishingService s) {
		return s.bot().captureMethod();
	}

	/** État du calibrage de la copie GPU (libellé). */
	public static String calibration(FishingService s) {
		return s.bot().calibrationState();
	}

	/** Dernière erreur de capture (texte court, vide si aucune). */
	public static String lastError(FishingService s) {
		return s.bot().lastErrorShort();
	}

	/** Raison d'échec (vide si aucune). */
	public static String failure(FishingService s) {
		return s.bot().failure();
	}
}
