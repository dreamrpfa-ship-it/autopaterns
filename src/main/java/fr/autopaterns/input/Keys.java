package fr.autopaterns.input;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.platform.InputConstants;

import fr.autopaterns.core.Log;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.resources.Identifier;

/**
 * Registre centralisé des raccourcis : enregistrement auprès de Fabric (ils apparaissent aussi dans
 * Options > Commandes et sont sauvegardés dans options.txt), lecture sans double déclenchement,
 * libellés, détection des conflits, réassignation et réinitialisation.
 */
public final class Keys {
	/** Ticks minimum entre deux déclenchements d'un même raccourci (anti-rebond). */
	private static final int DEBOUNCE_TICKS = 3;

	private static final Map<KeyDef, KeyMapping> MAPPINGS = new EnumMap<>(KeyDef.class);
	private static final Map<KeyDef, Long> LAST_FIRE = new EnumMap<>(KeyDef.class);
	private static long tick;

	private Keys() {}

	/** À appeler une seule fois dans onInitializeClient (avant la création des Options). */
	public static void register() {
		Map<KeyDef.Cat, KeyMapping.Category> cats = new EnumMap<>(KeyDef.Cat.class);
		for (KeyDef.Cat c : KeyDef.Cat.values()) {
			cats.put(c, KeyMapping.Category.register(Identifier.fromNamespaceAndPath("autopaterns", c.id)));
		}
		for (KeyDef d : KeyDef.values()) {
			KeyMapping km = new KeyMapping(d.translationKey, InputConstants.Type.KEYSYM, d.defaultKey, cats.get(d.category));
			MAPPINGS.put(d, KeyMappingHelper.registerKeyMapping(km));
		}
	}

	/** Compteur interne de ticks (appelé une fois par END tick par l'automate). */
	public static void tick() {
		tick++;
	}

	public static KeyMapping mapping(KeyDef d) {
		return MAPPINGS.get(d);
	}

	/**
	 * Vrai si le raccourci a été pressé depuis le dernier appel. Tous les clics accumulés sont consommés
	 * (une rafale ne déclenche qu'une action) et un anti-rebond de {@value #DEBOUNCE_TICKS} ticks s'applique.
	 */
	public static boolean poll(KeyDef d) {
		KeyMapping km = MAPPINGS.get(d);
		if (km == null) return false;
		boolean pressed = false;
		int guard = 0;
		while (km.consumeClick() && guard++ < 64) pressed = true;
		if (!pressed) return false;
		Long last = LAST_FIRE.get(d);
		if (last != null && tick - last < DEBOUNCE_TICKS) return false;
		LAST_FIRE.put(d, tick);
		return true;
	}

	/** Vide les clics en attente d'un raccourci sans agir (évite un déclenchement différé). */
	public static void drain(KeyDef d) {
		KeyMapping km = MAPPINGS.get(d);
		if (km == null) return;
		int guard = 0;
		while (km.consumeClick() && guard++ < 64) { /* vidage */ }
	}

	/** La touche correspond-elle à l'événement clavier (pour les écrans) ? */
	public static boolean matches(KeyDef d, KeyEvent e) {
		KeyMapping km = MAPPINGS.get(d);
		return km != null && !km.isUnbound() && km.matches(e);
	}

	public static boolean matchesMouse(KeyDef d, MouseButtonEvent e) {
		KeyMapping km = MAPPINGS.get(d);
		return km != null && !km.isUnbound() && km.matchesMouse(e);
	}

	/** Libellé de la touche assignée (« — » si aucune), tronqué à 16 caractères. */
	public static String label(KeyDef d) {
		return label(MAPPINGS.get(d));
	}

	public static String label(KeyMapping km) {
		try {
			if (km == null || km.isUnbound()) return "—";
			String s = km.getTranslatedKeyMessage().getString();
			return s.length() > 16 ? s.substring(0, 15) + "…" : s;
		} catch (RuntimeException | LinkageError e) {
			return "?";
		}
	}

	/** Toutes les touches du jeu (vanilla + mods) en conflit avec {@code km}. */
	public static List<KeyMapping> conflicts(KeyMapping km) {
		List<KeyMapping> out = new ArrayList<>();
		Minecraft mc = Minecraft.getInstance();
		if (km == null || km.isUnbound() || mc == null || mc.options == null) return out;
		for (KeyMapping other : mc.options.keyMappings) {
			if (other != km && !other.isUnbound() && other.same(km)) out.add(other);
		}
		return out;
	}

	/** Assigne une touche (ou {@link InputConstants#UNKNOWN} pour désassigner) et sauvegarde options.txt. */
	public static void rebind(KeyMapping km, InputConstants.Key key) {
		km.setKey(key);
		KeyMapping.resetMapping();
		saveOptions();
	}

	/** Remet les touches par défaut de tous les raccourcis du mod. */
	public static void resetAll() {
		for (KeyMapping km : MAPPINGS.values()) km.setKey(km.getDefaultKey());
		KeyMapping.resetMapping();
		saveOptions();
	}

	public static void reset(KeyDef d) {
		KeyMapping km = MAPPINGS.get(d);
		if (km == null) return;
		km.setKey(km.getDefaultKey());
		KeyMapping.resetMapping();
		saveOptions();
	}

	private static void saveOptions() {
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null && mc.options != null) mc.options.save();
		} catch (RuntimeException e) {
			Log.error("options.txt non sauvegardé", e);
		}
	}
}
