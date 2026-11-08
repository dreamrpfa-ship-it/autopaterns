package fr.autopaterns.patterns;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import fr.autopaterns.core.Log;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * Touches de lancement par pattern (nouveau en 3.1) : chaque pattern sauvegardé peut recevoir une touche du
 * clavier qui le charge et le lance, une fois ou en boucle.
 *
 * <p>Stockage : {@code config.txt}, une clé par pattern, {@code hotkey.<nom>=<code GLFW>:<ONCE|LOOP>}.
 * Le format des fichiers .apt n'est pas modifié (import / export inchangés).
 *
 * <p>Détection : les touches de pattern ne sont pas des {@link KeyMapping} (leur nombre varie, alors que les
 * KeyMapping doivent être déclarées avant la création des options). On lit donc l'état réel du clavier à chaque
 * fin de tick et on détecte le front montant (touche qui vient d'être enfoncée). L'état est suivi même quand un
 * écran est ouvert, pour qu'une touche maintenue à la fermeture d'un menu ne déclenche rien.
 *
 * <p>Une touche ne peut lancer qu'un seul pattern : l'assigner à un pattern la retire de l'autre.
 */
final class PatternHotkeys {
	private static final String PREFIX = "hotkey.";

	enum Mode {
		ONCE, LOOP;

		Mode next() {
			return this == ONCE ? LOOP : ONCE;
		}
	}

	record Binding(int key, Mode mode) {}

	/** Nom du pattern → touche et mode (ordre alphabétique, pour l'affichage). */
	private final Map<String, Binding> bindings = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
	/** Touches enfoncées au tick précédent (détection du front montant). */
	private final Set<Integer> down = new HashSet<>();
	private boolean pollWarned;

	// ------------------------------------------------------------------ configuration

	void load(Properties config) {
		bindings.clear();
		for (String k : config.stringPropertyNames()) {
			if (!k.startsWith(PREFIX)) continue;
			String name = k.substring(PREFIX.length());
			Binding b = parse(config.getProperty(k));
			if (b == null || name.isEmpty()) {
				Log.warn("touche de lancement ignorée (valeur invalide) : " + k);
				continue;
			}
			removeKey(b.key()); // une touche = un pattern (le dernier lu l'emporte)
			bindings.put(name, b);
		}
		if (!bindings.isEmpty()) Log.info(bindings.size() + " touche(s) de lancement de pattern chargée(s)");
	}

	void save(Properties config) {
		config.stringPropertyNames().stream().filter(k -> k.startsWith(PREFIX)).toList().forEach(config::remove);
		bindings.forEach((name, b) -> config.setProperty(PREFIX + name, b.key() + ":" + b.mode().name()));
	}

	private static Binding parse(String v) {
		if (v == null) return null;
		String[] p = v.trim().split(":", 2);
		try {
			int key = Integer.parseInt(p[0].trim());
			Mode mode = p.length > 1 ? Mode.valueOf(p[1].trim()) : Mode.ONCE;
			return key > 0 ? new Binding(key, mode) : null;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	// ------------------------------------------------------------------ modification

	Binding get(String name) {
		return name == null ? null : bindings.get(name);
	}

	/** Pattern qui utilise déjà cette touche (autre que {@code except}), ou null. */
	String ownerOf(int key, String except) {
		for (Map.Entry<String, Binding> e : bindings.entrySet()) {
			if (e.getValue().key() == key && !e.getKey().equals(except)) return e.getKey();
		}
		return null;
	}

	/** Assigne la touche (en la retirant d'un éventuel autre pattern). */
	void set(String name, int key, Mode mode) {
		removeKey(key);
		bindings.put(name, new Binding(key, mode));
		down.add(key); // la touche vient d'être pressée pour la capture : pas de lancement au relâchement d'écran
	}

	void setMode(String name, Mode mode) {
		Binding b = bindings.get(name);
		if (b != null) bindings.put(name, new Binding(b.key(), mode));
	}

	boolean remove(String name) {
		return bindings.remove(name) != null;
	}

	/** Suit un renommage de pattern. */
	void rename(String from, String to) {
		Binding b = bindings.remove(from);
		if (b != null) bindings.put(to, b);
	}

	private void removeKey(int key) {
		bindings.values().removeIf(b -> b.key() == key);
	}

	/** Copie (nom → liaison) pour l'affichage. */
	Map<String, Binding> all() {
		return new LinkedHashMap<>(bindings);
	}

	// ------------------------------------------------------------------ détection

	/**
	 * Fin de tick : renvoie le nom du pattern dont la touche vient d'être enfoncée, ou null.
	 * Rien n'est déclenché si un écran est ouvert (chat, inventaire, menus…) ou sans joueur.
	 */
	String poll(Minecraft mc) {
		if (bindings.isEmpty()) {
			down.clear();
			return null;
		}
		String fired = null;
		boolean canFire = mc.player != null && mc.gui.screen() == null && mc.isWindowActive();
		Set<Integer> seen = new HashSet<>();
		for (Map.Entry<String, Binding> e : bindings.entrySet()) {
			int key = e.getValue().key();
			if (!seen.add(key)) continue;
			boolean isDown;
			try {
				isDown = InputConstants.isKeyDown(mc.getWindow(), key);
			} catch (RuntimeException | LinkageError ex) {
				if (!pollWarned) {
					pollWarned = true;
					Log.error("lecture du clavier impossible : touches de lancement inactives", ex);
				}
				return null;
			}
			boolean was = down.contains(key);
			if (isDown && !was) {
				down.add(key);
				if (canFire && fired == null) fired = e.getKey();
			} else if (!isDown && was) {
				down.remove(key);
			}
		}
		down.retainAll(seen);
		return fired;
	}

	// ------------------------------------------------------------------ libellés et conflits

	/** Nom lisible de la touche (« G », « F4 », « Pavé num. 1 »…). */
	static String label(int key) {
		try {
			String s = InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString();
			if (s != null && !s.isBlank()) return s.length() > 16 ? s.substring(0, 15) + "…" : s;
		} catch (RuntimeException | LinkageError ignored) {
			// repli ci-dessous
		}
		String n = GLFW.glfwGetKeyName(key, 0);
		return n != null ? n.toUpperCase(java.util.Locale.ROOT) : "#" + key;
	}

	/** Raccourcis du jeu ou des mods déjà liés à cette touche (ils se déclencheront aussi). */
	static List<String> conflicts(Minecraft mc, int key) {
		List<String> out = new ArrayList<>();
		if (mc == null || mc.options == null) return out;
		try {
			KeyEvent ev = new KeyEvent(key, GLFW.glfwGetKeyScancode(key), 0);
			for (KeyMapping km : mc.options.keyMappings) {
				if (!km.isUnbound() && km.matches(ev)) out.add(Component.translatable(km.getName()).getString());
			}
		} catch (RuntimeException | LinkageError e) {
			Log.warn("conflits de touche non vérifiés : " + e);
		}
		return out;
	}
}
