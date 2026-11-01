package fr.autopaterns.fishing;

/**
 * Lecture de la jauge de pêche dans une image ARGB (ligne 0 = haut). Reprise exacte de la 2.x :
 * classification HSV de chaque pixel (seuils identiques), recherche d'une bande verticale riche en cyan
 * bordée de « bois » à droite, zone verte (groupe de lignes vertes) et poisson (barycentre des pixels sombres).
 * Classe pure (aucun accès au jeu) : utilisable sur n'importe quel thread.
 */
final class FishVision {
	static final byte OTHER = 0, CYAN = 1, BLUE = 2, GREEN = 3, WOOD = 4, DARK = 5;

	private FishVision() {}

	/** Classe de couleur d'un pixel ARGB (alpha ignoré). L'ordre des tests compte. */
	static byte classify(int c) {
		int r = (c >> 16) & 255;
		int g = (c >> 8) & 255;
		int b = c & 255;
		int max = Math.max(r, Math.max(g, b));
		int min = Math.min(r, Math.min(g, b));
		float v = max / 255f;
		if (v < 0.3f) return DARK;
		float s = max == 0 ? 0f : (max - min) / (float) max;
		int d = max - min;
		float h;
		if (d == 0) h = 0f;
		else if (max == r) h = 60f * (((g - b) / (float) d) % 6f);
		else if (max == g) h = 60f * ((b - r) / (float) d + 2f);
		else h = 60f * ((r - g) / (float) d + 4f);
		if (h < 0f) h += 360f;
		if (h >= 165f && h <= 200f && s > 0.45f && v > 0.6f) return CYAN;
		if (h >= 75f && h <= 150f && s > 0.35f && v > 0.45f) return GREEN;
		if (h > 200f && h <= 235f && s > 0.4f && v > 0.45f) return BLUE;
		if (h >= 8f && h <= 40f && s > 0.35f && v > 0.35f) return WOOD;
		return OTHER;
	}

	private static boolean gaugeColor(byte c) {
		return c == CYAN || c == BLUE || c == GREEN;
	}

	/**
	 * Analyse toute l'image. {@code refW}/{@code refH} : taille de l'écran exprimée en pixels de l'image
	 * (sert aux seuils de hauteur minimale et de largeur maximale de la jauge).
	 */
	static Result analyzePart(int[] px, int w, int h, int refW, int refH) {
		return analyzeRegion(px, w, h, 0, w, 0, h, refW, refH);
	}

	static Result analyzeRegion(int[] px, int stride, int h, int x0, int x1, int y0, int y1, int refW, int refH) {
		int rw = x1 - x0;
		int rh = y1 - y0;
		if (rw < 4 || rh < 10) return Result.NONE;
		if (px == null || px.length < (long) (y1 - 1) * stride + x1 || y1 > h) return Result.NONE;
		byte[] cls = new byte[rh * rw];
		for (int y = 0; y < rh; y++) {
			int base = (y0 + y) * stride + x0;
			int o = y * rw;
			for (int x = 0; x < rw; x++) cls[o + x] = classify(px[base + x]);
		}
		int minCount = Math.max(8, (int) (refH * 0.06));
		int maxWidth = Math.max(4, (int) (refW * 0.06));
		boolean[] colOk = new boolean[rw];
		for (int x = 0; x < rw; x++) {
			int cnt = 0;
			int cyan = 0;
			for (int y = 0; y < rh; y++) {
				byte c = cls[y * rw + x];
				if (gaugeColor(c)) {
					cnt++;
					if (c == CYAN) cyan++;
				}
			}
			colOk[x] = cnt >= minCount && cyan * 4 >= cnt;
		}
		Result best = Result.NONE;
		int x = 0;
		while (x < rw) {
			if (!colOk[x]) {
				x++;
				continue;
			}
			int start = x;
			while (x < rw && colOk[x]) x++;
			int end = x;
			int width = end - start;
			if (width >= 3 && width <= maxWidth) {
				Result g = gauge(cls, rw, rh, start, end, minCount);
				if (g.found() && (!best.found() || g.bottom() - g.top() > best.bottom() - best.top())) best = g;
			}
		}
		if (!best.found()) return best;
		return new Result(true, best.x0() + x0, best.x1() + x0, best.top() + y0, best.bottom() + y0,
			best.zoneTop() < 0 ? -1 : best.zoneTop() + y0,
			best.zoneBottom() < 0 ? -1 : best.zoneBottom() + y0,
			best.fish() + y0);
	}

	/** Jauge dans la bande de colonnes [c0, c1) ; {@code w}, {@code h} = taille de la grille de classes. */
	static Result gauge(byte[] cls, int w, int h, int c0, int c1, int minLen) {
		int bw = c1 - c0;
		// 1. lignes valides : cadre « bois » à droite et au moins 30 % de la bande colorés
		boolean[] rowOk = new boolean[h];
		int woodEnd = Math.min(w, c1 + 4);
		for (int y = 0; y < h; y++) {
			int o = y * w;
			int wood = 0;
			for (int x = c1; x < woodEnd; x++) if (cls[o + x] == WOOD) wood++;
			int col = 0;
			for (int x = c0; x < c1; x++) if (gaugeColor(cls[o + x])) col++;
			rowOk[y] = wood >= 2 && col * 10 >= bw * 3;
		}
		// 2. plus longue suite verticale, avec tolérance de trous
		int gapMax = Math.max(3, minLen / 2);
		int bestS = -1;
		int bestE = -1;
		int y = 0;
		while (y < h) {
			if (!rowOk[y]) {
				y++;
				continue;
			}
			int s = y;
			int gap = 0;
			int last = y;
			while (y < h && gap <= gapMax) {
				if (rowOk[y]) {
					last = y;
					gap = 0;
				} else {
					gap++;
				}
				y++;
			}
			if (last - s > bestE - bestS) {
				bestS = s;
				bestE = last;
			}
		}
		if (bestS < 0 || bestE - bestS < minLen) return Result.NONE;
		int top = bestS;
		int bottom = bestE;
		int height = bottom - top;
		// 3. zone verte : groupe de lignes vertes (≥ 60 % de la bande), le plus fourni en lignes
		int tol = Math.max(3, height * 11 / 100);
		int zTop = -1;
		int zBot = -1;
		int bestLen = 0;
		int cs = -1;
		int ce = -1;
		int cl = 0;
		for (int yy = top; yy <= bottom + 1; yy++) {
			boolean green = false;
			if (yy <= bottom) {
				int n = 0;
				int o = yy * w;
				for (int x = c0; x < c1; x++) if (cls[o + x] == GREEN) n++;
				green = n * 10 >= bw * 6;
			}
			if (green) {
				if (cs >= 0 && yy - ce <= tol) {
					ce = yy;
					cl++;
				} else {
					if (cl > bestLen) {
						bestLen = cl;
						zTop = cs;
						zBot = ce;
					}
					cs = yy;
					ce = yy;
					cl = 1;
				}
			}
		}
		if (cl > bestLen) {
			zTop = cs;
			zBot = ce;
		}
		// 4. poisson : barycentre vertical des pixels sombres (bords et bas de la jauge exclus)
		int yEnd = bottom - Math.max(2, height / 20);
		long sum = 0L;
		int n = 0;
		for (int yy = top; yy <= yEnd; yy++) {
			int o = yy * w;
			for (int x = c0 + 1; x <= c1 - 2; x++) {
				if (cls[o + x] == DARK) {
					sum += yy;
					n++;
				}
			}
		}
		double fish = n == 0 ? Double.NaN : sum / (double) n + height * 0.035;
		return new Result(true, c0, c1, top, bottom, zTop, zBot, fish);
	}

	/**
	 * Résultat d'analyse. {@code x1} exclusif, {@code bottom} inclusif ; zone à -1 si absente ; {@code fish} NaN si
	 * le poisson n'est pas vu. Axe y vers le bas.
	 */
	record Result(boolean found, int x0, int x1, int top, int bottom, int zoneTop, int zoneBottom, double fish) {
		static final Result NONE = new Result(false, 0, 0, 0, 0, -1, -1, Double.NaN);

		boolean hasZone() {
			return zoneTop >= 0;
		}

		boolean hasFish() {
			return !Double.isNaN(fish);
		}

		double zoneCenter() {
			return (zoneTop + zoneBottom) / 2.0;
		}

		/** Position normalisée dans la jauge : 0 en haut, 1 en bas. */
		double norm(double y) {
			return bottom > top ? (y - top) / (double) (bottom - top) : 0;
		}

		/** Coordonnées image → coordonnées écran (framebuffer). */
		Result scaled(int s, int ox, int oy) {
			if (!found) return this;
			return new Result(true, x0 * s + ox, x1 * s + ox, top * s + oy, bottom * s + oy,
				zoneTop < 0 ? -1 : zoneTop * s + oy,
				zoneBottom < 0 ? -1 : zoneBottom * s + oy,
				Double.isNaN(fish) ? Double.NaN : fish * s + oy);
		}
	}
}
