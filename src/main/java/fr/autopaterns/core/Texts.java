package fr.autopaterns.core;

import java.util.Locale;

import net.minecraft.network.chat.Component;

/** Utilitaires de texte communs à tous les modules. */
public final class Texts {
	private Texts() {}

	/** Retire les codes de mise en forme « §x ». */
	public static String strip(String s) {
		return s == null ? "" : s.replaceAll("§.", "");
	}

	/** Format décimal français à 3 décimales (ex. {@code 0,123}), comme en 2.x. */
	public static String fmt(double d) {
		return String.format(Locale.FRANCE, "%.3f", d);
	}

	/** Durée {@code m:ss} à partir d'un nombre de ticks (20 ticks = 1 s). */
	public static String duration(int ticks) {
		int s = Math.max(0, ticks) / 20;
		return String.format(Locale.FRANCE, "%d:%02d", s / 60, s % 60);
	}

	/**
	 * Texte traduit (fichiers {@code assets/autopaterns/lang/*.json}).
	 * Les arguments sont insérés aux emplacements {@code %s} / {@code %1$s}.
	 * Si la clé n'existe pas, la clé elle-même est renvoyée (jamais d'exception).
	 */
	public static String tr(String key, Object... args) {
		try {
			return Component.translatable(key, args).getString();
		} catch (RuntimeException e) {
			return key;
		}
	}

	/** Composant traduit. */
	public static Component c(String key, Object... args) {
		return Component.translatable(key, args);
	}

	/** Composant littéral. */
	public static Component lit(String s) {
		return Component.literal(s == null ? "" : s);
	}

	/** Tronque {@code s} à {@code max} caractères avec « … ». */
	public static String cut(String s, int max) {
		if (s == null) return "";
		return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
	}
}
