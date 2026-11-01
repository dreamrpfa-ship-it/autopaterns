package fr.autopaterns.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Charte graphique 3.0 : sombre, futuriste et minimaliste, accents cyan / bleu électrique,
 * bordures fines, transparence maîtrisée. Toutes les couleurs sont en ARGB avec alpha.
 * Ces couleurs sont aussi utilisées par le HUD.
 */
public final class Theme {
	private Theme() {}

	// Fonds
	public static final int SCRIM = 0xB0050810;          // voile derrière les menus
	public static final int PANEL = 0xEE0A0F1A;          // panneau principal
	public static final int PANEL_HEADER = 0xF00D1524;   // bandeau de titre
	public static final int SURFACE = 0xCC111A2A;        // boutons, cartes
	public static final int SURFACE_HOVER = 0xE6172539;
	public static final int SURFACE_ACTIVE = 0xF01B2D47;
	public static final int SURFACE_DISABLED = 0x80101722;
	public static final int HUD_BG = 0xB80A101B;         // fond des modules du HUD

	// Bordures
	public static final int BORDER = 0x4022D3EE;         // cyan discret
	public static final int BORDER_HOVER = 0xB022D3EE;
	public static final int BORDER_FOCUS = 0xFF22D3EE;
	public static final int DIVIDER = 0x2696B4D6;

	// Accents
	public static final int CYAN = 0xFF22D3EE;
	public static final int BLUE = 0xFF3B82F6;
	public static final int VIOLET = 0xFF9D8CF8;

	// Texte
	public static final int TEXT = 0xFFE6F1FF;
	public static final int TEXT_DIM = 0xFF8A9BB4;
	public static final int TEXT_MUTED = 0xFF56657C;

	// États
	public static final int GOOD = 0xFF34D399;
	public static final int WARN = 0xFFF5A524;
	public static final int BAD = 0xFFF0506E;
	public static final int ON = GOOD;
	public static final int OFF = 0xFF56657C;

	/** Multiplie l'alpha d'une couleur ARGB par {@code f} ∈ [0,1]. */
	public static int alpha(int argb, float f) {
		int a = (argb >>> 24) & 0xFF;
		int na = Math.max(0, Math.min(255, Math.round(a * Math.max(0f, Math.min(1f, f)))));
		return (na << 24) | (argb & 0x00FFFFFF);
	}

	/** Mélange linéaire de deux couleurs ARGB. */
	public static int mix(int c1, int c2, float t) {
		t = Math.max(0f, Math.min(1f, t));
		int a = lerp((c1 >>> 24) & 0xFF, (c2 >>> 24) & 0xFF, t);
		int r = lerp((c1 >> 16) & 0xFF, (c2 >> 16) & 0xFF, t);
		int g = lerp((c1 >> 8) & 0xFF, (c2 >> 8) & 0xFF, t);
		int b = lerp(c1 & 0xFF, c2 & 0xFF, t);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	private static int lerp(int a, int b, float t) {
		return Math.round(a + (b - a) * t);
	}

	/** Courbe d'animation douce (ease-out cubique). */
	public static float ease(float t) {
		t = Math.max(0f, Math.min(1f, t));
		float u = 1f - t;
		return 1f - u * u * u;
	}

	/** Rapproche {@code cur} de {@code target} (animation indépendante du framerate, ~120 ms). */
	public static float approach(float cur, float target, long lastNanos, long nowNanos) {
		float dt = Math.min(0.1f, (nowNanos - lastNanos) / 1_000_000_000f);
		float k = 1f - (float) Math.exp(-dt * 18f);
		return cur + (target - cur) * k;
	}

	/** Cadre fin de 1 px. */
	public static void frame(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
		if (w <= 0 || h <= 0) return;
		g.fill(x, y, x + w, y + 1, color);
		g.fill(x, y + h - 1, x + w, y + h, color);
		g.fill(x, y + 1, x + 1, y + h - 1, color);
		g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
	}

	/** Tuile à coins biseautés (fond) avec cadre optionnel. */
	public static void tile(GuiGraphicsExtractor g, int x, int y, int w, int h, int bg, int border) {
		if (w < 3 || h < 3) return;
		if (bg != 0) {
			g.fill(x + 1, y, x + w - 1, y + h, bg);
			g.fill(x, y + 1, x + 1, y + h - 1, bg);
			g.fill(x + w - 1, y + 1, x + w, y + h - 1, bg);
		}
		if (border != 0) {
			g.fill(x + 1, y, x + w - 1, y + 1, border);
			g.fill(x + 1, y + h - 1, x + w - 1, y + h, border);
			g.fill(x, y + 1, x + 1, y + h - 1, border);
			g.fill(x + w - 1, y + 1, x + w, y + h - 1, border);
		}
	}

	/** Pastille d'état ON/OFF (point 4×4). */
	public static void dot(GuiGraphicsExtractor g, int x, int y, int color) {
		g.fill(x + 1, y, x + 3, y + 4, color);
		g.fill(x, y + 1, x + 4, y + 3, color);
	}

	/** Texte tronqué avec « … » pour tenir dans {@code maxW} pixels. */
	public static String clip(Font font, String s, int maxW) {
		if (s == null) return "";
		if (font.width(s) <= maxW) return s;
		String t = s;
		while (t.length() > 1 && font.width(t + "…") > maxW) t = t.substring(0, t.length() - 1);
		return t + "…";
	}

	public static void text(GuiGraphicsExtractor g, Font font, String s, int x, int y, int color) {
		g.text(font, s, x, y, color, false);
	}

	public static void textShadow(GuiGraphicsExtractor g, Font font, String s, int x, int y, int color) {
		g.text(font, s, x, y, color, true);
	}

	public static void centered(GuiGraphicsExtractor g, Font font, String s, int cx, int y, int color) {
		g.text(font, s, cx - font.width(s) / 2, y, color, false);
	}
}
