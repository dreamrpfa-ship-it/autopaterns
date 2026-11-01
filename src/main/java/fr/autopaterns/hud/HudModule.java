package fr.autopaterns.hud;

import java.util.List;

import fr.autopaterns.core.Texts;

/**
 * Les 8 modules du HUD (mêmes noms et même ordre qu'en 2.x : ordre de dessin, d'empilement automatique
 * et ordre dans hud.txt).
 * <ul>
 *   <li>{@code stretchable} : en placement automatique, partage la largeur commune de la colonne (2.x) ;</li>
 *   <li>{@code resizable} : largeur réglable à la poignée (3.0), entre {@code minWidth} et {@code maxWidth}
 *       (pixels non mis à l'échelle) ; la hauteur suit toujours le contenu ;</li>
 *   <li>{@code infos} : informations affichables individuellement (cases du panneau de réglages).</li>
 * </ul>
 */
public enum HudModule {
	STATUT("statut", true, true, 70, 320,
		new Info("pattern", true), new Info("detail", true)),
	PROGRESSION("progression", true, true, 60, 360,
		new Info("time", true), new Info("bar", true), new Info("repetition", true), new Info("percent", false)),
	ECART("ecart", true, true, 60, 260,
		new Info("current", true), new Info("max", true)),
	COMPTEURS("compteurs", true, true, 70, 360,
		new Info("checkpoints", true), new Info("repairs", true), new Info("refills", true),
		new Info("sales", true), new Info("trash", true), new Info("fishing", true)),
	INVENTAIRE("inventaire", true, true, 60, 240,
		new Info("gauge", true)),
	BUTIN("butin", false, false, 0, 0,
		new Info("counts", true)),
	TOUCHES("touches", false, false, 0, 0,
		new Info("move", true), new Info("mouse", true), new Info("space", true)),
	NOTIF("notif", false, true, 80, 400);

	/** Information affichable d'un module : clé (fichier hud.txt) et valeur par défaut. */
	public record Info(String key, boolean def) {}

	public final String id;
	public final boolean stretchable;
	public final boolean resizable;
	public final int minWidth;
	public final int maxWidth;
	public final List<Info> infos;

	HudModule(String id, boolean stretchable, boolean resizable, int minWidth, int maxWidth, Info... infos) {
		this.id = id;
		this.stretchable = stretchable;
		this.resizable = resizable;
		this.minWidth = minWidth;
		this.maxWidth = maxWidth;
		this.infos = List.of(infos);
	}

	/** Libellé affiché (« Statut », « Progression »…). */
	public String label() {
		return Texts.tr("autopaterns.hud.module." + id);
	}

	/** Libellé d'une information du module. */
	public String infoLabel(String key) {
		return Texts.tr("autopaterns.hud.info." + id + "." + key);
	}

	public Info info(String key) {
		for (Info i : infos) if (i.key().equals(key)) return i;
		return null;
	}

	/** Largeur bornée aux limites du module (0 = automatique). */
	public int clampWidth(int w) {
		if (!resizable || w <= 0) return 0;
		return Math.max(minWidth, Math.min(maxWidth, w));
	}
}
