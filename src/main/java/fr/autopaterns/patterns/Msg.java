package fr.autopaterns.patterns;

import fr.autopaterns.core.Texts;

/** Textes traduits du module (clés {@code autopaterns.patterns.*} des fichiers lang/parts/patterns.*.json). */
final class Msg {
	private Msg() {}

	static String t(String key, Object... args) {
		return Texts.tr("autopaterns.patterns." + key, args);
	}
}
