package fr.autopaterns.hud;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import fr.autopaterns.core.Texts;
import fr.autopaterns.gui.Theme;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.locale.Language;
import net.minecraft.world.item.ItemStack;

/**
 * Mesure, placement et dessin des modules (équivalent de {@code Hud.render/measure/paint} 2.x), avec le design 3.0 :
 * tuiles sombres translucides à bordure fine cyan, liseré d'accent, texte sans ombre sur fond (ombre si le fond est
 * coupé), apparition/disparition en fondu, barre de progression lissée, touches animées.
 *
 * <p>Placement identique à la 2.x : modules automatiques empilés à gauche à partir de {@code max(64, H/4)}, espacés de
 * 2 px, les modules « étirables » partageant la même largeur ; modules placés : position fractionnaire de l'espace libre.
 * Les bornes ({@link #bounds}) sont celles de la dernière image, après échelle (zones de clic de l'éditeur).
 */
final class HudRenderer {
	static final int PAD_X = 5;
	static final int PAD_Y = 4;
	static final int LINE = 10;
	static final int MARGIN = 3;
	static final int GAP = 2;
	static final int KEY = 16;
	static final int KEY_GAP = 2;
	static final int MOUSE_H = 12;
	static final int BAR_H = 5;
	static final int COL_GAP = 10;
	static final int VAL_GAP = 6;
	static final int TRACK = 0x30FFFFFF;
	static final int KEY_TEXT_ON = 0xFF07101C;
	/** Hauteur minimale d'empilement automatique (2.x). */
	static final int AUTO_TOP = 64;

	private record Item(HudModule m, HudConf c, HudState st, int[] d, float alpha, boolean ghost) {}

	private final HudLayout layout;
	private final EnumMap<HudModule, int[]> bounds = new EnumMap<>(HudModule.class);
	private final List<HudModule> drawOrder = new ArrayList<>();
	private final EnumMap<HudModule, Float> fade = new EnumMap<>(HudModule.class);
	private final EnumMap<HudModule, HudState> lastShown = new EnumMap<>(HudModule.class);
	private final float[] keyAnim = new float[7];
	private float barFrac;
	private boolean barInit;
	private long lastNanos = System.nanoTime();
	private final Map<String, String> trCache = new HashMap<>();
	private Language trLang;

	// valeurs de l'image en cours
	private Font font;
	private Minecraft mc;
	private long now;
	private boolean edit;

	HudRenderer(HudLayout layout) {
		this.layout = layout;
	}

	/** Bornes à l'écran (x, y, largeur, hauteur, après échelle) du dernier rendu, ou null. */
	int[] bounds(HudModule m) {
		return bounds.get(m);
	}

	/** Module affiché sous le point (le plus au-dessus), ou null. */
	HudModule at(double x, double y) {
		for (int i = drawOrder.size() - 1; i >= 0; i--) {
			HudModule m = drawOrder.get(i);
			int[] b = bounds.get(m);
			if (b != null && x >= b[0] && x < b[0] + b[2] && y >= b[1] && y < b[1] + b[3]) return m;
		}
		return null;
	}

	/** Le point est-il sur la poignée de redimensionnement (coin bas-droit) du module ? */
	boolean onHandle(HudModule m, double x, double y) {
		int[] b = bounds.get(m);
		if (b == null || !m.resizable) return false;
		int cx = b[0] + b[2], cy = b[1] + b[3];
		return x >= cx - 4 && x <= cx + 4 && y >= cy - 4 && y <= cy + 4;
	}

	// ------------------------------------------------------------------ rendu

	/**
	 * Dessine le HUD.
	 * @param edit      mode éditeur : tous les modules (désactivés en transparence), sans fondu, avec contours
	 * @param onlyNotif mod en pause après une erreur : seul le module de notifications reste affiché
	 */
	void render(GuiGraphicsExtractor g, Minecraft mc, HudState s, int W, int H, boolean edit,
				HudModule hover, HudModule selected, boolean onlyNotif) {
		this.mc = mc;
		this.font = mc.font;
		this.now = System.nanoTime();
		this.edit = edit;
		bounds.clear();
		drawOrder.clear();
		List<Item> items = new ArrayList<>();
		int stretchW = 0;
		for (int pass = 0; pass < (edit ? 2 : 1); pass++) {
			for (HudModule m : HudModule.values()) {
				HudConf c = layout.conf(m);
				boolean ghost = !c.enabled;
				if (ghost != (pass == 1)) {
					if (!edit && ghost) {
						fade.put(m, 0f);
						lastShown.remove(m);
					}
					continue;
				}
				if (!edit && onlyNotif && m != HudModule.NOTIF) {
					fade.put(m, 0f);
					lastShown.remove(m);
					continue;
				}
				HudState st = s;
				float a = 1f;
				if (!edit) {
					boolean target = visible(m, c, s);
					float f = Theme.approach(fade.getOrDefault(m, 0f), target ? 1f : 0f, lastNanos, now);
					if (target) {
						if (f > 0.985f) f = 1f;
						lastShown.put(m, s);
					} else {
						st = lastShown.get(m);
						if (st == null || f < 0.02f) {
							fade.put(m, 0f);
							lastShown.remove(m);
							continue;
						}
					}
					fade.put(m, f);
					a = f;
				}
				int[] d = measure(m, c, st);
				if (d == null) continue;
				if (m.resizable && c.width > 0) d[0] = m.clampWidth(c.width);
				float sc = c.scale / 100f;
				if (!ghost && c.auto() && m.stretchable && c.width <= 0) stretchW = Math.max(stretchW, (int) Math.ceil(d[0] * sc));
				items.add(new Item(m, c, st, d, a, ghost));
			}
		}

		int autoY = Math.max(AUTO_TOP, H / 4);
		float global = layout.opacity() / 100f;
		for (Item it : items) {
			HudConf c = it.c();
			int[] d = it.d();
			float sc = c.scale / 100f;
			if (!it.ghost() && c.auto() && it.m().stretchable && c.width <= 0) d[0] = Math.max(d[0], (int) Math.floor(stretchW / sc));
			int sw = (int) Math.ceil(d[0] * sc);
			int sh = (int) Math.ceil(d[1] * sc);
			int x, y;
			if (c.auto()) {
				x = MARGIN;
				y = autoY;
				autoY += sh + GAP;
			} else {
				x = MARGIN + (int) Math.round(c.fx * Math.max(0, W - sw - 6));
				y = MARGIN + (int) Math.round(c.fy * Math.max(0, H - sh - 6));
			}
			bounds.put(it.m(), new int[] {x, y, sw, sh});
			drawOrder.add(it.m());
			float al = it.alpha() * global * c.opacity / 100f;
			if (it.ghost()) al *= 0.35f;
			if (al > 0.01f) drawScaled(g, it, x, y, sc, al);
			if (edit) {
				int col = it.m() == selected ? Theme.CYAN
					: it.m() == hover ? Theme.alpha(Theme.CYAN, 0.6f)
					: it.ghost() ? 0x30FFFFFF : 0x50FFFFFF;
				Theme.frame(g, x - 1, y - 1, sw + 2, sh + 2, col);
				if (it.m().resizable && !it.ghost() && (it.m() == selected || it.m() == hover)) {
					g.fill(x + sw - 2, y + sh - 2, x + sw + 3, y + sh + 3, Theme.CYAN);
					g.fill(x + sw - 1, y + sh - 1, x + sw + 2, y + sh + 2, KEY_TEXT_ON);
				}
			}
		}
		lastNanos = now;
	}

	/** Visibilité hors éditeur (2.x §2.6, avec « toujours visible » respecté pour le butin vide). */
	static boolean visible(HudModule m, HudConf c, HudState s) {
		return switch (m) {
			case NOTIF -> s.toast() != null;
			case BUTIN -> s.loot().isEmpty() ? c.always : (s.active() || c.always);
			case PROGRESSION, ECART -> (s.playing() || c.always) && (s.active() || c.always);
			default -> s.active() || c.always;
		};
	}

	private void drawScaled(GuiGraphicsExtractor g, Item it, int x, int y, float sc, float al) {
		if (Math.abs(sc - 1f) < 0.001f) {
			paint(g, it, x, y, al);
			return;
		}
		var pose = g.pose();
		pose.pushMatrix();
		try {
			pose.translate((float) x, (float) y);
			pose.scale(sc, sc);
			paint(g, it, 0, 0, al);
		} finally {
			pose.popMatrix();
		}
	}

	// ------------------------------------------------------------------ mesure (tailles non mises à l'échelle)

	/** {largeur, hauteur, 1 si contenu de remplacement « — »} ou null si rien à dessiner. */
	private int[] measure(HudModule m, HudConf c, HudState st) {
		return switch (m) {
			case STATUT -> {
				boolean name = c.shows("pattern") && st.pattern() != null;
				boolean det = c.shows("detail") && st.detail() != null && !st.detail().isEmpty();
				int w = 7 + w(st.label());
				if (name) w += 6 + w(st.pattern());
				int lines = 1;
				if (det) {
					w = Math.max(w, Math.min(220, w(st.detail())));
					lines = 2;
				}
				yield new int[] {w + 10, lines * LINE + 8 - 1, 0};
			}
			case PROGRESSION -> {
				String left = progressLeft(c, st);
				String right = progressRight(c, st);
				boolean bar = c.shows("bar");
				boolean line = left != null || right != null;
				if (!line && !bar) yield placeholder(m);
				int tw = (left == null ? 0 : w(left)) + (right == null ? 0 : (left == null ? 0 : 8) + w(right));
				int h = 2 * PAD_Y + (line ? 9 : 0) + (bar ? (line ? 2 : 0) + 3 : 0);
				yield new int[] {Math.max(96, tw) + 10, h, 0};
			}
			case ECART -> {
				String[] p = deviationParts(c, st);
				if (p == null) yield placeholder(m);
				yield new int[] {w(p[0] + p[1] + p[2]) + 10, 17, 0};
			}
			case COMPTEURS -> {
				List<String[]> e = counters(c, st);
				if (e.isEmpty()) yield placeholder(m);
				int[] cols = counterColumns(e);
				int w = 0;
				for (int k = 0; k < cols.length / 2; k++) w += cols[2 * k] + VAL_GAP + cols[2 * k + 1] + (k > 0 ? COL_GAP : 0);
				yield new int[] {w + 10, Math.min(3, e.size()) * LINE + 7, 0};
			}
			case INVENTAIRE -> {
				boolean gauge = c.shows("gauge");
				int w = w(tr("autopaterns.hud.inventory") + "  " + freeText(st.freeSlots())) + 10;
				yield new int[] {Math.max(gauge ? 70 : 0, w), 17 + (gauge ? 4 : 0), 0};
			}
			case BUTIN -> {
				if (st.loot().isEmpty()) yield (edit || c.always) ? placeholder(m) : null;
				yield new int[] {18 * st.loot().size() + 6 - 2, 22, 0};
			}
			case TOUCHES -> {
				int h = keysHeight(c);
				if (h <= 0) yield placeholder(m);
				yield new int[] {3 * KEY + 2 * KEY_GAP, h, 0};
			}
			case NOTIF -> {
				String t = notifText(st);
				if (t == null) yield null;
				yield new int[] {Math.min(280, w(Texts.strip(t))) + 10 + 3, 17, 0};
			}
		};
	}

	private int[] placeholder(HudModule m) {
		if (!edit && m != HudModule.BUTIN) return null;
		return new int[] {w(tr("autopaterns.hud.placeholder." + m.id)) + 10, 17, 1};
	}

	// ------------------------------------------------------------------ dessin

	private void paint(GuiGraphicsExtractor g, Item it, int x, int y, float al) {
		HudModule m = it.m();
		HudConf c = it.c();
		HudState st = it.st();
		int w = it.d()[0], h = it.d()[1];
		int tx = x + PAD_X, ty = y + PAD_Y;
		if (it.d()[2] == 1) {
			tile(g, c, x, y, w, h, al, 0);
			txt(g, c, Theme.clip(font, tr("autopaterns.hud.placeholder." + m.id), w - 10), tx, ty, Theme.TEXT_DIM, al);
			return;
		}
		switch (m) {
			case STATUT -> paintStatus(g, c, st, x, y, w, h, al);
			case PROGRESSION -> paintProgress(g, c, st, x, y, w, h, al);
			case ECART -> {
				tile(g, c, x, y, w, h, al, 0);
				String[] p = deviationParts(c, st);
				if (p == null) return;
				txt(g, c, p[0], tx, ty, Theme.TEXT_DIM, al);
				int vx = tx + w(p[0]);
				boolean cur = c.shows("current");
				txt(g, c, p[1], vx, ty, cur && st.deviationWarn() ? Theme.WARN : Theme.TEXT, al);
				int rx = vx + w(p[1]);
				txt(g, c, Theme.clip(font, p[2], Math.max(0, x + w - PAD_X - rx)), rx, ty, Theme.TEXT_DIM, al);
			}
			case COMPTEURS -> paintCounters(g, c, st, x, y, w, h, al);
			case INVENTAIRE -> {
				tile(g, c, x, y, w, h, al, 0);
				int free = st.freeSlots();
				int col = free <= 0 ? Theme.BAD : free <= 2 ? Theme.WARN : Theme.GOOD;
				String ft = freeText(free);
				txt(g, c, tr("autopaterns.hud.inventory"), tx, ty, Theme.TEXT_DIM, al);
				txt(g, c, ft, x + w - PAD_X - w(ft), ty, col, al);
				if (c.shows("gauge")) {
					int by = ty + 11, bw = w - 10;
					g.fill(tx, by, tx + bw, by + 2, Theme.alpha(TRACK, al));
					int used = Math.max(0, Math.min(36, 36 - free));
					int fw = Math.round(bw * used / 36f);
					if (fw > 0) g.fill(tx, by, tx + fw, by + 2, Theme.alpha(col, 0.9f * al));
				}
			}
			case BUTIN -> {
				tile(g, c, x, y, w, h, al, 0);
				if (al < 0.5f) return; // les icônes d'items ne se fondent pas : affichées à partir de mi-fondu
				boolean counts = c.shows("counts");
				for (int i = 0; i < st.loot().size(); i++) {
					ItemStack stack = st.loot().get(i);
					int ix = x + 3 + 18 * i, iy = y + 3;
					g.item(stack, ix, iy);
					if (counts) {
						int n = i < st.lootCounts().size() ? st.lootCounts().get(i) : stack.getCount();
						g.itemDecorations(font, stack, ix, iy, shortCount(n));
					}
				}
			}
			case TOUCHES -> paintKeys(g, c, x, y, al);
			case NOTIF -> {
				String t = notifText(st);
				if (t == null) return;
				tile(g, c, x, y, w, h, al, st.toastAccent());
				g.fill(x + 1, y + 2, x + 3, y + h - 2, Theme.alpha(st.toastAccent(), al));
				txt(g, c, Theme.clip(font, t, w - 13), tx + 3, ty, Theme.TEXT, al);
			}
		}
	}

	private void paintStatus(GuiGraphicsExtractor g, HudConf c, HudState st, int x, int y, int w, int h, float al) {
		tile(g, c, x, y, w, h, al, st.accent());
		int tx = x + PAD_X, ty = y + PAD_Y;
		float pulse = 1f;
		if (st.active() && !edit) pulse = 0.55f + 0.45f * (float) (0.5 + 0.5 * Math.sin(now / 1_000_000_000.0 * Math.PI * 1.25));
		Theme.dot(g, tx, ty + 2, Theme.alpha(st.accent(), al * pulse));
		int lx = tx + 7;
		String label = Theme.clip(font, st.label(), Math.max(8, x + w - PAD_X - lx));
		txt(g, c, label, lx, ty, st.accent(), al);
		if (c.shows("pattern") && st.pattern() != null) {
			int nx = lx + w(label) + 6;
			int avail = x + w - PAD_X - nx;
			if (avail > 6) txt(g, c, Theme.clip(font, st.pattern(), avail), nx, ty, Theme.TEXT_DIM, al);
		}
		if (c.shows("detail") && st.detail() != null && !st.detail().isEmpty()) {
			txt(g, c, Theme.clip(font, st.detail(), w - 10), tx, ty + LINE, Theme.TEXT_DIM, al);
		}
	}

	private void paintProgress(GuiGraphicsExtractor g, HudConf c, HudState st, int x, int y, int w, int h, float al) {
		tile(g, c, x, y, w, h, al, 0);
		int tx = x + PAD_X, ty = y + PAD_Y;
		String left = progressLeft(c, st);
		String right = progressRight(c, st);
		boolean line = left != null || right != null;
		int rw = right == null ? 0 : w(right);
		if (left != null) txt(g, c, Theme.clip(font, left, w - 10 - (rw > 0 ? rw + 8 : 0)), tx, ty, Theme.TEXT, al);
		if (right != null) txt(g, c, right, x + w - PAD_X - rw, ty, Theme.TEXT_DIM, al);
		if (c.shows("bar")) {
			int by = ty + (line ? 11 : 0);
			int bw = w - 10;
			float target = st.total() <= 0 ? 0f : Math.min(1f, st.current() / (float) st.total());
			float f = smoothBar(target);
			g.fill(tx, by, tx + bw, by + 3, Theme.alpha(TRACK, al));
			int fw = Math.round(bw * f);
			if (fw > 0) {
				g.fill(tx, by, tx + fw, by + 3, Theme.alpha(st.accent(), al));
				g.fill(tx + fw - 1, by, tx + fw, by + 3, Theme.alpha(0xFFFFFFFF, 0.85f * al)); // tête lumineuse
			}
		}
	}

	/** Barre lissée (animation discrète) ; recalage immédiat quand la progression repart en arrière (nouvelle répétition). */
	private float smoothBar(float target) {
		if (edit) return target;
		if (!barInit || target < barFrac - 0.15f) {
			barFrac = target;
			barInit = true;
		} else {
			barFrac = Theme.approach(barFrac, target, lastNanos, now);
		}
		return barFrac;
	}

	private void paintCounters(GuiGraphicsExtractor g, HudConf c, HudState st, int x, int y, int w, int h, float al) {
		tile(g, c, x, y, w, h, al, 0);
		int tx = x + PAD_X, ty = y + PAD_Y;
		List<String[]> e = counters(c, st);
		int[] cols = counterColumns(e);
		for (int i = 0; i < e.size(); i++) {
			int col = i / 3, row = i % 3;
			int lx = tx;
			for (int k = 0; k < col; k++) lx += cols[2 * k] + VAL_GAP + cols[2 * k + 1] + COL_GAP;
			int vx = lx + cols[2 * col] + VAL_GAP + cols[2 * col + 1];
			String[] p = e.get(i);
			txt(g, c, p[0], lx, ty + row * LINE, Theme.TEXT_DIM, al);
			txt(g, c, p[1], vx - w(p[1]), ty + row * LINE, "0".equals(p[1]) ? Theme.TEXT_DIM : Theme.TEXT, al);
		}
	}

	private void paintKeys(GuiGraphicsExtractor g, HudConf c, int x, int y, float al) {
		Options o = mc.options;
		if (o == null) return;
		int cy = y;
		if (c.shows("move")) {
			key(g, c, 0, o.keyUp, keyName(o.keyUp, "▲"), x + KEY + KEY_GAP, cy, KEY, KEY, al);
			int y2 = cy + KEY + KEY_GAP;
			key(g, c, 1, o.keyLeft, keyName(o.keyLeft, "◀"), x, y2, KEY, KEY, al);
			key(g, c, 2, o.keyDown, keyName(o.keyDown, "▼"), x + KEY + KEY_GAP, y2, KEY, KEY, al);
			key(g, c, 3, o.keyRight, keyName(o.keyRight, "▶"), x + 2 * (KEY + KEY_GAP), y2, KEY, KEY, al);
			cy += 2 * (KEY + KEY_GAP);
		}
		if (c.shows("mouse")) {
			key(g, c, 4, o.keyAttack, tr("autopaterns.hud.key.lmb"), x, cy, 25, MOUSE_H, al);
			key(g, c, 5, o.keyUse, tr("autopaterns.hud.key.rmb"), x + 27, cy, 25, MOUSE_H, al);
			cy += MOUSE_H + 2;
		}
		if (c.shows("space")) {
			float p = press(6, o.keyJump);
			int bw = 3 * KEY + 2 * KEY_GAP;
			Theme.tile(g, x, cy, bw, BAR_H, keyBg(c, p, al), c.background ? Theme.alpha(Theme.BORDER, al) : 0);
			g.fill(x + 12, cy + 2, x + bw - 12, cy + 3, Theme.alpha(Theme.mix(Theme.TEXT, KEY_TEXT_ON, p), al));
		}
	}

	private void key(GuiGraphicsExtractor g, HudConf c, int idx, KeyMapping k, String label, int x, int y, int w, int h, float al) {
		float p = press(idx, k);
		Theme.tile(g, x, y, w, h, keyBg(c, p, al), c.background ? Theme.alpha(Theme.BORDER, al) : 0);
		int col = Theme.alpha(Theme.mix(Theme.TEXT, KEY_TEXT_ON, p), al);
		int lx = x + (w - w(label) + 1) / 2, ly = y + (h - 8) / 2;
		if (c.background || p > 0.5f) Theme.text(g, font, label, lx, ly, col);
		else Theme.textShadow(g, font, label, lx, ly, col);
	}

	private float press(int idx, KeyMapping k) {
		boolean down = k != null && k.isDown();
		keyAnim[idx] = edit ? (down ? 1f : 0f) : Theme.approach(keyAnim[idx], down ? 1f : 0f, lastNanos, now);
		return keyAnim[idx];
	}

	private static int keyBg(HudConf c, float p, float al) {
		int on = Theme.alpha(Theme.CYAN, 0.85f * al);
		if (!c.background) return p < 0.02f ? 0 : Theme.alpha(on, p);
		return Theme.mix(Theme.alpha(Theme.HUD_BG, al), on, p);
	}

	/**
	 * Libellé d'une touche de déplacement : nom de la touche assignée s'il tient en 3 caractères, sinon flèche de
	 * direction (2.x affichait Z/Q/S/D, trompeur pour une touche fléchée ou une autre disposition — bug n°8).
	 */
	private static String keyName(KeyMapping k, String arrow) {
		try {
			if (k == null || k.isUnbound()) return arrow;
			String s = k.getTranslatedKeyMessage().getString();
			return s.length() <= 3 ? s.toUpperCase(Locale.ROOT) : arrow;
		} catch (RuntimeException | LinkageError e) {
			return arrow;
		}
	}

	private void tile(GuiGraphicsExtractor g, HudConf c, int x, int y, int w, int h, float al, int accent) {
		if (!c.background) return;
		Theme.tile(g, x, y, w, h, Theme.alpha(Theme.HUD_BG, al), Theme.alpha(Theme.BORDER, 0.9f * al));
		if (accent != 0 && w > 6) g.fill(x + 2, y, x + w - 2, y + 1, Theme.alpha(accent, 0.75f * al));
	}

	private void txt(GuiGraphicsExtractor g, HudConf c, String s, int x, int y, int color, float al) {
		if (s == null || s.isEmpty() || al < 0.03f) return;
		int col = Theme.alpha(color, al);
		if (c.background) Theme.text(g, font, s, x, y, col);
		else Theme.textShadow(g, font, s, x, y, col);
	}

	// ------------------------------------------------------------------ textes

	private String progressLeft(HudConf c, HudState st) {
		if (c.shows("time")) return Texts.duration(st.current()) + " / " + Texts.duration(st.total());
		if (c.shows("percent")) return percent(st);
		return null;
	}

	private String progressRight(HudConf c, HudState st) {
		String pct = c.shows("time") && c.shows("percent") ? percent(st) : null;
		String rep = c.shows("repetition") && st.loop() ? Texts.tr("autopaterns.hud.rep", st.repetition()) : null;
		if (pct != null && rep != null) return pct + "  " + rep;
		return pct != null ? pct : rep;
	}

	private static String percent(HudState st) {
		int p = st.total() <= 0 ? 0 : (int) Math.min(100L, 100L * st.current() / st.total());
		return p + " %";
	}

	/** {libellé, valeur, suite} de l'écart, ou null si aucune information n'est cochée. */
	private String[] deviationParts(HudConf c, HudState st) {
		boolean cur = c.shows("current"), max = c.shows("max");
		if (!cur && !max) return null;
		if (cur) {
			String tail = max ? tr("autopaterns.hud.deviation.max") + Texts.fmt(st.maxDeviation()) : "";
			return new String[] {tr("autopaterns.hud.deviation"), Texts.fmt(st.deviation()), tail};
		}
		return new String[] {tr("autopaterns.hud.deviation.maxonly"), Texts.fmt(st.maxDeviation()), ""};
	}

	/** Compteurs cochés {libellé, valeur} dans l'ordre 2.x, puis la pêche (prises, ratés) si elle a tourné. */
	private List<String[]> counters(HudConf c, HudState st) {
		List<String[]> e = new ArrayList<>(7);
		if (c.shows("checkpoints")) e.add(new String[] {tr("autopaterns.hud.counter.checkpoints"), Integer.toString(st.checkpoints())});
		if (c.shows("repairs")) e.add(new String[] {tr("autopaterns.hud.counter.repairs"), Integer.toString(st.repairs())});
		if (c.shows("refills")) e.add(new String[] {tr("autopaterns.hud.counter.refills"), Integer.toString(st.refills())});
		if (c.shows("sales")) e.add(new String[] {tr("autopaterns.hud.counter.sales"), Integer.toString(st.sales())});
		if (c.shows("trash")) e.add(new String[] {tr("autopaterns.hud.counter.trash"), Integer.toString(st.trash())});
		if (c.shows("fishing") && st.fishingShown()) {
			e.add(new String[] {tr("autopaterns.hud.counter.caught"), Integer.toString(st.fishCaught())});
			e.add(new String[] {tr("autopaterns.hud.counter.missed"), Integer.toString(st.fishMissed())});
		}
		return e;
	}

	/** Pour chaque colonne de 3 lignes : {largeur max des libellés, largeur max des valeurs}. */
	private int[] counterColumns(List<String[]> e) {
		int ncol = Math.max(1, (e.size() + 2) / 3);
		int[] cols = new int[2 * ncol];
		for (int i = 0; i < e.size(); i++) {
			int col = i / 3;
			cols[2 * col] = Math.max(cols[2 * col], w(e.get(i)[0]));
			cols[2 * col + 1] = Math.max(cols[2 * col + 1], w(e.get(i)[1]));
		}
		return cols;
	}

	private String freeText(int n) {
		return Texts.tr(n > 1 ? "autopaterns.hud.free.many" : "autopaterns.hud.free.one", n);
	}

	private String notifText(HudState st) {
		if (st.toast() != null) return st.toast();
		return edit ? tr("autopaterns.hud.notif.placeholder") : null;
	}

	private static int keysHeight(HudConf c) {
		int h = 0;
		if (c.shows("move")) h += 2 * (KEY + KEY_GAP);
		if (c.shows("mouse")) h += MOUSE_H + 2;
		if (c.shows("space")) h += BAR_H + 2;
		return h - 2;
	}

	/**
	 * Quantité abrégée du butin : {@code 1,5k} / {@code 12k}. Format français, comme les écarts
	 * (2.x mélangeait {@code 0,004} et {@code 1.5k} — bug n°9).
	 */
	static String shortCount(int n) {
		if (n >= 10_000) return (n / 1000) + "k";
		if (n >= 1000) return String.format(Locale.FRANCE, "%.1fk", n / 1000.0);
		return Integer.toString(n);
	}

	private int w(String s) {
		return s == null ? 0 : font.width(s);
	}

	/** Traduction mise en cache (textes fixes dessinés à chaque image) ; vidée au changement de langue. */
	private String tr(String key) {
		Language lang = Language.getInstance();
		if (lang != trLang) {
			trCache.clear();
			trLang = lang;
		}
		return trCache.computeIfAbsent(key, k -> Texts.tr(k));
	}
}
