package fr.autopaterns.selling;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import fr.autopaterns.core.Texts;

/**
 * Grammaire des étapes de vente 2.x : {@code item:droit ; nom:entièreté:gauche ; attendre:10}.
 * Étapes séparées par « ; » ou un retour à la ligne ; mots-clés insensibles à la casse et aux accents.
 * <ul>
 *   <li>{@code item[:gauche|droit]} : clic sur l'item à vendre (trouvé dans les catégories / pages) ;</li>
 *   <li>{@code nom:texte[:gauche|droit]} : clic sur le premier bouton dont le nom contient le texte ;</li>
 *   <li>{@code attendre[:ticks]} : pause (1..200, défaut 10).</li>
 * </ul>
 * 3.0 : le texte d'une étape « nom » peut contenir « : » (2.x le coupait). Seul un dernier segment
 * gauche/droit/left/right est lu comme bouton ; les étapes 2.x valides sont lues à l'identique.
 */
public final class SellSteps {
	private SellSteps() {}

	public static final String ITEM = "item", NAME = "nom", WAIT = "attendre";

	/** Une étape ; {@code button} 0 = gauche, 1 = droit. */
	public record Step(String kind, String text, int button, int ticks) {
		/** Libellé de progression (HUD). */
		public String describe() {
			String side = Texts.tr(button == 1 ? "autopaterns.selling.side.right" : "autopaterns.selling.side.left");
			return switch (kind) {
				case ITEM -> Texts.tr("autopaterns.selling.step.item", side);
				case NAME -> Texts.tr("autopaterns.selling.step.name", side, text);
				default -> Texts.tr("autopaterns.selling.step.wait", ticks);
			};
		}
	}

	/** Minuscules, sans codes §, espaces réduits, sans accents (comparaisons de noms). */
	public static String normalize(String s) {
		if (s == null) return "";
		String t = s.replaceAll("§.", "").toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
		return Normalizer.normalize(t, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
	}

	/** 1 = droit, 0 = gauche, -1 = pas un mot de bouton. */
	static int buttonOrNeg(String s) {
		return switch (normalize(s)) {
			case "droit", "droite", "right" -> 1;
			case "gauche", "left" -> 0;
			default -> -1;
		};
	}

	static int button(String s) {
		int b = buttonOrNeg(s);
		if (b < 0) throw new IllegalArgumentException(Texts.tr("autopaterns.selling.steps.err.button", s.trim()));
		return b;
	}

	/**
	 * Analyse les étapes ; lève {@link IllegalArgumentException} (message en français, affiché tel quel)
	 * si une étape est invalide ou si la liste est vide.
	 */
	public static List<Step> parse(String spec) {
		List<Step> list = new ArrayList<>();
		for (String raw : (spec == null ? "" : spec).split("[;\n]")) {
			String line = raw.trim();
			if (line.isEmpty()) continue;
			int c = line.indexOf(':');
			String kind = normalize(c < 0 ? line : line.substring(0, c));
			String rest = c < 0 ? null : line.substring(c + 1);
			switch (kind) {
				case ITEM -> {
					String b = rest == null ? "" : rest.split(":", -1)[0];
					list.add(new Step(ITEM, "", b.isBlank() ? 0 : button(b), 0));
				}
				case NAME -> {
					if (rest == null || rest.isBlank()) throw new IllegalArgumentException(Texts.tr("autopaterns.selling.steps.err.noText", line));
					String text = rest;
					int btn = 0;
					int last = rest.lastIndexOf(':');
					if (last >= 0) {
						int b = buttonOrNeg(rest.substring(last + 1));
						if (b >= 0) {
							btn = b;
							text = rest.substring(0, last);
						} else if (rest.substring(last + 1).isBlank()) {
							text = rest.substring(0, last); // « nom:texte: » : bouton omis
						}
					}
					text = text.trim();
					if (text.isEmpty()) throw new IllegalArgumentException(Texts.tr("autopaterns.selling.steps.err.noText", line));
					list.add(new Step(NAME, text, btn, 0));
				}
				case WAIT -> {
					int t;
					try {
						String v = rest == null ? "" : rest.split(":", -1)[0];
						t = v.isBlank() ? 10 : Integer.parseInt(v.trim());
					} catch (NumberFormatException e) {
						throw new IllegalArgumentException(Texts.tr("autopaterns.selling.steps.err.wait", line));
					}
					list.add(new Step(WAIT, "", 0, Math.max(1, Math.min(200, t))));
				}
				default -> throw new IllegalArgumentException(Texts.tr("autopaterns.selling.steps.err.unknown", line));
			}
		}
		if (list.isEmpty()) throw new IllegalArgumentException(Texts.tr("autopaterns.selling.steps.err.empty"));
		return list;
	}
}
