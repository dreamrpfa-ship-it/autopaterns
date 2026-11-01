package fr.autopaterns.waypoints;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import fr.autopaterns.core.Log;
import fr.autopaterns.core.Notifier;
import fr.autopaterns.core.Texts;
import fr.autopaterns.storage.AtomicFiles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * État du module (2.x : classe statique {@code Waypoints}) : patterns, enregistrement en cours, détection des actions
 * du joueur (miner, clic droit, clic maintenu, poser), sauvegarde de {@code waypoints.txt}, export / import.
 * Le module observe seulement : il ne simule aucune entrée et ne déplace jamais le joueur. Thread client uniquement.
 */
public final class Waypoints {
	/** Durée de vie d'une surveillance de bloc sans rafraîchissement (ticks). */
	static final int WATCH_TICKS = 60;
	/** Distance² max entre le joueur (pieds) et un bloc surveillé (16 blocs). */
	private static final double WATCH_RANGE_SQ = 256.0;
	/** Délai de sauvegarde automatique (ticks où une modification est en attente). */
	private static final int AUTOSAVE_TICKS = 40;
	/** Au plus autant de blocs ajoutés par un scan « Identiques autour » (3.0 : garde-fou mémoire / rendu). */
	static final int MAX_SIMILAR = 4000;
	/** Contexte des mondes solo enregistrés par la 2.x (partagé par tous les mondes). */
	static final String LEGACY_SOLO = "solo";

	private static final class Watch {
		final BlockState state;
		long lastTick;

		Watch(BlockState state) {
			this.state = state;
		}
	}

	private final BlockFinder finder;
	public final List<WaypointPattern> patterns = new ArrayList<>();
	private WaypointPattern active;
	private String lastActiveName;
	private boolean loaded;
	private boolean dirty;
	private int saveCountdown;
	private String lastSaved;
	private int version;
	private long tick;

	// appui sur « utiliser »
	private boolean pressing;
	private int pressTicks;
	private BlockPos pressFirstPos;
	private boolean placedDuringPress;
	private BlockPos holdPos;
	private int holdCount;
	private BlockPos interactPos;
	private long interactTick = -1;
	private long placedTick = -1;

	// minage
	private final Map<BlockPos, Watch> watch = new HashMap<>();
	private BlockPos breakPos;
	private BlockState breakState;
	// pose
	private BlockPos[] snapPos;
	private BlockState[] snapState;

	// cache du contexte (recalculé à chaque changement de monde)
	private ClientLevel ctxLevel;
	private String ctxValue;
	private static final Map<Object, String> DIM_CACHE = Collections.synchronizedMap(new IdentityHashMap<>());

	Waypoints(BlockFinder finder) {
		this.finder = finder;
	}

	// ------------------------------------------------------------------ accès

	public WaypointPattern active() {
		return active;
	}

	public boolean isActive(WaypointPattern p) {
		return p != null && p == active;
	}

	/** Affiché dans le monde : visible, ou en cours d'enregistrement. */
	public boolean shown(WaypointPattern p) {
		return p.visible || isActive(p);
	}

	public WaypointPattern find(String name) {
		if (name == null) return null;
		for (WaypointPattern p : patterns) if (name.equals(p.name)) return p;
		return null;
	}

	/** Modification à sauvegarder (sauvegarde automatique). */
	public void markDirty() {
		dirty = true;
		version++;
	}

	/** Compteur de modifications (cache du rendu). */
	int version() {
		return version;
	}

	// ------------------------------------------------------------------ contexte

	/**
	 * Contexte du monde courant : adresse du serveur en minuscules ; en solo, {@code solo/<dossier du monde>}.
	 * Correction 2.x : tous les mondes solo partageaient « solo » et leurs waypoints se mélangeaient. Les points
	 * « solo » déjà enregistrés par la 2.x restent affichés dans tous les mondes solo (voir {@link #sameContext}).
	 */
	public String context(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level != null && level == ctxLevel && ctxValue != null) return ctxValue;
		String v = computeContext(mc);
		ctxLevel = level;
		ctxValue = level == null ? null : v;
		return v;
	}

	private static String computeContext(Minecraft mc) {
		try {
			ServerData si = mc.getCurrentServer();
			if (si != null && si.ip != null && !si.ip.isEmpty()) return si.ip.toLowerCase(Locale.ROOT);
		} catch (RuntimeException e) {
			Log.debug("waypoints : adresse du serveur illisible : " + e);
		}
		String world = singleplayerWorldId(mc);
		return world == null || world.isEmpty() ? LEGACY_SOLO : LEGACY_SOLO + "/" + WaypointIO.clean(world);
	}

	/** Nom du dossier du monde solo ouvert (unique par monde), ou null. */
	private static String singleplayerWorldId(Minecraft mc) {
		try {
			IntegratedServer server = mc.getSingleplayerServer();
			if (server == null) return null;
			Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			Path name = root.getFileName();
			return name == null ? null : name.toString();
		} catch (RuntimeException e) {
			Log.debug("waypoints : dossier du monde solo illisible : " + e);
			return null;
		}
	}

	/** Un point de contexte {@code pointCtx} appartient-il au contexte courant ? */
	static boolean sameContext(String pointCtx, String curCtx) {
		if (pointCtx.equals(curCtx)) return true;
		return LEGACY_SOLO.equals(pointCtx) && curCtx != null && curCtx.startsWith(LEGACY_SOLO + "/");
	}

	/**
	 * Identifiant de dimension ({@code minecraft:overworld}). Lu dans {@code ResourceKey#toString()}
	 * (« ResourceKey[minecraft:dimension / minecraft:overworld] »), forme stable depuis la 1.16, pour ne dépendre
	 * d'aucun accesseur renommé ; mis en cache par clé.
	 */
	public static String dimension(Level level) {
		if (level == null) return "?";
		try {
			Object key = level.dimension();
			String cached = DIM_CACHE.get(key);
			if (cached != null) return cached;
			String s = String.valueOf(key);
			int sep = s.lastIndexOf(" / ");
			String id = sep >= 0 && s.endsWith("]") ? s.substring(sep + 3, s.length() - 1) : s;
			DIM_CACHE.put(key, id);
			return id;
		} catch (RuntimeException e) {
			return "?";
		}
	}

	// ------------------------------------------------------------------ fichier

	/** Charge {@code waypoints.txt} une seule fois (jamais d'écriture avant ce chargement). */
	public void ensureLoaded() {
		if (loaded) return;
		loaded = true;
		Path f = WaypointIO.file();
		Path bak = f.resolveSibling(f.getFileName() + ".bak");
		if (!Files.exists(f) && !Files.exists(bak)) return;
		try {
			List<String> lines = AtomicFiles.readLinesWithBackup(f);
			patterns.addAll(WaypointIO.parse(lines, fields -> {
				if ("L".equals(fields[0]) && fields.length >= 2) lastActiveName = fields[1];
				else if ("F".equals(fields[0])) finder.read(fields);
			}));
			int n = 0;
			for (WaypointPattern p : patterns) n += p.points.size();
			Log.info("waypoints : " + patterns.size() + " pattern(s), " + n + " points chargés");
		} catch (IOException | RuntimeException e) {
			Log.error("waypoints.txt illisible", e);
			// Le fichier sera réécrit : on garde une copie de l'original pour ne rien perdre.
			try {
				if (Files.exists(f)) {
					Files.copy(f, f.resolveSibling("waypoints.txt.illisible"), StandardCopyOption.REPLACE_EXISTING);
				}
			} catch (IOException | RuntimeException e2) {
				Log.error("copie de waypoints.txt impossible", e2);
			}
			Notifier.hud(Texts.tr("autopaterns.waypoints.load_failed"));
		}
		version++;
	}

	/** Écrit {@code waypoints.txt} (atomique, avec copie .bak), seulement si le contenu a changé. */
	public void saveNow() {
		dirty = false;
		if (!loaded) return;
		StringBuilder sb = new StringBuilder(4096);
		sb.append(WaypointIO.HEADER).append('\n');
		if (lastActiveName != null) sb.append("L\t").append(WaypointIO.clean(lastActiveName)).append('\n');
		finder.write(sb);
		for (WaypointPattern p : patterns) WaypointIO.write(sb, p);
		String text = sb.toString();
		if (text.equals(lastSaved)) return;
		try {
			AtomicFiles.writeString(WaypointIO.file(), text);
			lastSaved = text;
		} catch (IOException | RuntimeException e) {
			WpSafe.logLimited("sauvegarde des waypoints", e);
			dirty = true; // nouvel essai à la prochaine sauvegarde automatique
		}
	}

	// ------------------------------------------------------------------ cycle d'enregistrement

	/** Lance l'enregistrement de {@code p}. Correction 2.x : un autre pattern actif est d'abord arrêté proprement. */
	public void start(WaypointPattern p) {
		ensureLoaded();
		if (p != null && active != null && active != p) stop();
		active = p;
		if (p != null) lastActiveName = p.name;
		resetPress();
		if (p != null) {
			markDirty();
			feedback(Texts.tr("autopaterns.waypoints.started", p.name, p.triggersSummary()));
		}
	}

	public void stop() {
		WaypointPattern p = active;
		if (p != null) feedback(Texts.tr("autopaterns.waypoints.stopped", p.name, p.points.size()));
		active = null;
		resetPress();
		saveNow();
	}

	/**
	 * Raccourci F12 : arrête, ou relance le dernier pattern (sinon le premier, sinon un nouveau).
	 * Correction 2.x : un pattern sans aucune action à capturer n'est plus lancé « à vide ».
	 */
	public void toggle() {
		ensureLoaded();
		if (active != null) {
			stop();
			return;
		}
		WaypointPattern p = find(lastActiveName);
		if (p == null && !patterns.isEmpty()) p = patterns.get(0);
		if (p == null) p = create();
		if (!p.anyTrigger()) {
			feedback(Texts.tr("autopaterns.waypoints.no_trigger", p.name));
			return;
		}
		start(p);
	}

	/** Nouveau pattern « Waypoints N » (couleur suivante), sauvegardé immédiatement. */
	public WaypointPattern create() {
		ensureLoaded();
		int n = patterns.size() + 1;
		while (find("Waypoints " + n) != null) n++;
		WaypointPattern p = new WaypointPattern("Waypoints " + n);
		p.colorIndex = patterns.size() % WaypointPattern.COLORS.length;
		patterns.add(p);
		markDirty();
		saveNow();
		return p;
	}

	/** Supprime un pattern. Correction 2.x : l'arrêt est notifié et le « dernier lancé » est oublié. */
	public void delete(WaypointPattern p) {
		if (p == null) return;
		if (p == active) {
			feedback(Texts.tr("autopaterns.waypoints.stopped", p.name, p.points.size()));
			active = null;
			resetPress();
		}
		if (p.name != null && p.name.equals(lastActiveName)) lastActiveName = null;
		patterns.remove(p);
		markDirty();
		saveNow();
	}

	/**
	 * Renommage depuis le champ « Nom » : ignoré si vide ou déjà pris par un autre pattern (2.x).
	 * Correction 2.x : le « dernier pattern lancé » suit le renommage.
	 */
	public boolean rename(WaypointPattern p, String text) {
		String t = text == null ? "" : text.trim();
		if (t.isEmpty() || t.equals(p.name)) return false;
		WaypointPattern other = find(t);
		if (other != null && other != p) return false;
		if (p.name != null && p.name.equals(lastActiveName)) lastActiveName = t;
		p.name = t;
		markDirty();
		return true;
	}

	/** Nom libre : {@code base}, sinon {@code base (2)}, {@code base (3)}… */
	public String uniqueName(String base) {
		if (find(base) == null) return base;
		int k = 2;
		while (find(base + " (" + k + ")") != null) k++;
		return base + " (" + k + ")";
	}

	// ------------------------------------------------------------------ capture

	static void feedback(String msg) {
		Notifier.hud(msg);
	}

	/** Ajoute le bloc au pattern actif si l'action est capturée (cœur de la 2.x). */
	void record(BlockPos pos, char type, BlockState stateForSimilar) {
		WaypointPattern p = active;
		if (p == null || pos == null || !p.accepts(type)) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		String ctx = context(mc);
		String dim = dimension(mc.level);
		Waypoint w = new Waypoint(ctx, dim, pos.getX(), pos.getY(), pos.getZ(), type, System.currentTimeMillis());
		if (type == Waypoint.MINE && p.similarRadius > 0 && stateForSimilar != null && !stateForSimilar.isAir()) {
			scanSimilar(mc.level, p, w, stateForSimilar);
		}
		// un bloc déjà marqué en solo par la 2.x (contexte « solo ») compte comme déjà marqué
		boolean legacy = !p.dupes && !LEGACY_SOLO.equals(ctx) && ctx.startsWith(LEGACY_SOLO + "/")
			&& p.containsKey(Waypoint.keyOf(LEGACY_SOLO, dim, w.x(), w.y(), w.z()));
		if (!legacy && p.add(w)) {
			markDirty();
			feedback(Texts.tr("autopaterns.waypoints.added", Waypoint.typeLabel(type), p.name, p.points.size(),
				w.x() + " " + w.y() + " " + w.z()));
		} else {
			feedback(Texts.tr("autopaterns.waypoints.already", Waypoint.typeLabel(type), w.x() + " " + w.y() + " " + w.z()));
		}
	}

	/** « Identiques autour » : scan incrémental du cube autour du bloc miné (variantes deepslate toujours regroupées). */
	private void scanSimilar(ClientLevel level, WaypointPattern p, Waypoint w, BlockState state) {
		String key = BlockFinder.keyOf(state, true);
		if (key == null) return;
		int[] count = {0};
		boolean[] capped = {false};
		finder.submit(new BlockFinder.Job(level, w.x(), w.y(), w.z(), p.similarRadius,
			s -> !s.isAir() && key.equals(BlockFinder.keyOf(s, true)),
			(x, y, z) -> {
				if (x == w.x() && y == w.y() && z == w.z()) return;
				if (count[0] >= MAX_SIMILAR) {
					capped[0] = true;
					return;
				}
				if (p.add(new Waypoint(w.ctx(), w.dim(), x, y, z, Waypoint.SIMILAR, System.currentTimeMillis()))) {
					count[0]++;
					version++;
				}
			},
			() -> {
				if (count[0] <= 0 || !patterns.contains(p)) return;
				markDirty();
				feedback(Texts.tr(capped[0] ? "autopaterns.waypoints.similar_capped" : "autopaterns.waypoints.similar",
					count[0], BlockFinder.display(key), p.name));
			}));
	}

	/** Bloc visé par le réticule, ou null. */
	static BlockPos crosshairBlock(Minecraft mc) {
		if (mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
			BlockPos bp = bhr.getBlockPos();
			return bp == null ? null : bp.immutable();
		}
		return null;
	}

	// --- chemin A : MultiPlayerGameMode#destroyBlock (mixin)

	void onBreakPre(BlockPos pos) {
		breakPos = null;
		breakState = null;
		if (active == null || pos == null) return;
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) return;
		breakPos = pos.immutable();
		breakState = level.getBlockState(breakPos);
	}

	void onBlockBroken(BlockPos pos) {
		if (active == null || pos == null) return;
		BlockPos ip = pos.immutable();
		Watch wt = watch.remove(ip);
		BlockState state = ip.equals(breakPos) ? breakState : (wt != null ? wt.state : null);
		breakPos = null;
		breakState = null;
		record(ip, Waypoint.MINE, state);
	}

	// --- chemin B : surveillance de l'état des blocs frappés

	/** startDestroyBlock / continueDestroyBlock (mixin). */
	void onAttack(BlockPos pos) {
		if (active != null && active.mine && pos != null) {
			ClientLevel level = Minecraft.getInstance().level;
			if (level != null) watch(level, pos.immutable());
		}
	}

	private void watch(ClientLevel level, BlockPos pos) {
		Watch w = watch.get(pos);
		if (w == null) {
			BlockState s = level.getBlockState(pos);
			if (s == null || s.isAir()) return;
			w = new Watch(s);
			watch.put(pos, w);
		}
		w.lastTick = tick;
	}

	private void checkWatches(Minecraft mc) {
		if (watch.isEmpty()) return;
		ClientLevel level = mc.level;
		LocalPlayer pl = mc.player;
		List<BlockPos> mined = null;
		List<BlockState> minedStates = null;
		Iterator<Map.Entry<BlockPos, Watch>> it = watch.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<BlockPos, Watch> e = it.next();
			BlockPos pos = e.getKey();
			Watch w = e.getValue();
			if (tick - w.lastTick > WATCH_TICKS || level == null || pl == null) {
				it.remove();
				continue;
			}
			double dx = pos.getX() + 0.5 - pl.getX();
			double dy = pos.getY() + 0.5 - pl.getY();
			double dz = pos.getZ() + 0.5 - pl.getZ();
			if (dx * dx + dy * dy + dz * dz > WATCH_RANGE_SQ) {
				it.remove();
				continue;
			}
			BlockState cur = level.getBlockState(pos);
			if (cur == null || cur == w.state) continue;
			if (cur.isAir() || cur.getBlock() != w.state.getBlock()) {
				it.remove();
				if (mined == null) {
					mined = new ArrayList<>(2);
					minedStates = new ArrayList<>(2);
				}
				mined.add(pos);
				minedStates.add(w.state);
			}
			// même bloc, propriétés changées : pas un minage (2.x)
		}
		if (mined != null) {
			for (int i = 0; i < mined.size(); i++) record(mined.get(i), Waypoint.MINE, minedStates.get(i));
		}
	}

	// --- pose : MultiPlayerGameMode#useItemOn (mixin)

	void onInteractPre(LocalPlayer player, InteractionHand hand, BlockHitResult hit) {
		snapPos = null;
		snapState = null;
		if (hit == null) return;
		BlockPos bp = hit.getBlockPos();
		interactPos = bp == null ? null : bp.immutable();
		interactTick = tick;
		if (active == null || !active.place || player == null || bp == null || hand == null) return;
		ItemStack stack = player.getItemInHand(hand);
		if (stack == null || !(stack.getItem() instanceof BlockItem)) return;
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) return;
		Direction side = hit.getDirection();
		BlockPos adj = side == null ? interactPos : interactPos.relative(side);
		snapPos = new BlockPos[] {interactPos, adj};
		snapState = new BlockState[] {level.getBlockState(snapPos[0]), level.getBlockState(snapPos[1])};
	}

	void onInteractPost() {
		try {
			ClientLevel level = Minecraft.getInstance().level;
			if (snapPos == null || active == null || level == null) return;
			for (int i = 0; i < 2; i++) {
				BlockState old = snapState[i];
				BlockState cur = level.getBlockState(snapPos[i]);
				if (old != null && cur != null && cur != old && (old.isAir() || old.canBeReplaced()) && !cur.isAir()) {
					placedTick = tick;
					placedDuringPress = true;
					record(snapPos[i], Waypoint.PLACE, null);
					break;
				}
			}
		} finally {
			snapPos = null;
			snapState = null;
		}
	}

	void resetPress() {
		watch.clear();
		pressing = false;
		pressTicks = 0;
		pressFirstPos = null;
		placedDuringPress = false;
		holdPos = null;
		holdCount = 0;
	}

	/** Annule les captures en cours (arrêt du mod). */
	void halt() {
		resetPress();
		snapPos = null;
		snapState = null;
		breakPos = null;
		breakState = null;
	}

	// ------------------------------------------------------------------ tick

	void onEndTick(Minecraft mc) {
		ensureLoaded();
		if (dirty && --saveCountdown <= 0) {
			saveCountdown = AUTOSAVE_TICKS;
			saveNow();
		}
		WaypointPattern p = active;
		if (p == null || mc.player == null || mc.options == null || mc.level == null) {
			resetPress();
			tick++;
			return;
		}
		boolean noScreen = mc.gui.screen() == null;
		// Miner (chemin B)
		if (p.mine) {
			if (noScreen && mc.options.keyAttack.isDown()) {
				BlockPos c = crosshairBlock(mc);
				if (c != null) watch(mc.level, c);
			}
			checkWatches(mc);
		} else {
			watch.clear();
		}
		// Clic droit / clic maintenu : machine à états de l'appui sur « utiliser »
		boolean pressedNow = noScreen && mc.options.keyUse.isDown();
		if (pressedNow) {
			if (!pressing) {
				pressing = true;
				pressTicks = 0;
				pressFirstPos = interactTick == tick ? interactPos : null;
				placedDuringPress = placedTick == tick;
				holdPos = null;
				holdCount = 0;
			}
			pressTicks++;
			if (pressFirstPos == null && interactTick == tick) pressFirstPos = interactPos;
			if (placedTick == tick) placedDuringPress = true;
			BlockPos c = crosshairBlock(mc);
			if (c != null && c.equals(holdPos)) {
				holdCount++;
			} else {
				holdPos = c;
				holdCount = c == null ? 0 : 1;
			}
			if (p.hold && holdPos != null && holdCount == Math.max(1, p.holdTicks) && !placedDuringPress) {
				record(holdPos, Waypoint.HOLD, null);
			}
		} else if (pressing) {
			pressing = false;
			if (p.click && pressFirstPos != null && !placedDuringPress && (!p.hold || pressTicks < p.holdTicks)) {
				record(pressFirstPos, Waypoint.CLICK, null);
			}
			pressFirstPos = null;
		}
		tick++;
	}

	// ------------------------------------------------------------------ export / import

	public String export(Minecraft mc, WaypointPattern p) {
		return WaypointIO.export(mc, p);
	}

	public String importText(String s) {
		ensureLoaded();
		if (s == null || s.isBlank()) return Texts.tr("autopaterns.waypoints.import.empty");
		List<WaypointPattern> list = WaypointIO.parseImport(s);
		if (list.isEmpty()) return Texts.tr("autopaterns.waypoints.import.none");
		int pts = 0;
		for (WaypointPattern p : list) {
			p.name = uniqueName(p.name);
			patterns.add(p);
			pts += p.points.size();
		}
		markDirty();
		saveNow();
		return Texts.tr("autopaterns.waypoints.import.done", list.size(), pts);
	}

	public String importFile(Path path) {
		try {
			return importText(Files.readString(path, StandardCharsets.UTF_8));
		} catch (IOException | RuntimeException e) {
			Log.error("import de " + path + " impossible", e);
			return Texts.tr("autopaterns.waypoints.import.read_failed", String.valueOf(path.getFileName()));
		}
	}

	public String importClipboard(Minecraft mc) {
		try {
			return importText(mc.keyboardHandler.getClipboard());
		} catch (RuntimeException | LinkageError e) {
			Log.error("presse-papiers illisible (import)", e);
			return Texts.tr("autopaterns.waypoints.import.clipboard_failed");
		}
	}

	/** « Créer des waypoints avec les blocs trouvés » (écran de surbrillance). */
	public String toWaypoints(Minecraft mc) {
		if (mc.level == null) return Texts.tr("autopaterns.waypoints.finder.no_world");
		String ctx = context(mc);
		String dim = dimension(mc.level);
		List<BlockFinder.Hit> found = finder.results(ctx, dim);
		if (found.isEmpty()) return Texts.tr("autopaterns.waypoints.finder.nothing");
		List<String> names = new ArrayList<>();
		for (String t : finder.targets) names.add(BlockFinder.display(t));
		String base = Texts.tr("autopaterns.waypoints.finder.pattern_name", String.join(", ", names));
		if (base.length() > 40) base = base.substring(0, 40);
		WaypointPattern p = create();
		p.name = uniqueName(base);
		p.mine = false;
		p.colorIndex = finder.colorIndex;
		long now = System.currentTimeMillis();
		for (BlockFinder.Hit h : found) p.add(new Waypoint(ctx, dim, h.x, h.y, h.z, Waypoint.SIMILAR, now));
		markDirty();
		saveNow();
		return Texts.tr("autopaterns.waypoints.finder.created", p.name, p.points.size());
	}
}
