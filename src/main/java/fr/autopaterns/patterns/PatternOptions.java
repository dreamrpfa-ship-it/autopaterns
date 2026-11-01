package fr.autopaterns.patterns;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import net.minecraft.locale.Language;
import net.minecraft.world.item.ItemStack;

/**
 * Réglages propres à chaque pattern (section « options » des fichiers .apt) : remplacement des items,
 * action en cas d'erreur, vente et corbeille automatiques. Format et valeurs par défaut identiques à la 2.x.
 * Les items sont identifiés par leur clé de traduction (ex. {@code item.minecraft.diamond}), comme en 2.x.
 */
public final class PatternOptions {
	/** Action exécutée quand la lecture s'arrête sur une erreur. */
	public enum OnError {
		NONE("Rien"), DISCONNECT("Se déconnecter"), COMMAND("Exécuter une commande");

		public final String label;

		OnError(String label) {
			this.label = label;
		}

		public OnError next() {
			return values()[(ordinal() + 1) % values().length];
		}
	}

	public static final String DEFAULT_STEPS = "item:droit ; nom:entièreté:gauche";
	public static final List<String> DEFAULT_SELL = List.of(
		"item.minecraft.diamond", "block.minecraft.soul_sand", "item.minecraft.emerald", "item.minecraft.bone",
		"item.minecraft.gold_ingot", "block.minecraft.dirt", "item.minecraft.coal", "item.minecraft.iron_ingot");
	public static final int[] THRESHOLDS = {0, 1, 2, 3, 4, 5, 9, 36};
	public static final int[] DELAYS = {2, 3, 4, 6, 8, 10};
	public static final int MAX_ITEMS = 128;

	public OnError onError = OnError.NONE;
	public String command = "";
	/** Item de référence de chaque emplacement de la barre d'action (capturé à l'enregistrement), ou null. */
	public final String[] slotItems = new String[9];
	/** Remplacer automatiquement l'item de l'emplacement s'il casse ou s'épuise. */
	public final boolean[] refill = new boolean[9];

	public boolean sellEnabled;
	public boolean sellBetween;
	public int sellThreshold;
	public String sellCommand = "/shop";
	public String sellSteps = DEFAULT_STEPS;
	public String sellPageText = "suivant";
	public String sellCategory = "brown_dye";
	public int sellDelay = 4;
	public final LinkedHashSet<String> sellItems = new LinkedHashSet<>(DEFAULT_SELL);

	public boolean trashEnabled;
	public boolean trashBetween;
	public int trashThreshold;
	public String trashCommand = "/trash";
	public int trashDelay = 3;
	public final LinkedHashSet<String> trashItems = new LinkedHashSet<>();

	// ------------------------------------------------------------ accès par mode (trash = corbeille, sinon vente)

	public boolean enabled(boolean trash) { return trash ? trashEnabled : sellEnabled; }
	public boolean between(boolean trash) { return trash ? trashBetween : sellBetween; }
	public int threshold(boolean trash) { return trash ? trashThreshold : sellThreshold; }
	public int delay(boolean trash) { return trash ? trashDelay : sellDelay; }
	public String command(boolean trash) { return trash ? trashCommand : sellCommand; }
	public LinkedHashSet<String> items(boolean trash) { return trash ? trashItems : sellItems; }

	public void setEnabled(boolean trash, boolean v) { if (trash) trashEnabled = v; else sellEnabled = v; }
	public void setBetween(boolean trash, boolean v) { if (trash) trashBetween = v; else sellBetween = v; }
	public void setThreshold(boolean trash, int v) { v = clamp(v, 0, 36); if (trash) trashThreshold = v; else sellThreshold = v; }
	public void setDelay(boolean trash, int v) { v = clamp(v, 1, 40); if (trash) trashDelay = v; else sellDelay = v; }
	public void setCommand(boolean trash, String v) { v = cut(v == null ? "" : v, 256); if (trash) trashCommand = v; else sellCommand = v; }

	/** Au moins un emplacement à réapprovisionner. */
	public boolean anyRefill() {
		for (int i = 0; i < 9; i++) if (refill[i] && slotItems[i] != null) return true;
		return false;
	}

	public PatternOptions copy() {
		PatternOptions o = new PatternOptions();
		o.onError = onError;
		o.command = command;
		System.arraycopy(slotItems, 0, o.slotItems, 0, 9);
		System.arraycopy(refill, 0, o.refill, 0, 9);
		o.sellEnabled = sellEnabled;
		o.sellBetween = sellBetween;
		o.sellThreshold = sellThreshold;
		o.sellCommand = sellCommand;
		o.sellSteps = sellSteps;
		o.sellPageText = sellPageText;
		o.sellCategory = sellCategory;
		o.sellDelay = sellDelay;
		o.sellItems.clear();
		o.sellItems.addAll(sellItems);
		o.trashEnabled = trashEnabled;
		o.trashBetween = trashBetween;
		o.trashThreshold = trashThreshold;
		o.trashCommand = trashCommand;
		o.trashDelay = trashDelay;
		o.trashItems.clear();
		o.trashItems.addAll(trashItems);
		return o;
	}

	// ------------------------------------------------------------ sérialisation (19 lignes, ordre 2.x)

	public List<String> serialize() {
		List<String> l = new ArrayList<>(19);
		l.add("onError=" + onError.name());
		l.add("command=" + oneLine(command));
		StringBuilder items = new StringBuilder();
		for (int i = 0; i < 9; i++) {
			if (i > 0) items.append('|');
			if (slotItems[i] != null) items.append(slotItems[i]);
		}
		l.add("items=" + items);
		StringBuilder rf = new StringBuilder();
		for (int i = 0; i < 9; i++) {
			if (i > 0) rf.append(',');
			rf.append(refill[i] ? '1' : '0');
		}
		l.add("refill=" + rf);
		l.add("sell=" + b(sellEnabled));
		l.add("sellBetween=" + b(sellBetween));
		l.add("sellThreshold=" + sellThreshold);
		l.add("sellCommand=" + oneLine(sellCommand));
		l.add("sellSteps=" + oneLine(sellSteps));
		l.add("sellPage=" + oneLine(sellPageText));
		l.add("sellCategory=" + oneLine(sellCategory));
		l.add("sellDelay=" + sellDelay);
		l.add("sellItems=" + String.join("|", sellItems));
		l.add("trash=" + b(trashEnabled));
		l.add("trashBetween=" + b(trashBetween));
		l.add("trashThreshold=" + trashThreshold);
		l.add("trashCommand=" + oneLine(trashCommand));
		l.add("trashDelay=" + trashDelay);
		l.add("trashItems=" + String.join("|", trashItems));
		return l;
	}

	public static PatternOptions parse(List<String> lines) {
		PatternOptions o = new PatternOptions();
		boolean sawCategory = false;
		for (String line : lines) {
			if (line == null) continue;
			int i = line.indexOf('=');
			if (i <= 0) continue;
			String key = line.substring(0, i);
			String val = line.substring(i + 1);
			switch (key) {
				case "onError" -> {
					try {
						o.onError = OnError.valueOf(val.trim());
					} catch (IllegalArgumentException e) {
						o.onError = OnError.NONE;
					}
				}
				case "command" -> o.command = cut(val, 256);
				case "items" -> {
					String[] p = val.split("\\|", -1);
					for (int j = 0; j < 9 && j < p.length; j++) {
						o.slotItems[j] = (p[j].isBlank() || p[j].contains(" ")) ? null : p[j];
					}
				}
				case "refill" -> {
					String[] p = val.split(",", -1);
					for (int j = 0; j < 9 && j < p.length; j++) o.refill[j] = "1".equals(p[j].trim());
				}
				case "sell" -> o.sellEnabled = "1".equals(val.trim());
				case "sellBetween" -> o.sellBetween = "1".equals(val.trim());
				case "trash" -> o.trashEnabled = "1".equals(val.trim());
				case "trashBetween" -> o.trashBetween = "1".equals(val.trim());
				case "sellThreshold" -> o.sellThreshold = clampInt(val, 0, 36, 0);
				case "trashThreshold" -> o.trashThreshold = clampInt(val, 0, 36, 0);
				case "sellDelay" -> o.sellDelay = clampInt(val, 1, 40, 4);
				case "trashDelay" -> o.trashDelay = clampInt(val, 1, 40, 3);
				case "sellCommand" -> o.sellCommand = cut(val, 256);
				case "trashCommand" -> o.trashCommand = cut(val, 256);
				case "sellSteps" -> o.sellSteps = cut(val, 256);
				case "sellPage" -> o.sellPageText = cut(val, 64);
				case "sellCategory" -> {
					o.sellCategory = cut(val.trim(), 64);
					sawCategory = true;
				}
				case "sellItems" -> {
					o.sellItems.clear();
					addItems(o.sellItems, val);
				}
				case "trashItems" -> addItems(o.trashItems, val);
				default -> { }
			}
		}
		if (!sawCategory && o.sellThreshold == 1) o.sellThreshold = 0; // migration des fichiers anciens
		return o;
	}

	private static void addItems(LinkedHashSet<String> set, String val) {
		for (String s : val.split("\\|")) {
			if (!s.isBlank() && !s.contains(" ") && set.size() < MAX_ITEMS) set.add(s.trim());
		}
	}

	// ------------------------------------------------------------ utilitaires d'items

	/** Identifiant d'un item tel que stocké dans les options (clé de traduction), ou null si vide. */
	public static String itemId(ItemStack st) {
		if (st == null || st.isEmpty()) return null;
		return st.getItem().getDescriptionId();
	}

	/** Nom lisible (langue du jeu) d'un identifiant d'item, sinon son nom court. */
	public static String displayName(String key) {
		try {
			if (key != null && Language.getInstance().has(key)) return Language.getInstance().getOrDefault(key);
		} catch (RuntimeException | LinkageError ignored) {
			// repli sur le nom court
		}
		return shortName(key);
	}

	/** Partie après le dernier point ({@code item.minecraft.diamond_pickaxe} → {@code diamond_pickaxe}). */
	public static String shortName(String key) {
		if (key == null) return "—";
		int i = key.lastIndexOf('.');
		return i < 0 ? key : key.substring(i + 1);
	}

	/** Même item, en ignorant le préfixe {@code block.} / {@code item.}. */
	public static boolean sameItem(String a, String b) {
		if (a == null || b == null) return false;
		return a.equals(b) || bare(a).equals(bare(b));
	}

	private static String bare(String s) {
		if (s.startsWith("block.")) return s.substring(6);
		if (s.startsWith("item.")) return s.substring(5);
		return s;
	}

	/** Prochaine valeur strictement supérieure dans {@code arr}, sinon la première (cycle des sélecteurs). */
	public static int nextIn(int[] arr, int v) {
		for (int x : arr) if (x > v) return x;
		return arr[0];
	}

	private static String b(boolean v) {
		return v ? "1" : "0";
	}

	private static String oneLine(String s) {
		return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ');
	}

	private static String cut(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max);
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	private static int clampInt(String v, int min, int max, int def) {
		try {
			return clamp(Integer.parseInt(v.trim()), min, max);
		} catch (NumberFormatException e) {
			return def;
		}
	}

	@Override
	public String toString() {
		return onError + " " + command + " " + Arrays.toString(slotItems) + " " + Arrays.toString(refill);
	}
}
