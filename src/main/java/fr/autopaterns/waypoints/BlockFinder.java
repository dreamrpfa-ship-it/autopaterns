package fr.autopaterns.waypoints;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import fr.autopaterns.core.Notifier;
import fr.autopaterns.core.Texts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Surbrillance de blocs (2.x : {@code BlockFinder}). Recherche incrémentale sur le thread client, avec un budget de
 * blocs examinés par tick (aucun thread), autour du joueur ; au plus {@value #MAX_RESULTS} résultats, triés par
 * distance au centre du scan. Sert aussi aux scans « Identiques autour » des patterns de waypoints.
 */
public final class BlockFinder {
	public static final int[] RADII = {16, 24, 32, 48, 64};
	static final int MAX_RESULTS = 4000;
	/** Blocs examinés au plus par tick, toutes tâches confondues (2.x). */
	static final int BUDGET_PER_TICK = 40000;
	/** Part maximale du scan principal (2.x : la moitié). */
	private static final int MAIN_BUDGET = BUDGET_PER_TICK / 2;
	/** Résultats revérifiés par tick (un bloc miné disparaît de la surbrillance sans attendre le scan suivant). */
	private static final int VERIFY_PER_TICK = 1000;
	/** Pause entre deux scans principaux (ticks). */
	private static final int RESCAN_DELAY = 20;
	/** Hauteurs examinées (2.x : bornes fixes ; hors de ces bornes le monde ne contient que du vide). */
	private static final int MIN_Y = -64, MAX_Y = 320;

	/** Un bloc trouvé, avec la boîte englobante de sa forme (relative au bloc) pour une surbrillance précise. */
	public static final class Hit {
		public final int x, y, z;
		float x0 = 0f, y0 = 0f, z0 = 0f, x1 = 1f, y1 = 1f, z1 = 1f;

		Hit(int x, int y, int z) {
			this.x = x;
			this.y = y;
			this.z = z;
		}
	}

	/** Rappel d'un bloc correspondant au test. */
	interface Match {
		void found(int x, int y, int z);
	}

	/** Parcours d'un cube (2r+1)³ : x le plus rapide, puis z, puis y de bas en haut (ordre 2.x). */
	static final class Job {
		final ClientLevel level;
		final int cx, cy, cz, r, d;
		final long total;
		long i;
		final Predicate<BlockState> test;
		final Match match;
		final Runnable done;

		Job(ClientLevel level, int cx, int cy, int cz, int r, Predicate<BlockState> test, Match match, Runnable done) {
			this.level = level;
			this.cx = cx;
			this.cy = cy;
			this.cz = cz;
			this.r = r;
			this.d = 2 * r + 1;
			this.total = (long) d * d * d;
			this.test = test;
			this.match = match;
			this.done = done;
		}
	}

	public final Set<String> targets = new LinkedHashSet<>();
	public boolean enabled = true;
	public int radius = 32;
	public int colorIndex = 5;
	public boolean variants = true;
	public boolean throughWalls = true;

	private List<Hit> results = new ArrayList<>();
	private String resultsDim = "";
	private String resultsCtx = "";
	private static final Map<Block, String[]> KEY_CACHE = new HashMap<>();
	private final List<Job> jobs = new ArrayList<>();
	private Job finderJob;
	private long tick;
	private long lastFinish = -1000;
	private int verifyCursor;
	private int version;
	private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

	// ------------------------------------------------------------------ clés de blocs

	/**
	 * Clé d'un bloc : clé de traduction sans « block. » (ex. {@code minecraft.deepslate_diamond_ore}) ; avec
	 * {@code variants}, « .deepslate_ » est retiré ({@code minecraft.diamond_ore}). Identique à la 2.x.
	 */
	public static String keyOf(BlockState state, boolean variants) {
		if (state == null) return null;
		Block block = state.getBlock();
		if (block == null) return null;
		String[] k = KEY_CACHE.get(block);
		if (k == null) {
			String tk = descriptionId(block);
			String base = tk.startsWith("block.") ? tk.substring(6) : tk;
			k = new String[] {base, base.replace(".deepslate_", ".")};
			KEY_CACHE.put(block, k);
		}
		return variants ? k[1] : k[0];
	}

	/** Clé de traduction du bloc (API Mojang {@code Block#getDescriptionId}). */
	private static String descriptionId(Block block) {
		String tk = block.getDescriptionId();
		return tk == null ? String.valueOf(block) : tk;
	}

	/** « minecraft.diamond_ore » → « Diamond ore ». */
	public static String display(String key) {
		if (key == null) return "";
		String s = key.substring(key.lastIndexOf('.') + 1).replace('_', ' ');
		if (s.isEmpty()) return key;
		return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
	}

	public int color() {
		return WaypointPattern.COLORS[Math.floorMod(colorIndex, WaypointPattern.COLORS.length)];
	}

	public String colorName() {
		return WaypointPattern.colorName(colorIndex);
	}

	// ------------------------------------------------------------------ sélection

	static BlockState aimedState(Minecraft mc) {
		if (mc.level == null) return null;
		if (mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
			return mc.level.getBlockState(bhr.getBlockPos());
		}
		return null;
	}

	/** Touche N : ajoute / retire le bloc visé ; sans bloc visé, active / désactive la surbrillance. */
	public void toggleAimed(Minecraft mc, Waypoints state) {
		BlockState s = aimedState(mc);
		if (s == null || s.isAir()) {
			enabled = !enabled;
			Notifier.hud(Texts.tr(enabled ? "autopaterns.waypoints.finder.on" : "autopaterns.waypoints.finder.off"));
		} else {
			String k = keyOf(s, variants);
			if (targets.remove(k)) {
				Notifier.hud(Texts.tr("autopaterns.waypoints.finder.removed", display(k)));
			} else {
				targets.add(k);
				enabled = true;
				Notifier.hud(Texts.tr("autopaterns.waypoints.finder.added", display(k), radius));
			}
		}
		restart();
		state.markDirty();
	}

	/** Bouton « + Ajouter le bloc visé ». */
	public String addAimed(Minecraft mc, Waypoints state) {
		BlockState s = aimedState(mc);
		if (s == null || s.isAir()) return Texts.tr("autopaterns.waypoints.finder.aim_first");
		String k = keyOf(s, variants);
		targets.add(k);
		enabled = true;
		restart();
		state.markDirty();
		return Texts.tr("autopaterns.waypoints.finder.added_short", display(k));
	}

	/** Option « Variantes deepslate » : en l'activant, les cibles deepslate sont regroupées (2.x). */
	public void setVariants(boolean v) {
		variants = v;
		if (v) {
			List<String> conv = new ArrayList<>(targets.size());
			for (String t : targets) conv.add(t.replace(".deepslate_", "."));
			targets.clear();
			targets.addAll(conv);
		}
		restart();
	}

	/** Abandonne le scan en cours et relance immédiatement. */
	public void restart() {
		finderJob = null;
		lastFinish = -1000;
		if (targets.isEmpty()) setResults(new ArrayList<>());
	}

	private void setResults(List<Hit> r) {
		results = r;
		verifyCursor = 0;
		version++;
	}

	/** Résultats du monde courant (liste vide si désactivé, sans cible ou d'un autre monde). */
	public List<Hit> results(String ctx, String dim) {
		if (!enabled || targets.isEmpty() || !dim.equals(resultsDim) || !ctx.equals(resultsCtx)) return List.of();
		return results;
	}

	public int resultCount() {
		return enabled ? results.size() : 0;
	}

	/** Change à chaque modification des résultats (cache du rendu). */
	int version() {
		return version;
	}

	void submit(Job job) {
		jobs.add(job);
	}

	/** Annule tous les scans (arrêt du mod, changement de monde). */
	void cancelAll() {
		jobs.clear();
		finderJob = null;
	}

	// ------------------------------------------------------------------ fichier (ligne F)

	void write(StringBuilder sb) {
		sb.append("F\t").append(enabled ? "1" : "0")
			.append('\t').append(radius)
			.append('\t').append(colorIndex)
			.append('\t').append(variants ? "1" : "0")
			.append('\t').append(throughWalls ? "1" : "0")
			.append('\t').append(WaypointIO.clean(String.join(",", targets)))
			.append('\n');
	}

	/** Lecture de la ligne F. Le rayon est borné à 1..64 (2.x : non validé, un rayon énorme figeait le scan). */
	void read(String[] f) {
		if (f.length < 7) return;
		enabled = "1".equals(f[1]);
		try {
			radius = Integer.parseInt(f[2].trim());
		} catch (RuntimeException e) {
			radius = 32;
		}
		radius = Math.max(1, Math.min(RADII[RADII.length - 1], radius));
		try {
			colorIndex = Integer.parseInt(f[3].trim());
		} catch (RuntimeException e) {
			colorIndex = 5;
		}
		variants = "1".equals(f[4]);
		throughWalls = "1".equals(f[5]);
		targets.clear();
		for (String t : f[6].split(",")) {
			if (!t.isBlank()) targets.add(t.trim());
		}
		restart();
	}

	// ------------------------------------------------------------------ scan

	/** END tick : scan principal, scans secondaires et revérification des résultats. */
	void onEndTick(Minecraft mc, Waypoints state) {
		tick++;
		ClientLevel level = mc.level;
		LocalPlayer player = mc.player;
		if (level == null || player == null) {
			jobs.clear();
			finderJob = null;
			if (!results.isEmpty()) setResults(new ArrayList<>());
			return;
		}
		// Correction 2.x : un scan commencé dans un autre monde / une autre dimension est abandonné.
		if (finderJob != null && finderJob.level != level) finderJob = null;
		jobs.removeIf(j -> j.level != level);

		if (finderJob == null && enabled && !targets.isEmpty() && tick - lastFinish >= RESCAN_DELAY) {
			startMainScan(level, player, state);
		}
		int budget = BUDGET_PER_TICK;
		if (finderJob != null) {
			Job main = finderJob;
			int left = run(level, main, MAIN_BUDGET);
			// Correction 2.x : les scans secondaires reçoivent le reste du budget total (et non le reliquat
			// des 20 000 blocs du scan principal, souvent nul : ils n'avançaient qu'entre deux scans).
			budget -= MAIN_BUDGET - left;
			if (main.i >= main.total && finderJob == main) main.done.run();
		}
		Iterator<Job> it = jobs.iterator();
		while (it.hasNext() && budget > 0) {
			Job j = it.next();
			budget = run(level, j, budget);
			if (j.i >= j.total) {
				it.remove();
				if (j.done != null) j.done.run();
			}
		}
		verify(level);
	}

	private void startMainScan(ClientLevel level, LocalPlayer player, Waypoints state) {
		Set<String> tset = new LinkedHashSet<>(targets);
		boolean v = variants;
		int px = (int) Math.floor(player.getX());
		int py = (int) Math.floor(player.getY());
		int pz = (int) Math.floor(player.getZ());
		String ctx = state.context(Minecraft.getInstance());
		String dim = Waypoints.dimension(level);
		int r = radius;
		NearestHeap heap = new NearestHeap(MAX_RESULTS);
		int d = 2 * r + 1;
		Job[] self = new Job[1];
		self[0] = new Job(level, px, py, pz, r,
			s -> !s.isAir() && tset.contains(keyOf(s, v)),
			(x, y, z) -> {
				long dx = x - px, dy = y - py, dz = z - pz;
				long d2 = dx * dx + dy * dy + dz * dz;
				// index de parcours : départage les égalités comme le tri stable de la 2.x
				long n = (long) (y - py + r) * d * d + (long) (z - pz + r) * d + (x - px + r);
				heap.offer((d2 << 32) | n);
			},
			() -> {
				long[] keys = heap.sorted();
				List<Hit> out = new ArrayList<>(keys.length);
				BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
				for (long k : keys) {
					long n = k & 0xFFFFFFFFL;
					int x = (int) (n % d) - r + px;
					int z = (int) ((n / d) % d) - r + pz;
					int y = (int) (n / ((long) d * d)) - r + py;
					Hit h = new Hit(x, y, z);
					shape(level, mp.set(x, y, z), h);
					out.add(h);
				}
				setResults(out);
				resultsDim = dim;
				resultsCtx = ctx;
				lastFinish = tick;
				if (finderJob == self[0]) finderJob = null;
			});
		finderJob = self[0];
	}

	/** Examine au plus {@code budget} blocs du job ; renvoie le budget restant. */
	private int run(ClientLevel level, Job job, int budget) {
		int d = job.d;
		long dd = (long) d * d;
		while (budget > 0 && job.i < job.total) {
			long n = job.i++;
			budget--;
			int x = (int) (n % d) - job.r + job.cx;
			int z = (int) ((n / d) % d) - job.r + job.cz;
			int y = (int) (n / dd) - job.r + job.cy;
			if (y < MIN_Y || y > MAX_Y) continue; // compte dans le budget, comme en 2.x
			BlockState s = level.getBlockState(cursor.set(x, y, z));
			if (s != null && job.test.test(s)) job.match.found(x, y, z);
		}
		return budget;
	}

	/**
	 * Revérifie une tranche des résultats à chaque tick : un bloc miné ou remplacé n'est plus surligné, et la boîte
	 * suit la forme réelle du bloc (surbrillance précise). Les cibles courantes font foi.
	 */
	private void verify(ClientLevel level) {
		if (results.isEmpty() || targets.isEmpty()) return;
		if (!resultsDim.equals(Waypoints.dimension(level))) return;
		boolean v = variants;
		int n = Math.min(VERIFY_PER_TICK, results.size());
		for (int k = 0; k < n && !results.isEmpty(); k++) {
			if (verifyCursor >= results.size()) verifyCursor = 0;
			Hit h = results.get(verifyCursor);
			BlockState s = level.getBlockState(cursor.set(h.x, h.y, h.z));
			if (s == null || s.isAir() || !targets.contains(keyOf(s, v))) {
				results.remove(verifyCursor); // liste propre au module, jamais partagée hors du thread client
				version++;
			} else {
				shape(level, cursor, h);
				verifyCursor++;
			}
		}
	}

	/** Boîte englobante de la forme du bloc (cube entier si forme vide). */
	private static void shape(ClientLevel level, BlockPos pos, Hit h) {
		BlockState s = level.getBlockState(pos);
		VoxelShape shape = s == null ? null : s.getShape(level, pos);
		if (shape == null || shape.isEmpty()) {
			h.x0 = h.y0 = h.z0 = 0f;
			h.x1 = h.y1 = h.z1 = 1f;
			return;
		}
		AABB b = shape.bounds();
		h.x0 = (float) Math.max(-0.5, b.minX);
		h.y0 = (float) Math.max(-0.5, b.minY);
		h.z0 = (float) Math.max(-0.5, b.minZ);
		h.x1 = (float) Math.min(1.5, b.maxX);
		h.y1 = (float) Math.min(1.5, b.maxY);
		h.z1 = (float) Math.min(1.5, b.maxZ);
	}

	/**
	 * Tas borné des {@code cap} plus petites clés (distance² &lt;&lt; 32 | index). Donne exactement les mêmes
	 * résultats que le tri complet + troncature de la 2.x, sans conserver tous les blocs trouvés en mémoire
	 * (un bloc très courant pouvait produire des millions d'entrées).
	 */
	static final class NearestHeap {
		private final long[] a;
		private int size;

		NearestHeap(int cap) {
			a = new long[cap];
		}

		void offer(long k) {
			if (size < a.length) {
				int i = size++;
				a[i] = k;
				while (i > 0) { // remontée (tas max)
					int p = (i - 1) >>> 1;
					if (a[p] >= a[i]) break;
					long t = a[p];
					a[p] = a[i];
					a[i] = t;
					i = p;
				}
			} else if (k < a[0]) {
				a[0] = k;
				int i = 0;
				while (true) { // descente
					int l = 2 * i + 1, rr = l + 1, m = i;
					if (l < size && a[l] > a[m]) m = l;
					if (rr < size && a[rr] > a[m]) m = rr;
					if (m == i) break;
					long t = a[m];
					a[m] = a[i];
					a[i] = t;
					i = m;
				}
			}
		}

		void clear() {
			size = 0;
		}

		long[] sorted() {
			long[] out = Arrays.copyOf(a, size);
			Arrays.sort(out);
			return out;
		}
	}
}
