package fr.autopaterns.selling;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import fr.autopaterns.core.Log;
import fr.autopaterns.storage.AtomicFiles;

/**
 * Ventes (ou corbeilles) capturées, une liste de clics par item, persistées dans
 * {@code autopaterns/ventes.txt} / {@code autopaterns/corbeille.txt}. Format 2.x inchangé :
 * <pre>
 * # en-tête
 * cléItem TAB clic1;clic2;…
 * </pre>
 * avec {@code clic = slot,bouton,action,p|c,cléItem,nomBase64Url}. Lecture tolérante (ligne illisible ignorée).
 * Thread client uniquement.
 */
public final class SellRecipes {
	/** Codes d'action stockés dans les fichiers (ordre des {@code SlotActionType} 2.x). */
	public static final int PICKUP = 0, QUICK_MOVE = 1, SWAP = 2, CLONE = 3, THROW = 4, PICKUP_ALL = 5;

	static final String SELL_HEADER = "# AutoPaterns : ventes capturées (item, puis les clics dans la boutique)";
	/** 3.0 : en-tête propre à la corbeille (2.x écrivait celui des ventes ; les lignes « # » sont ignorées à la lecture). */
	static final String TRASH_HEADER = "# AutoPaterns : corbeilles capturées (item, puis les clics dans le menu)";

	/**
	 * Un clic relevé pendant une capture.
	 *
	 * @param slot      index du slot dans le menu au moment de la capture
	 * @param button    bouton de la souris (0 gauche, 1 droit, ou touche de la barre pour SWAP)
	 * @param action    code d'action ({@link #PICKUP}…)
	 * @param playerInv le slot appartenait à l'inventaire du joueur
	 * @param key       clé de traduction de l'item ({@code ""} : slot vide, clic « poser »)
	 * @param name      nom affiché de l'item (≤ 256 caractères, codes § éventuels)
	 */
	public record Click(int slot, int button, int action, boolean playerInv, String key, String name) {
		public Click {
			key = key == null ? "" : key;
			name = name == null ? "" : name;
		}

		public String encode() {
			return slot + "," + button + "," + action + "," + (playerInv ? "p" : "c") + "," + key + ","
				+ Base64.getUrlEncoder().withoutPadding().encodeToString(name.getBytes(StandardCharsets.UTF_8));
		}

		public static Click decode(String s) {
			String[] p = s.trim().split(",", -1);
			if (p.length != 6) throw new IllegalArgumentException("clic illisible : " + s);
			String name = new String(Base64.getUrlDecoder().decode(p[5]), StandardCharsets.UTF_8);
			return new Click(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()),
				"p".equals(p[3]), p[4], name);
		}

		@Override
		public String toString() {
			return (playerInv ? "inv" : "menu") + "#" + slot + "/" + button + "/" + action + " " + (key.isEmpty() ? "(vide)" : key);
		}
	}

	private final Path file;
	private final boolean trash;
	private final Map<String, List<Click>> map = new LinkedHashMap<>();
	private FileTime loadedStamp;

	public SellRecipes(Path file, boolean trash) {
		this.file = file;
		this.trash = trash;
		load();
	}

	public Path file() {
		return file;
	}

	/** Relit le fichier s'il a été modifié sur le disque depuis le dernier chargement (édition manuelle). */
	public void reloadIfChanged() {
		FileTime now = stamp();
		if (now == null ? loadedStamp != null : !now.equals(loadedStamp)) load();
	}

	public List<Click> get(String key) {
		return map.get(key);
	}

	public boolean has(String key) {
		return map.containsKey(key);
	}

	/** Copie immuable, ordre d'insertion conservé. */
	public Map<String, List<Click>> all() {
		return Collections.unmodifiableMap(new LinkedHashMap<>(map));
	}

	public int size() {
		return map.size();
	}

	/** Enregistre la capture d'un item (liste vide refusée : elle ne serait pas rejouable). */
	public void put(String key, List<Click> clicks) throws IOException {
		if (key == null || key.isBlank()) throw new IllegalArgumentException("item vide");
		if (clicks == null || clicks.isEmpty()) throw new IllegalArgumentException("aucun clic");
		map.put(key, List.copyOf(clicks));
		save();
	}

	public boolean remove(String key) throws IOException {
		boolean had = map.remove(key) != null;
		if (had) save();
		return had;
	}

	private void save() throws IOException {
		StringBuilder sb = new StringBuilder();
		sb.append(trash ? TRASH_HEADER : SELL_HEADER).append('\n');
		for (Map.Entry<String, List<Click>> e : map.entrySet()) {
			if (e.getValue().isEmpty()) continue;
			sb.append(e.getKey()).append('\t');
			List<Click> l = e.getValue();
			for (int i = 0; i < l.size(); i++) {
				if (i > 0) sb.append(';');
				sb.append(l.get(i).encode());
			}
			sb.append('\n');
		}
		AtomicFiles.writeString(file, sb.toString());
		loadedStamp = stamp();
	}

	private void load() {
		map.clear();
		loadedStamp = stamp();
		Path bak = file.resolveSibling(file.getFileName() + ".bak");
		if (!Files.isRegularFile(file) && !Files.isRegularFile(bak)) return;
		try {
			for (String line : AtomicFiles.readLinesWithBackup(file)) {
				int tab = line.indexOf('\t');
				if (line.isBlank() || line.startsWith("#") || tab <= 0) continue;
				try {
					List<Click> clicks = new ArrayList<>();
					for (String part : line.substring(tab + 1).split(";")) {
						if (!part.isBlank()) clicks.add(Click.decode(part));
					}
					if (!clicks.isEmpty()) map.put(line.substring(0, tab).trim(), List.copyOf(clicks));
				} catch (RuntimeException e) {
					Log.warn("vente capturée illisible ignorée : " + e.getMessage());
				}
			}
		} catch (IOException | RuntimeException e) {
			Log.warn("ventes capturées illisibles : " + e);
		}
	}

	private FileTime stamp() {
		try {
			return Files.isRegularFile(file) ? Files.getLastModifiedTime(file) : null;
		} catch (IOException e) {
			return null;
		}
	}
}
