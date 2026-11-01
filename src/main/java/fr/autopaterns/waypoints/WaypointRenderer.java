package fr.autopaterns.waypoints;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import fr.autopaterns.core.Log;
import fr.autopaterns.core.Texts;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * Affichage des waypoints et de la surbrillance (2.x : {@code WaypointRenderer}) :
 * <ul>
 *   <li>boîtes translucides dans le monde (au plus {@value #MAX_WORLD}) ;</li>
 *   <li>cadres 2D « à travers les murs » projetés sur le HUD, avec libellé de distance (au plus {@value #MAX_HUD}) ;</li>
 *   <li>lignes d'état en haut à droite (enregistrement en cours, nombre de blocs en surbrillance).</li>
 * </ul>
 * Corrections 2.x : la vraie position de la caméra est utilisée (la 2.x lisait (0,0,0) : boîtes mal placées,
 * distances et projection fausses) ; les sommets sont calculés relativement à la caméra en double précision
 * (pas de tremblement loin de l'origine) ; une erreur de rendu ne coupe plus l'affichage jusqu'au redémarrage ;
 * le coût par image est borné (sélection des plus proches recalculée au plus toutes les 100 ms).
 */
public final class WaypointRenderer {
	static final int MAX_HUD = 600;
	static final int MAX_WORLD = 2500;
	/** Candidats examinés au plus par image pour les cadres HUD (les plus proches d'abord). */
	private static final int HUD_SCAN_LIMIT = 8000;
	/** Marge de candidats monde au-delà de MAX_WORLD (filtre de distance exact appliqué à chaque image). */
	private static final int WORLD_POOL = MAX_WORLD + 512;
	private static final long REBUILD_NANOS = 100_000_000L;
	private static final long CAPTURE_MAX_AGE_NANOS = 250_000_000L;
	private static final float EPS = 0.004f;
	private static final int STATUS_RED = 0xFFFF5555;

	/** Un candidat à l'affichage (pattern ou résultat de surbrillance). */
	private static final class Mark {
		int x, y, z;
		float x0, y0, z0, x1, y1, z1;
		int color;
		boolean finder;
		double maxD;
		int labelMode;
		char type;
		String name;
	}

	private final WaypointsModule module;

	// matrices de la dernière image rendue
	private final Matrix4f viewProj = new Matrix4f();
	private final Vector4f vec = new Vector4f();
	private boolean hasViewProj;
	private double camX, camY, camZ;
	private long capturedAt;
	private boolean matrixWarned;

	// candidats (sélection des plus proches)
	private final List<Mark> worldPool = new ArrayList<>();
	private final List<Mark> hudPool = new ArrayList<>();
	private Mark[] world = new Mark[0];
	private int worldCount;
	private Mark[] hud = new Mark[0];
	private int hudCount;
	private final BlockFinder.NearestHeap worldHeap = new BlockFinder.NearestHeap(WORLD_POOL);
	private final BlockFinder.NearestHeap hudHeap = new BlockFinder.NearestHeap(HUD_SCAN_LIMIT);
	private final List<Object> src = new ArrayList<>();
	private final List<WaypointPattern> srcPattern = new ArrayList<>();
	private long builtAt;
	private int builtVersion = -1;
	private int builtFinderVersion = -1;
	private String builtCtx = "";
	private String builtDim = "";

	// géométrie monde (double tampon : le rappel de dessin est exécuté plus tard dans la même image)
	private final float[][] boxBuf = {new float[0], new float[0]};
	private final int[][] colBuf = {new int[0], new int[0]};
	private int bufIdx;

	// cadres HUD acceptés
	private final int[] fx0 = new int[MAX_HUD], fy0 = new int[MAX_HUD], fx1 = new int[MAX_HUD], fy1 = new int[MAX_HUD];
	private final int[] fcx = new int[MAX_HUD], fcol = new int[MAX_HUD];
	private final double[] fdist = new double[MAX_HUD];
	private final String[] flabel = new String[MAX_HUD];
	private final float[] proj = new float[3];

	WaypointRenderer(WaypointsModule module) {
		this.module = module;
	}

	/** Enregistrement des événements Fabric (appelé par {@link WaypointsModule#onInit()}). */
	void register() {
		try {
			LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN.register(ctx -> WpSafe.run("rendu des waypoints dans le monde", () -> renderWorld(ctx)));
		} catch (RuntimeException | LinkageError e) {
			Log.error("waypoints : rendu dans le monde indisponible", e);
		}
		try {
			HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("autopaterns", "waypoints"),
				(g, dt) -> WpSafe.run("HUD des waypoints", () -> renderHud(g, dt)));
		} catch (RuntimeException | LinkageError e) {
			Log.error("waypoints : HUD indisponible", e);
		}
	}

	// ------------------------------------------------------------------ sélection des candidats

	private static double maxDist(WaypointPattern p) {
		return p.maxDistance <= 0 ? Double.MAX_VALUE : p.maxDistance;
	}

	/** Recalcule les candidats les plus proches si les données ont changé ou toutes les 100 ms. */
	private void rebuildIfNeeded(Waypoints st, BlockFinder finder, String ctx, String dim, double cx, double cy, double cz) {
		long now = System.nanoTime();
		boolean changed = st.version() != builtVersion || finder.version() != builtFinderVersion
			|| !ctx.equals(builtCtx) || !dim.equals(builtDim);
		if (!changed && now - builtAt < REBUILD_NANOS) return;
		builtAt = now;
		builtVersion = st.version();
		builtFinderVersion = finder.version();
		builtCtx = ctx;
		builtDim = dim;

		src.clear();
		srcPattern.clear();
		BlockFinder.NearestHeap wHeap = worldHeap;
		BlockFinder.NearestHeap hHeap = hudHeap;
		wHeap.clear();
		hHeap.clear();
		for (WaypointPattern p : st.patterns) {
			if (!st.shown(p) || p.points.isEmpty()) continue;
			double maxD = maxDist(p);
			// présélection large (le joueur bouge entre deux recalculs) ; filtre exact à chaque image
			double pre = maxD == Double.MAX_VALUE ? Double.MAX_VALUE : (maxD + 16) * (maxD + 16);
			for (Waypoint w : p.points) {
				if (!w.in(ctx, dim)) continue;
				double dx = w.x() + 0.5 - cx, dy = w.y() + 0.5 - cy, dz = w.z() + 0.5 - cz;
				double d2 = dx * dx + dy * dy + dz * dz;
				if (d2 > pre) continue;
				long key = key(d2, src.size());
				src.add(w);
				srcPattern.add(p);
				wHeap.offer(key);
				if (p.throughWalls) hHeap.offer(key);
			}
		}
		List<BlockFinder.Hit> found = finder.results(ctx, dim);
		for (BlockFinder.Hit h : found) {
			double dx = h.x + 0.5 - cx, dy = h.y + 0.5 - cy, dz = h.z + 0.5 - cz;
			long key = key(dx * dx + dy * dy + dz * dz, src.size());
			src.add(h);
			srcPattern.add(null);
			wHeap.offer(key);
			if (finder.throughWalls) hHeap.offer(key);
		}
		int fcolor = finder.color();
		long[] wk = wHeap.sorted();
		world = fill(wk, worldPool, world, fcolor);
		worldCount = wk.length;
		long[] hk = hHeap.sorted();
		hud = fill(hk, hudPool, hud, fcolor);
		hudCount = hk.length;
	}

	/** Clé triable : distance² (float, bits croissants pour les valeurs positives) puis index. */
	private static long key(double d2, int idx) {
		float f = (float) Math.min(d2, Float.MAX_VALUE);
		return ((long) Float.floatToIntBits(f) << 32) | (idx & 0xFFFFFFFFL);
	}

	private Mark[] fill(long[] keys, List<Mark> pool, Mark[] arr, int finderColor) {
		if (arr.length < keys.length) arr = new Mark[keys.length];
		while (pool.size() < keys.length) pool.add(new Mark());
		for (int i = 0; i < keys.length; i++) {
			int idx = (int) (keys[i] & 0xFFFFFFFFL);
			Mark m = pool.get(i);
			Object o = src.get(idx);
			WaypointPattern p = srcPattern.get(idx);
			if (o instanceof Waypoint w) {
				m.x = w.x();
				m.y = w.y();
				m.z = w.z();
				m.x0 = m.y0 = m.z0 = 0f;
				m.x1 = m.y1 = m.z1 = 1f;
				m.color = p.color();
				m.finder = false;
				m.maxD = maxDist(p);
				m.labelMode = p.labelMode;
				m.type = w.type();
				m.name = p.name;
			} else {
				BlockFinder.Hit h = (BlockFinder.Hit) o;
				m.x = h.x;
				m.y = h.y;
				m.z = h.z;
				m.x0 = h.x0;
				m.y0 = h.y0;
				m.z0 = h.z0;
				m.x1 = h.x1;
				m.y1 = h.y1;
				m.z1 = h.z1;
				m.color = finderColor;
				m.finder = true;
				m.maxD = Double.MAX_VALUE;
				m.labelMode = 0;
				m.type = '?';
				m.name = null;
			}
			arr[i] = m;
		}
		for (int i = keys.length; i < arr.length; i++) arr[i] = null;
		return arr;
	}

	// ------------------------------------------------------------------ rendu monde

	private void renderWorld(LevelRenderContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			hasViewProj = false;
			return;
		}
		var cs = ctx.levelState().cameraRenderState;
		Vec3 cam = cs.pos;
		if (cam == null) return;
		captureMatrices(cs.projectionMatrix, cs.viewRotationMatrix, cam);

		Waypoints st = module.state();
		BlockFinder finder = module.finder();
		st.ensureLoaded();
		String c = st.context(mc);
		String d = Waypoints.dimension(mc.level);
		rebuildIfNeeded(st, finder, c, d, cam.x, cam.y, cam.z);
		if (worldCount == 0) return;

		float[] buf = boxBuf[bufIdx];
		int[] cols = colBuf[bufIdx];
		int need = Math.min(worldCount, MAX_WORLD);
		if (buf.length < need * 6) {
			buf = new float[need * 6];
			boxBuf[bufIdx] = buf;
		}
		if (cols.length < need) {
			cols = new int[need];
			colBuf[bufIdx] = cols;
		}
		int n = 0;
		for (int i = 0; i < worldCount && n < MAX_WORLD; i++) {
			Mark m = world[i];
			double dx = m.x - cam.x, dy = m.y - cam.y, dz = m.z - cam.z;
			if (!m.finder) {
				double ex = dx + 0.5, ey = dy + 0.5, ez = dz + 0.5;
				if (ex * ex + ey * ey + ez * ez > m.maxD * m.maxD) continue;
			}
			int o = n * 6;
			buf[o] = (float) (dx + m.x0) - EPS;
			buf[o + 1] = (float) (dy + m.y0) - EPS;
			buf[o + 2] = (float) (dz + m.z0) - EPS;
			buf[o + 3] = (float) (dx + m.x1) + EPS;
			buf[o + 4] = (float) (dy + m.y1) + EPS;
			buf[o + 5] = (float) (dz + m.z1) + EPS;
			cols[n] = (m.color & 0xFFFFFF) | (m.finder ? 0x55000000 : 0x66000000);
			n++;
		}
		if (n == 0) return;
		bufIdx ^= 1;
		final float[] b = buf;
		final int[] cc = cols;
		final int count = n;
		// Pas de translation : la pile de matrices est à l'origine de la caméra, les coordonnées sont déjà relatives.
		// Couche : quadrilatères translucides POSITION_COLOR (RenderTypes.debugFilledBox, voir notes/waypoints.md).
		ctx.submitNodeCollector().submitCustomGeometry(ctx.poseStack(), RenderTypes.debugFilledBox(),
			(PoseStack.Pose pose, VertexConsumer vc) -> {
				for (int k = 0; k < count; k++) {
					int o = k * 6;
					box(vc, pose, b[o], b[o + 1], b[o + 2], b[o + 3], b[o + 4], b[o + 5], cc[k]);
				}
			});
	}

	/** 6 faces, sommets dans l'ordre de la 2.x. */
	private static void box(VertexConsumer vc, PoseStack.Pose m, float x0, float y0, float z0, float x1, float y1, float z1, int c) {
		// nord (z0)
		vc.addVertex(m, x0, y0, z0).setColor(c);
		vc.addVertex(m, x1, y0, z0).setColor(c);
		vc.addVertex(m, x1, y1, z0).setColor(c);
		vc.addVertex(m, x0, y1, z0).setColor(c);
		// sud (z1)
		vc.addVertex(m, x1, y0, z1).setColor(c);
		vc.addVertex(m, x0, y0, z1).setColor(c);
		vc.addVertex(m, x0, y1, z1).setColor(c);
		vc.addVertex(m, x1, y1, z1).setColor(c);
		// ouest (x0)
		vc.addVertex(m, x0, y0, z1).setColor(c);
		vc.addVertex(m, x0, y0, z0).setColor(c);
		vc.addVertex(m, x0, y1, z0).setColor(c);
		vc.addVertex(m, x0, y1, z1).setColor(c);
		// est (x1)
		vc.addVertex(m, x1, y0, z0).setColor(c);
		vc.addVertex(m, x1, y0, z1).setColor(c);
		vc.addVertex(m, x1, y1, z1).setColor(c);
		vc.addVertex(m, x1, y1, z0).setColor(c);
		// haut (y1)
		vc.addVertex(m, x0, y1, z0).setColor(c);
		vc.addVertex(m, x1, y1, z0).setColor(c);
		vc.addVertex(m, x1, y1, z1).setColor(c);
		vc.addVertex(m, x0, y1, z1).setColor(c);
		// bas (y0)
		vc.addVertex(m, x0, y0, z1).setColor(c);
		vc.addVertex(m, x1, y0, z1).setColor(c);
		vc.addVertex(m, x1, y0, z0).setColor(c);
		vc.addVertex(m, x0, y0, z0).setColor(c);
	}

	/** Mémorise projection × rotation de vue et position caméra de l'image en cours (projection des cadres HUD). */
	private void captureMatrices(Matrix4fc projection, Matrix4fc viewRotation, Vec3 cam) {
		if (projection == null || viewRotation == null) {
			hasViewProj = false;
			if (!matrixWarned) {
				matrixWarned = true;
				Log.warn("waypoints : matrices de caméra absentes, projection HUD de secours (angles + FOV)");
			}
			return;
		}
		projection.mul(viewRotation, viewProj);
		camX = cam.x;
		camY = cam.y;
		camZ = cam.z;
		capturedAt = System.nanoTime();
		hasViewProj = true;
	}

	// ------------------------------------------------------------------ HUD

	private void renderHud(GuiGraphicsExtractor g, DeltaTracker dt) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) return;
		if (mc.gui.hud.isHidden()) return; // F1 masque aussi ces éléments
		Waypoints st = module.state();
		BlockFinder finder = module.finder();
		st.ensureLoaded();
		Font font = mc.font;
		int w = g.guiWidth();
		int h = g.guiHeight();
		int y = 4;
		WaypointPattern act = st.active();
		if (act != null) {
			String s = Texts.tr("autopaterns.waypoints.hud.recording", act.name, act.points.size());
			g.text(font, s, w - font.width(s) - 4, y, STATUS_RED, true);
			y += 11;
		}
		if (finder.enabled && !finder.targets.isEmpty()) {
			String s = Texts.tr("autopaterns.waypoints.hud.finder", finder.resultCount());
			g.text(font, s, w - font.width(s) - 4, y, finder.color() | 0xFF000000, true);
		}
		drawFrames(mc, g, font, st, finder, w, h);
	}

	/** Cadres « à travers les murs » : les plus proches d'abord, au plus 600, dessinés du plus loin au plus proche. */
	private void drawFrames(Minecraft mc, GuiGraphicsExtractor g, Font font, Waypoints st, BlockFinder finder, int w, int h) {
		boolean anyWalls = finder.throughWalls && finder.resultCount() > 0;
		if (!anyWalls) {
			for (WaypointPattern p : st.patterns) {
				if (st.shown(p) && p.throughWalls && !p.points.isEmpty()) {
					anyWalls = true;
					break;
				}
			}
		}
		if (!anyWalls) return;

		boolean matrix = hasViewProj && System.nanoTime() - capturedAt < CAPTURE_MAX_AGE_NANOS;
		double cx, cy, cz;
		float yaw = 0f, pitch = 0f;
		if (matrix) {
			cx = camX;
			cy = camY;
			cz = camZ;
		} else {
			Camera camera = mc.gameRenderer.mainCamera();
			if (camera == null) return;
			Vec3 p = camera.position();
			if (p == null) return;
			cx = p.x;
			cy = p.y;
			cz = p.z;
			yaw = camera.yRot();
			pitch = camera.xRot();
		}
		String c = st.context(mc);
		String d = Waypoints.dimension(mc.level);
		rebuildIfNeeded(st, finder, c, d, cx, cy, cz);
		if (hudCount == 0) return;

		// repère de la projection de secours (2.x)
		double sinY = 0, cosY = 0, sinP = 0, cosP = 0, t = 1, aspect = 1;
		if (!matrix) {
			double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
			sinY = Math.sin(yr);
			cosY = Math.cos(yr);
			sinP = Math.sin(pr);
			cosP = Math.cos(pr);
			t = Math.tan(Math.toRadians(fallbackFov(mc)) / 2.0);
			aspect = w / (double) Math.max(1, h);
		}

		int accepted = 0;
		for (int i = 0; i < hudCount && accepted < MAX_HUD; i++) {
			Mark m = hud[i];
			double ex = m.x + 0.5 - cx, ey = m.y + 0.5 - cy, ez = m.z + 0.5 - cz;
			double dist = Math.sqrt(ex * ex + ey * ey + ez * ez);
			if (!m.finder && dist > m.maxD) continue;
			// 8 coins de la boîte ; un seul coin derrière la caméra suffit à ignorer le cadre (2.x)
			double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
			boolean ok = true;
			for (int k = 0; k < 8 && ok; k++) {
				double px = m.x + ((k & 1) == 0 ? m.x0 : m.x1) - cx;
				double py = m.y + (((k >> 1) & 1) == 0 ? m.y0 : m.y1) - cy;
				double pz = m.z + (((k >> 2) & 1) == 0 ? m.z0 : m.z1) - cz;
				ok = matrix ? projectMatrix(px, py, pz, w, h)
					: projectManual(px, py, pz, sinY, cosY, sinP, cosP, t, aspect, w, h);
				if (ok) {
					minX = Math.min(minX, proj[0]);
					maxX = Math.max(maxX, proj[0]);
					minY = Math.min(minY, proj[1]);
					maxY = Math.max(maxY, proj[1]);
				}
			}
			if (!ok) continue;
			if (maxX < 0 || maxY < 0 || minX > w || minY > h) continue;
			int mcx = (int) Math.round((minX + maxX) / 2.0);
			int mcy = (int) Math.round((minY + maxY) / 2.0);
			int x0 = (int) Math.floor(minX), y0 = (int) Math.floor(minY);
			int x1 = (int) Math.ceil(maxX), y1 = (int) Math.ceil(maxY);
			if (x1 - x0 < 4) {
				x0 = mcx - 2;
				x1 = mcx + 2;
			}
			if (y1 - y0 < 4) {
				y0 = mcy - 2;
				y1 = mcy + 2;
			}
			if (x1 - x0 > w || y1 - y0 > h) continue;
			fx0[accepted] = x0;
			fy0[accepted] = y0;
			fx1[accepted] = x1;
			fy1[accepted] = y1;
			fcx[accepted] = mcx;
			fcol[accepted] = m.color;
			fdist[accepted] = dist;
			flabel[accepted] = m.finder ? null : label(m, dist);
			accepted++;
		}
		for (int i = accepted - 1; i >= 0; i--) {
			int col = fcol[i] & 0xFFFFFF;
			int x0 = fx0[i], y0 = fy0[i], x1 = fx1[i], y1 = fy1[i];
			g.fill(x0, y0, x1, y1, col | 0x30000000);
			int border = col | 0xE0000000;
			g.fill(x0, y0, x1, y0 + 1, border);
			g.fill(x0, y1 - 1, x1, y1, border);
			g.fill(x0, y0, x0 + 1, y1, border);
			g.fill(x1 - 1, y0, x1, y1, border);
			String lab = flabel[i];
			if (lab != null && (fdist[i] < 48 || i < 40)) {
				g.text(font, lab, fcx[i] - font.width(lab) / 2, y1 + 2, 0xFFFFFFFF, true);
			}
			flabel[i] = null;
		}
	}

	/** Libellé selon le mode : distance, type + distance, nom + distance (aucun sinon). */
	private static String label(Mark m, double dist) {
		int dm = (int) dist;
		return switch (m.labelMode) {
			case 1 -> dm + "m";
			case 2 -> m.type + " " + dm + "m";
			case 3 -> m.name + " " + dm + "m";
			default -> null;
		};
	}

	/** Projection par la matrice de l'image : (x, y, z) relatifs à la caméra → pixels GUI dans {@link #proj}. */
	private boolean projectMatrix(double x, double y, double z, int w, int h) {
		vec.set((float) x, (float) y, (float) z, 1f);
		viewProj.transform(vec);
		if (vec.w <= 0.05f) return false;
		double nx = vec.x / vec.w, ny = vec.y / vec.w;
		proj[0] = (float) ((nx + 1.0) * 0.5 * w);
		proj[1] = (float) ((1.0 - ny) * 0.5 * h);
		return true;
	}

	/** Projection de secours (2.x) à partir des angles de la caméra et du champ de vision. */
	private boolean projectManual(double dx, double dy, double dz, double sinY, double cosY, double sinP, double cosP,
								  double t, double aspect, int w, int h) {
		double fx = -sinY * cosP, fy = -sinP, fz = cosY * cosP;
		double rx = -cosY, rz = -sinY;
		double ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy;
		double depth = dx * fx + dy * fy + dz * fz;
		if (depth < 0.05) return false;
		double sx = dx * rx + dz * rz;
		double sy = dx * ux + dy * uy + dz * uz;
		double nx = sx / (depth * t * aspect), ny = sy / (depth * t);
		proj[0] = (float) ((nx + 1.0) * 0.5 * w);
		proj[1] = (float) ((1.0 - ny) * 0.5 * h);
		return true;
	}

	/** Champ de vision des options (projection de secours uniquement ; sans réflexion). */
	private static double fallbackFov(Minecraft mc) {
		try {
			Integer v = mc.options.fov().get();
			if (v != null && v > 1) return v;
		} catch (RuntimeException | LinkageError e) {
			// valeur par défaut ci-dessous
		}
		return 70.0;
	}
}
