package fr.autopaterns.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

import fr.autopaterns.core.AutoPaterns;
import fr.autopaterns.core.Guard;
import fr.autopaterns.core.ModPaths;
import fr.autopaterns.core.Notifier;
import fr.autopaterns.core.Phase;
import fr.autopaterns.core.Texts;
import fr.autopaterns.fishing.FishingScreen;
import fr.autopaterns.fishing.FishingService;
import fr.autopaterns.fishing.FishingTestAccess;
import fr.autopaterns.gui.KeysScreen;
import fr.autopaterns.gui.MainMenuScreen;
import fr.autopaterns.hud.HudConf;
import fr.autopaterns.hud.HudEditScreen;
import fr.autopaterns.hud.HudLayout;
import fr.autopaterns.hud.HudModule;
import fr.autopaterns.input.KeyDef;
import fr.autopaterns.input.Keys;
import fr.autopaterns.patterns.PatternEngine;
import fr.autopaterns.patterns.PatternsScreen;
import fr.autopaterns.performance.PerformanceMode;
import fr.autopaterns.performance.PerformanceScreen;
import fr.autopaterns.waypoints.BlockFinder;
import fr.autopaterns.waypoints.Waypoint;
import fr.autopaterns.waypoints.WaypointPattern;
import fr.autopaterns.waypoints.Waypoints;
import fr.autopaterns.waypoints.WaypointsScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/**
 * Tests en jeu d'AutoPaterns 3.0 (vrai client Minecraft, Fabric client game tests). Voir {@code notes/gametest.md}.
 *
 * <p>Chaque vérification est inscrite dans {@code <jeu>/autopaterns/test-report.txt} et dans le journal ; un échec
 * n'interrompt pas la suite. À la fin, une {@link AssertionError} est levée s'il y a au moins un échec. Tout accès au
 * jeu ou au mod passe par {@code runOnClient} / {@code computeOnClient} ; les attentes se font en ticks, avec délai.
 */
public final class AutoPaternsClientGameTest implements FabricClientGameTest {
	/** Fenêtre et échelle d'interface fixes : tous les widgets des écrans du mod sont visibles sans défilement. */
	private static final int WIDTH = 1280, HEIGHT = 960, GUI_SCALE = 2;

	@Override
	public void runTest(ClientGameTestContext context) {
		Report r = new Report();
		try {
			r.section("0 préparation");
			r.run("écran titre et chargement du mod", () -> {
				boolean ok = TestUtil.waitUntil(context, mc -> mc.gui.screen() instanceof TitleScreen
					&& TestUtil.apOrNull() != null && TestUtil.apOrNull().isLoaded(), 1200);
				r.check("écran titre affiché et modules du mod chargés (premier tick passé)", ok);
			});
			r.run("fenêtre 1280×960, échelle 2", () -> setupWindow(context));

			r.section("1 chargement du mod");
			r.run("chargement", () -> modLoaded(context, r));

			r.section("11 mode performance");
			r.run("application puis restauration exacte", () -> performanceApplyRestore(context, r));

			r.section("12 récupération après crash");
			r.run("récupération (état mémoire perdu)", () -> performanceCrashRecovery(context, r));
			r.run("récupération d'une sauvegarde au format 2.x", () -> performanceRecovery2x(context, r));
			r.run("options par défaut des tests", () -> {
				context.restoreDefaultGameOptions();
				setupWindow(context);
			});

			r.section("monde solo");
			TestSingleplayerContext sp = null;
			try {
				sp = context.worldBuilder()
					.adjustSettings(c -> c.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE))
					.create();
				sp.getConnection().waitForChunksRender();
				r.check("monde plat créatif créé et chunks affichés", true);
			} catch (RuntimeException | AssertionError e) {
				r.fail("création du monde solo", e);
			}
			if (sp != null) {
				try (TestSingleplayerContext world = sp) {
					worldTests(context, world, r);
				} catch (RuntimeException | AssertionError e) {
					r.fail("fermeture du monde solo", e);
				}
			}

			r.section("fin");
			r.run("état final du mod", () -> TestUtil.guardOk(context, r));
			r.run("fichiers temporaires", () -> FormatTests.noTempFiles(r));
		} finally {
			r.finish();
		}
		if (r.failureCount() > 0) {
			throw new AssertionError("AutoPaterns : " + r.failureCount() + " vérification(s) en échec sur "
				+ (r.failureCount() + r.passCount()) + " - voir " + r.file());
		}
	}

	private static void setupWindow(ClientGameTestContext context) {
		context.getInput().resizeWindow(WIDTH, HEIGHT);
		context.runOnClient(mc -> mc.options.guiScale().set(GUI_SCALE));
		context.waitTicks(2);
	}

	/** Tous les tests qui demandent un monde, dans l'ordre (un échec n'empêche pas les suivants). */
	private static void worldTests(ClientGameTestContext ctx, TestSingleplayerContext sp, Report r) {
		r.section("2 menu principal (touche O)");
		r.run("ouverture / fermeture par la touche", () -> menuKey(ctx, r));

		r.section("3 boutons du menu principal");
		r.run("les 5 boutons et le bouton HUD", () -> menuButtons(ctx, r));
		guard(ctx, r);

		r.section("4 patterns (+ 9 HUD pendant la lecture)");
		r.run("enregistrement, lecture, fichiers", () -> patterns(ctx, sp, r));
		guard(ctx, r);

		r.section("5 boucle puis arrêt d'urgence");
		r.run("boucle F8 puis F6", () -> loopAndStop(ctx, r));
		guard(ctx, r);

		r.section("7 waypoints et surbrillance");
		r.run("waypoints", () -> waypoints(ctx, sp, r));
		guard(ctx, r);

		r.section("6 pêche");
		r.run("pêche", () -> fishing(ctx, sp, r));
		guard(ctx, r);

		r.section("8 vente et corbeille");
		r.run("inventaire vidé", () -> {
			TestUtil.ensureIdle(ctx, r);
			sp.getServer().runCommand("clear @a");
			r.check("inventaire vide", TestUtil.waitUntil(ctx, mc -> {
				for (int i = 0; i < 36; i++) if (!mc.player.getInventory().getItem(i).isEmpty()) return false;
				return true;
			}, 60));
		});
		r.run("uiTest sans items", () -> FormatTests.uiTestRefusal(ctx, r));
		r.run("ventes.txt / corbeille.txt", () -> FormatTests.sellFiles(ctx, r));
		r.run("grammaire des étapes", () -> FormatTests.sellSteps(ctx, r));
		guard(ctx, r);

		r.section("9 HUD : disposition");
		r.run("modification, sauvegarde, relecture, réinitialisation", () -> hudLayout(ctx, r));

		r.section("10 touches");
		r.run("réassignation, conflit, réinitialisation", () -> keys(ctx, r));
		guard(ctx, r);

		r.section("13 migration des données 2.x");
		r.run("patterns 2.x (lecteur du stockage)", () -> FormatTests.patternsOffline(r));
		r.run("patterns 2.x (API du module)", () -> FormatTests.patternsModule(ctx, r));
		r.run("hud.txt 2.x", () -> FormatTests.hudMigration(ctx, r));
		r.run("waypoints.txt 2.x", () -> FormatTests.waypointsMigration(ctx, r));
		guard(ctx, r);

		r.run("remise au repos avant la fermeture du monde", () -> {
			TestUtil.ensureIdle(ctx, r);
			TestUtil.closeScreens(ctx);
		});
	}

	private static void guard(ClientGameTestContext ctx, Report r) {
		r.run("contrôle de Guard", () -> TestUtil.guardOk(ctx, r));
	}

	// =========================================================================================== 1

	private static void modLoaded(ClientGameTestContext ctx, Report r) {
		ctx.runOnClient(mc -> {
			AutoPaterns ap = TestUtil.apOrNull();
			r.check("AutoPaterns.get() non nul", ap != null);
			r.check("Guard.disabled() faux", !Guard.disabled(), Guard.lastError());
			if (ap == null) return;
			r.check("modules chargés (config.txt lu, premier tick passé)", ap.isLoaded() && ap.config().isLoaded());
			List<String> names = new ArrayList<>();
			ap.modules().forEach(m -> names.add(m.name()));
			r.equal("7 modules dans l'ordre d'initialisation", 7, names.size());
			r.info("modules : " + names);
			r.check("HUD du mod enregistré auprès de Fabric (Notifier.hudOk)", Notifier.hudOk());
			for (KeyDef d : KeyDef.values()) {
				KeyMapping km = Keys.mapping(d);
				boolean inOptions = false;
				if (km != null) for (KeyMapping k : mc.options.keyMappings) if (k == km) inOptions = true;
				r.check("raccourci " + d + " enregistré (présent dans Options > Commandes)", km != null && inOptions,
					km == null ? "absent" : km.getName() + " = " + Keys.label(d));
			}
		});
		r.check("mod de test chargé à côté du mod", FabricLoader.getInstance().isModLoaded("autopaterns")
			&& FabricLoader.getInstance().isModLoaded("autopaterns-gametest"));
	}

	// =========================================================================================== 2 / 3

	private static void menuKey(ClientGameTestContext ctx, Report r) {
		TestUtil.closeScreens(ctx);
		TestUtil.press(ctx, KeyDef.MENU);
		boolean open = TestUtil.waitUntil(ctx, mc -> mc.gui.screen() instanceof MainMenuScreen, 20);
		r.check("touche O (entrée simulée) : MainMenuScreen ouvert", open, screenName(ctx));
		if (!open) return;
		ctx.waitTicks(8);
		TestUtil.shot(ctx, r, "02-menu-principal");
		TestUtil.press(ctx, KeyDef.MENU);
		boolean closed = TestUtil.waitUntil(ctx, mc -> mc.gui.screen() == null, 20);
		r.check("touche O à nouveau : menu fermé", closed, screenName(ctx));
		ctx.waitTicks(5);
		r.check("menu toujours fermé 5 ticks plus tard (pas de réouverture par le clic de la touche)",
			ctx.computeOnClient(mc -> mc.gui.screen() == null), screenName(ctx));
	}

	private static String screenName(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> mc.gui.screen() == null ? "aucun écran" : mc.gui.screen().getClass().getName());
	}

	private record MenuTarget(String key, Class<? extends Screen> screen, String shot) {}

	private static void menuButtons(ClientGameTestContext ctx, Report r) {
		List<MenuTarget> targets = List.of(
			new MenuTarget("autopaterns.menu.fishing", FishingScreen.class, "03a-peche"),
			new MenuTarget("autopaterns.menu.waypoints", WaypointsScreen.class, "03b-waypoints"),
			new MenuTarget("autopaterns.menu.patterns", PatternsScreen.class, "03c-patterns"),
			new MenuTarget("autopaterns.menu.keys", KeysScreen.class, "03d-touches"),
			new MenuTarget("autopaterns.menu.performance", PerformanceScreen.class, "03e-performance"),
			new MenuTarget("autopaterns.menu.hud", HudEditScreen.class, "03f-hud"));
		TestUtil.closeScreens(ctx);
		ctx.waitTicks(4); // anti-rebond du raccourci
		TestUtil.press(ctx, KeyDef.MENU);
		if (!r.check("menu principal ouvert", TestUtil.waitUntil(ctx, mc -> mc.gui.screen() instanceof MainMenuScreen, 20), screenName(ctx))) {
			return;
		}
		for (MenuTarget t : targets) {
			String label = ctx.computeOnClient(mc -> Texts.tr(t.key()));
			ctx.waitTicks(2);
			String miss = TestUtil.clickWidget(ctx, label);
			boolean opened = miss == null && TestUtil.waitUntil(ctx, mc -> t.screen().isInstance(mc.gui.screen()), 20);
			r.check("bouton « " + label + " » → " + t.screen().getSimpleName(), opened, miss != null ? miss : screenName(ctx));
			if (opened) {
				ctx.waitTicks(10); // fin de l'animation d'ouverture
				TestUtil.shot(ctx, r, t.shot());
				ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
				boolean back = TestUtil.waitUntil(ctx, mc -> mc.gui.screen() instanceof MainMenuScreen, 20);
				r.check("Échap : retour au menu principal depuis " + t.screen().getSimpleName(), back, screenName(ctx));
			}
			if (!ctx.computeOnClient(mc -> mc.gui.screen() instanceof MainMenuScreen)) {
				ctx.setScreen(() -> new MainMenuScreen(null));
				ctx.waitTicks(2);
			}
		}
		ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
		r.check("Échap sur le menu principal : fermé", TestUtil.waitUntil(ctx, mc -> mc.gui.screen() == null, 20), screenName(ctx));
		TestUtil.closeScreens(ctx);
	}

	// =========================================================================================== 4 / 9

	private static void patterns(ClientGameTestContext ctx, TestSingleplayerContext sp, Report r) {
		TestUtil.closeScreens(ctx);
		TestUtil.ensureIdle(ctx, r);
		boolean perfWas = ctx.computeOnClient(mc -> AutoPaterns.get().performance().isEnabled());
		ctx.runOnClient(mc -> {
			AutoPaterns.get().performance().setEnabled(false); // pas de préparation de 2 s
			AutoPaterns.get().config().save();
		});
		try {
			recordAndPlay(ctx, sp, r);
		} finally {
			ctx.runOnClient(mc -> {
				AutoPaterns.get().performance().setEnabled(perfWas);
				AutoPaterns.get().config().save();
			});
		}
		patternFiles(ctx, r);
	}

	private static void recordAndPlay(ClientGameTestContext ctx, TestSingleplayerContext sp, Report r) {
		PatternEngine pe = AutoPaterns.get().patterns();
		float yaw0 = 0f;
		ctx.getInput().lookAt(yaw0, 10f);
		ctx.waitTicks(3);
		double[] start = TestUtil.pos(ctx);

		TestUtil.press(ctx, KeyDef.RECORD);
		boolean rec = TestUtil.waitUntil(ctx, mc -> AutoPaterns.get().automation().phase() == Phase.RECORDING, 10);
		r.check("F7 : enregistrement démarré sans préparation (phase RECORDING)", rec, String.valueOf(TestUtil.phase(ctx)));
		if (!rec) return;

		ctx.getInput().holdKey(o -> o.keyUp);
		for (int i = 0; i < 40; i++) {
			ctx.getInput().lookAt(yaw0 + 2.5f * i, 10f - 0.25f * i);
			ctx.waitTick();
		}
		ctx.getInput().releaseKey(o -> o.keyUp);
		TestUtil.shot(ctx, r, "04a-hud-enregistrement");
		ctx.waitTicks(15); // le joueur s'arrête
		int recTicks = ctx.computeOnClient(mc -> pe.recordingTicks());
		double[] end = TestUtil.pos(ctx);
		r.check("le joueur a avancé pendant l'enregistrement", TestUtil.dist(start, end) > 2.0,
			TestUtil.fmt(start) + " → " + TestUtil.fmt(end));

		TestUtil.press(ctx, KeyDef.RECORD);
		boolean stopped = TestUtil.waitUntil(ctx, mc -> AutoPaterns.get().automation().phase() == Phase.IDLE, 10);
		r.check("F7 : enregistrement arrêté (phase IDLE)", stopped, String.valueOf(TestUtil.phase(ctx)));
		int size = ctx.computeOnClient(mc -> pe.patternSize());
		boolean has = ctx.computeOnClient(mc -> pe.hasPattern());
		r.check("pattern non vide (≥ 50 ticks)", has && size >= 50 && size >= recTicks, "taille " + size + ", ticks enregistrés " + recTicks);
		r.check("dernier.apt écrit", Files.isRegularFile(ModPaths.file("dernier.apt")));
		if (!has) return;

		// téléportation à côté du départ, puis lecture unique (recalage à pied jusqu'au départ)
		double tx = start[0] + 1.5, ty = start[1], tz = start[2] - 1.0;
		sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.4f %.4f %.4f", tx, ty, tz));
		boolean tp = TestUtil.waitUntil(ctx, mc -> mc.player != null
			&& Math.hypot(mc.player.getX() - tx, mc.player.getZ() - tz) < 0.05, 60);
		r.check("téléporté à 1,8 bloc du départ", tp, TestUtil.fmt(TestUtil.pos(ctx)));
		ctx.waitTicks(5);

		TestUtil.press(ctx, KeyDef.PLAY);
		boolean started = TestUtil.waitUntil(ctx, mc -> AutoPaterns.get().automation().phase().isPlayback(), 10);
		r.check("F9 : lecture unique démarrée (recalage)", started, String.valueOf(TestUtil.phase(ctx)));
		if (!started) return;
		boolean playing = TestUtil.waitUntil(ctx, mc -> AutoPaterns.get().automation().phase() == Phase.PLAYING, 600);
		r.check("recalage terminé : phase PLAYING", playing, String.valueOf(TestUtil.phase(ctx)));
		if (playing) {
			ctx.waitTicks(8);
			TestUtil.shot(ctx, r, "04b-hud-lecture");
			r.check("HUD actif pendant la lecture (non coupé)", !ctx.computeOnClient(mc -> AutoPaterns.get().hud().isBroken()));
		}
		boolean done = TestUtil.waitUntil(ctx, mc -> AutoPaterns.get().automation().phase() == Phase.IDLE, 900);
		r.check("lecture unique terminée (phase IDLE)", done, String.valueOf(TestUtil.phase(ctx)));
		if (!done) {
			TestUtil.ensureIdle(ctx, r);
			return;
		}
		double[] fin = TestUtil.pos(ctx);
		double d = TestUtil.dist(fin, end);
		r.check("position finale à moins de 0,30 bloc de la fin d'enregistrement", d < 0.30,
			"écart " + TestUtil.fmt(d) + " : " + TestUtil.fmt(fin) + " / " + TestUtil.fmt(end));
		double[] dev = ctx.computeOnClient(mc -> new double[] {pe.repMaxDev(), pe.lastDev(), pe.repetitions()});
		r.check("écart maximal de la répétition faible (< 0,5 bloc)", dev[0] < 0.5,
			"max " + TestUtil.fmt(dev[0]) + ", dernier " + TestUtil.fmt(dev[1]));
		r.equal("une répétition jouée", 1, (int) dev[2]);
		r.equal("aucune touche restée enfoncée après la lecture", "", ctx.computeOnClient(TestUtil::keysDown));
	}

	private static void patternFiles(ClientGameTestContext ctx, Report r) {
		String a = "gametest_pattern", b = "gametest_pattern_2";
		Path pd = ModPaths.file("patterns");
		ctx.runOnClient(mc -> {
			PatternEngine pe = AutoPaterns.get().patterns();
			for (String n : new String[] {a, b}) if (Files.exists(pd.resolve(n + ".apt"))) pe.uiDelete(n);
		});
		Object[] res = ctx.computeOnClient(mc -> {
			PatternEngine pe = AutoPaterns.get().patterns();
			int size = pe.patternSize();
			String save = pe.uiSave(a);
			boolean listed = pe.uiList().stream().anyMatch(i -> i.name().equals(a) && i.ticks() == size);
			String cur1 = pe.currentName();
			String ren = pe.uiRename(a, b);
			String cur2 = pe.currentName();
			String load = pe.uiLoad(b);
			int size2 = pe.patternSize();
			String cur3 = pe.currentName();
			return new Object[] {size, save, listed, cur1, ren, cur2, load, size2, cur3};
		});
		r.check("sauvegarde sous un nom (uiSave)", String.valueOf(res[1]).startsWith("✔") && Files.isRegularFile(pd.resolve(a + ".apt")), String.valueOf(res[1]));
		r.check("pattern listé avec son nombre de ticks (uiList)", Boolean.TRUE.equals(res[2]));
		r.equal("pattern courant = nom sauvegardé", a, res[3]);
		r.check("renommage (uiRename)", String.valueOf(res[4]).startsWith("✔") && !Files.exists(pd.resolve(a + ".apt"))
			&& Files.isRegularFile(pd.resolve(b + ".apt")), String.valueOf(res[4]));
		r.equal("pattern courant suit le renommage", b, res[5]);
		r.check("rechargement (uiLoad)", String.valueOf(res[6]).startsWith("✔"), String.valueOf(res[6]));
		r.equal("même nombre de ticks après rechargement", res[0], res[7]);
		r.equal("pattern courant = nom rechargé", b, res[8]);
		String del = ctx.computeOnClient(mc -> AutoPaterns.get().patterns().uiDelete(b));
		r.check("suppression (uiDelete)", del.startsWith("✔") && !Files.exists(pd.resolve(b + ".apt")), del);
		boolean gone = ctx.computeOnClient(mc -> AutoPaterns.get().patterns().uiList().stream().noneMatch(i -> i.name().equals(b)));
		r.check("pattern supprimé absent de la liste", gone);
		String again = ctx.computeOnClient(mc -> AutoPaterns.get().patterns().uiDelete(b));
		r.check("supprimer un pattern absent : erreur", again.startsWith("✖"), again);
	}

	// =========================================================================================== 5

	private static void loopAndStop(ClientGameTestContext ctx, Report r) {
		TestUtil.closeScreens(ctx);
		TestUtil.ensureIdle(ctx, r);
		boolean has = ctx.computeOnClient(mc -> AutoPaterns.get().patterns().hasPattern());
		if (!r.check("un pattern est chargé", has)) return;
		boolean perfWas = ctx.computeOnClient(mc -> AutoPaterns.get().performance().isEnabled());
		ctx.runOnClient(mc -> AutoPaterns.get().performance().setEnabled(false));
		try {
			TestUtil.press(ctx, KeyDef.LOOP);
			boolean playing = TestUtil.waitUntil(ctx, mc -> AutoPaterns.get().automation().phase() == Phase.PLAYING, 900);
			r.check("F8 : boucle démarrée, recalage terminé (phase PLAYING)", playing, String.valueOf(TestUtil.phase(ctx)));
			if (!playing) {
				TestUtil.ensureIdle(ctx, r);
				return;
			}
			r.check("mode boucle", ctx.computeOnClient(mc -> AutoPaterns.get().patterns().isLoop()));
			ctx.waitTicks(10);
			r.check("touche « avancer » simulée pendant la lecture", ctx.computeOnClient(mc -> mc.options.keyUp.isDown()),
				ctx.computeOnClient(TestUtil::keysDown));
			TestUtil.press(ctx, KeyDef.STOP); // un seul tick attendu
			r.equal("F6 : phase IDLE dès le tick suivant", Phase.IDLE, TestUtil.phase(ctx));
			r.equal("F6 : aucune touche restée enfoncée", "", ctx.computeOnClient(TestUtil::keysDown));
			ctx.waitTicks(5);
			r.equal("5 ticks plus tard : toujours au repos", Phase.IDLE, TestUtil.phase(ctx));
			r.equal("5 ticks plus tard : aucune touche enfoncée", "", ctx.computeOnClient(TestUtil::keysDown));
			r.check("mode performance non resté actif", !ctx.computeOnClient(mc -> AutoPaterns.get().performance().isActive()));
		} finally {
			ctx.runOnClient(mc -> AutoPaterns.get().performance().setEnabled(perfWas));
		}
	}

	// =========================================================================================== 7

	private static void waypoints(ClientGameTestContext ctx, TestSingleplayerContext sp, Report r) {
		TestUtil.closeScreens(ctx);
		TestUtil.ensureIdle(ctx, r);
		// pattern de waypoints « miner » activé, défini comme dernier lancé (pour F12)
		WaypointPattern p = ctx.computeOnClient(mc -> {
			Waypoints st = AutoPaterns.get().waypoints().state();
			if (st.active() != null) st.stop();
			WaypointPattern np = st.create();
			st.rename(np, st.uniqueName("GT minage"));
			np.mine = true;
			np.click = false;
			np.hold = false;
			np.place = false;
			st.start(np);
			st.stop();
			return np;
		});
		try {
			r.check("pattern de waypoints créé avec « miner »", p.mine && p.points.isEmpty(), p.name);
			TestUtil.press(ctx, KeyDef.WP_RECORD);
			boolean recording = TestUtil.waitUntil(ctx, mc -> AutoPaterns.get().waypoints().state().active() == p, 10);
			r.check("F12 : enregistrement des waypoints démarré (isRecording)", recording
				&& ctx.computeOnClient(mc -> AutoPaterns.get().waypoints().isRecording()));

			BlockPos base = ctx.computeOnClient(mc -> BlockPos.containing(mc.player.position()));
			BlockPos target = base.offset(2, 0, 0);
			BlockPos hl = base.offset(2, 0, 2);
			sp.getServer().runCommand(cmdBlock("setblock", target, "minecraft:stone"));
			sp.getServer().runCommand(cmdBlock("setblock", hl, "minecraft:obsidian"));
			boolean placed = TestUtil.waitUntil(ctx, mc -> mc.level.getBlockState(target).is(Blocks.STONE)
				&& mc.level.getBlockState(hl).is(Blocks.OBSIDIAN), 60);
			r.check("blocs de test posés (pierre à miner, obsidienne à surligner)", placed);

			// minage par l'entrée simulée (clic gauche, mode créatif : cassage immédiat)
			ctx.getInput().lookAt(target);
			ctx.waitTicks(2);
			ctx.getInput().pressKey(o -> o.keyAttack);
			boolean broken = TestUtil.waitUntil(ctx, mc -> mc.level.getBlockState(target).isAir(), 20);
			String via = "entrée simulée (attaque)";
			if (!broken) {
				r.info("bloc non cassé par l'entrée simulée : essai par MultiPlayerGameMode#startDestroyBlock");
				ctx.runOnClient(mc -> mc.gameMode.startDestroyBlock(target, Direction.UP));
				broken = TestUtil.waitUntil(ctx, mc -> mc.level.getBlockState(target).isAir(), 20);
				via = "gameMode.startDestroyBlock";
			}
			r.check("bloc cassé", broken, via);
			ctx.waitTicks(3);
			String pts = ctx.computeOnClient(mc -> String.valueOf(p.points));
			boolean marked = ctx.computeOnClient(mc -> p.points.stream().anyMatch(w -> w.x() == target.getX()
				&& w.y() == target.getY() && w.z() == target.getZ() && w.type() == Waypoint.MINE));
			r.check("waypoint « miné » créé à la position du bloc", marked, pts);
			TestUtil.shot(ctx, r, "07a-waypoint-mine");

			TestUtil.press(ctx, KeyDef.WP_RECORD);
			r.check("F12 : enregistrement des waypoints arrêté", TestUtil.waitUntil(ctx,
				mc -> !AutoPaterns.get().waypoints().isRecording(), 10));
			Path wpFile = ModPaths.file("waypoints.txt");
			r.check("waypoints.txt sauvegardé avec le pattern", Files.isRegularFile(wpFile)
				&& TestUtil.read(wpFile).contains("P\t" + p.name + "\t"));

			// surbrillance : viser l'obsidienne et presser N
			Object[] saved = ctx.computeOnClient(mc -> {
				BlockFinder f = AutoPaterns.get().waypoints().finder();
				return new Object[] {new LinkedHashSet<>(f.targets), f.enabled};
			});
			try {
				ctx.getInput().lookAt(hl);
				ctx.waitTicks(2);
				TestUtil.press(ctx, KeyDef.FINDER_SELECT);
				boolean lit = TestUtil.waitUntil(ctx, mc -> AutoPaterns.get().waypoints().highlightCount() > 0, 200);
				String tg = ctx.computeOnClient(mc -> String.valueOf(AutoPaterns.get().waypoints().finder().targets));
				r.check("N sur un bloc visé : highlightCount() > 0", lit,
					"cibles " + tg + ", résultats " + ctx.computeOnClient(mc -> AutoPaterns.get().waypoints().highlightCount()));
				ctx.waitTicks(3);
				TestUtil.shot(ctx, r, "07b-surbrillance");
			} finally {
				ctx.runOnClient(mc -> {
					BlockFinder f = AutoPaterns.get().waypoints().finder();
					f.targets.clear();
					@SuppressWarnings("unchecked")
					Set<String> old = (Set<String>) saved[0];
					f.targets.addAll(old);
					f.enabled = (Boolean) saved[1];
					f.restart();
					AutoPaterns.get().waypoints().state().markDirty();
				});
			}
		} finally {
			ctx.runOnClient(mc -> {
				Waypoints st = AutoPaterns.get().waypoints().state();
				if (st.active() == p) st.stop();
				st.delete(p);
			});
		}
	}

	private static String cmdBlock(String cmd, BlockPos pos, String block) {
		return cmd + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + block;
	}

	// =========================================================================================== 6

	private static void fishing(ClientGameTestContext ctx, TestSingleplayerContext sp, Report r) {
		TestUtil.closeScreens(ctx);
		TestUtil.ensureIdle(ctx, r);
		Path errFile = ModPaths.file("peche-erreur.txt");
		long errBefore = Files.isRegularFile(errFile) ? TestUtil.read(errFile).length() : -1;

		BlockPos base = ctx.computeOnClient(mc -> BlockPos.containing(mc.player.position()));
		BlockPos w1 = base.offset(2, -1, -2), w2 = base.offset(6, -1, 2);
		sp.getServer().runCommand("fill " + w1.getX() + " " + w1.getY() + " " + w1.getZ() + " "
			+ w2.getX() + " " + w2.getY() + " " + w2.getZ() + " minecraft:water");
		sp.getServer().runCommand("give @a minecraft:fishing_rod");
		boolean rod = TestUtil.waitUntil(ctx, mc -> {
			var inv = mc.player.getInventory();
			for (int i = 0; i < 9; i++) {
				ItemStack st = inv.getItem(i);
				if (!st.isEmpty() && st.getItem().getDescriptionId().endsWith("fishing_rod")) {
					inv.setSelectedSlot(i);
					return true;
				}
			}
			return false;
		}, 60);
		r.check("canne à pêche donnée (give) et tenue en main", rod);
		if (!rod) return;
		ctx.getInput().lookAt(base.offset(4, -1, 0));
		ctx.waitTicks(3);

		TestUtil.press(ctx, KeyDef.FISHING);
		boolean on = TestUtil.waitUntil(ctx, mc -> AutoPaterns.get().automation().phase() == Phase.FISHING, 10);
		r.check("K : pêche lancée (phase FISHING)", on, String.valueOf(TestUtil.phase(ctx)));
		if (!on) return;
		r.check("ligne lancée (bouchon présent)", TestUtil.waitUntil(ctx, mc -> mc.player.fishing != null, 100));

		boolean probe = true;
		boolean captured;
		try {
			captured = TestUtil.waitUntil(ctx, mc -> FishingTestAccess.captures(AutoPaterns.get().fishing()) > 0, 400);
		} catch (LinkageError e) {
			probe = false;
			r.fail("accès de test au robot de pêche indisponible (même chargeur de classes requis)", e);
			ctx.waitTicks(200);
			captured = false;
		}
		ctx.waitTicks(5);
		TestUtil.shot(ctx, r, "06-peche");
		if (probe) {
			String[] st = ctx.computeOnClient(mc -> {
				FishingService fs = AutoPaterns.get().fishing();
				return new String[] {
					Integer.toString(FishingTestAccess.captures(fs)), Integer.toString(FishingTestAccess.errors(fs)),
					Integer.toString(FishingTestAccess.timeouts(fs)), Integer.toString(FishingTestAccess.analysisErrors(fs)),
					FishingTestAccess.state(fs), Texts.strip(FishingTestAccess.method(fs)),
					Texts.strip(FishingTestAccess.calibration(fs)), FishingTestAccess.lastError(fs), FishingTestAccess.failure(fs)};
			});
			String detail = "captures " + st[0] + ", état " + st[4] + ", méthode « " + st[5] + " », calibrage « " + st[6] + " »";
			r.check("au moins une capture d'écran analysée", captured, detail);
			r.check("aucune erreur de capture ni délai dépassé", "0".equals(st[1]) && "0".equals(st[2]),
				"erreurs " + st[1] + ", délais " + st[2] + ", erreurs d'analyse " + st[3] + ", dernière : " + st[7]);
			r.check("aucun échec du robot", st[8].isEmpty(), st[8]);
		}
		r.equal("pêche toujours en cours", Phase.FISHING, TestUtil.phase(ctx));
		long errAfter = Files.isRegularFile(errFile) ? TestUtil.read(errFile).length() : -1;
		r.check("peche-erreur.txt inchangé (aucune erreur de capture journalisée)", errAfter == errBefore,
			errAfter < 0 ? "absent" : TestUtil.read(errFile));
		r.info("HUD pêche : " + ctx.computeOnClient(mc -> Texts.strip(String.valueOf(AutoPaterns.get().fishing().detail()))));

		TestUtil.press(ctx, KeyDef.FISHING);
		r.check("K : pêche arrêtée (phase IDLE)", TestUtil.waitUntil(ctx, mc -> AutoPaterns.get().automation().phase() == Phase.IDLE, 10),
			String.valueOf(TestUtil.phase(ctx)));
		r.equal("touche « utiliser » relâchée", "", ctx.computeOnClient(TestUtil::keysDown));
		TestUtil.ensureIdle(ctx, r);
	}

	// =========================================================================================== 9

	private static void hudLayout(ClientGameTestContext ctx, Report r) {
		ctx.runOnClient(mc -> {
			HudLayout lay = AutoPaterns.get().hud().layout();
			r.check("HUD non coupé par une erreur de rendu", !AutoPaterns.get().hud().isBroken());
			HudConf c = lay.conf(HudModule.ECART);
			c.enabled = false;
			c.fx = 0.25;
			c.fy = 0.5;
			c.scale = 150;
			c.background = false;
			c.always = true;
			c.opacity = 60;
			c.width = 120;
			c.setShows("max", false);
			lay.setOpacity(80);
			lay.changed();
			lay.flush();
			String text = TestUtil.read(lay.file());
			r.check("hud.txt écrit avec la ligne ECART modifiée",
				text.contains("\nECART=0,0.2500,0.5000,150,0,1,60,120,-max\n") && text.contains("\nglobal.opacity=80\n"), text);
			HudLayout fresh = new HudLayout();
			fresh.load();
			List<String> diffs = new ArrayList<>();
			for (HudModule m : HudModule.values()) {
				if (!FormatTests.sameConf(lay.conf(m), fresh.conf(m))) {
					diffs.add(FormatTests.confText(lay.conf(m)) + " ≠ " + FormatTests.confText(fresh.conf(m)));
				}
			}
			if (lay.opacity() != fresh.opacity()) diffs.add("opacité " + lay.opacity() + " ≠ " + fresh.opacity());
			r.check("relecture de hud.txt identique (position, échelle, visibilité, opacité, largeur, informations)",
				diffs.isEmpty(), String.join(" ; ", diffs));

			lay.resetAll();
			lay.flush();
			HudLayout def = new HudLayout();
			def.load();
			List<String> d2 = new ArrayList<>();
			for (HudModule m : HudModule.values()) {
				if (!FormatTests.sameConf(HudLayout.defaults(m), def.conf(m))) d2.add(FormatTests.confText(def.conf(m)));
			}
			if (def.opacity() != 100) d2.add("opacité " + def.opacity());
			r.check("réinitialisation : disposition par défaut sauvegardée et relue", d2.isEmpty(), String.join(" ; ", d2));
		});
		TestUtil.shot(ctx, r, "09-hud-par-defaut");
	}

	// =========================================================================================== 10

	private static void keys(ClientGameTestContext ctx, Report r) {
		TestUtil.closeScreens(ctx);
		TestUtil.ensureIdle(ctx, r);
		Path optionsFile = FabricLoader.getInstance().getGameDir().resolve("options.txt");
		try {
			// conflit : RECORD sur la touche de l'inventaire du jeu
			Object[] a = ctx.computeOnClient(mc -> {
				KeyMapping rec = Keys.mapping(KeyDef.RECORD);
				KeyMapping inv = mc.options.keyInventory;
				InputConstants.Key invKey = KeyMappingHelper.getBoundKeyOf(inv);
				Keys.rebind(rec, invKey);
				return new Object[] {KeyMappingHelper.getBoundKeyOf(rec).equals(invKey), Keys.label(KeyDef.RECORD), Keys.label(inv),
					Keys.conflicts(rec).contains(inv), "key_" + rec.getName() + ":" + rec.saveString(), String.valueOf(Keys.conflicts(rec).size())};
			});
			r.check("rebind de RECORD sur la touche de l'inventaire", Boolean.TRUE.equals(a[0]));
			r.equal("Keys.label suit la nouvelle touche", a[2], a[1]);
			r.check("conflit détecté avec la touche du jeu (Keys.conflicts)", Boolean.TRUE.equals(a[3]), "conflits : " + a[5]);
			r.check("options.txt sauvegardé avec la nouvelle touche", Files.isRegularFile(optionsFile)
				&& TestUtil.read(optionsFile).contains((String) a[4]), (String) a[4]);

			// touche libre : plus de conflit, et la touche agit vraiment
			Object[] b = ctx.computeOnClient(mc -> {
				KeyMapping rec = Keys.mapping(KeyDef.RECORD);
				InputConstants.Key free = InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_KP_7);
				Keys.rebind(rec, free);
				String expected = free.getDisplayName().getString();
				if (expected.length() > 16) expected = expected.substring(0, 15) + "…";
				return new Object[] {Keys.label(KeyDef.RECORD), expected, Keys.conflicts(rec).isEmpty()};
			});
			r.equal("Keys.label après rebind sur une touche libre", b[1], b[0]);
			r.check("aucun conflit sur une touche libre", Boolean.TRUE.equals(b[2]));
			ctx.waitTicks(4);
			ctx.getInput().pressKey(GLFW.GLFW_KEY_KP_7);
			boolean acted = TestUtil.waitUntil(ctx, mc -> AutoPaterns.get().automation().phase() != Phase.IDLE, 5);
			r.check("la nouvelle touche déclenche l'enregistrement (ou sa préparation)", acted, String.valueOf(TestUtil.phase(ctx)));
			TestUtil.press(ctx, KeyDef.STOP);
			TestUtil.waitUntil(ctx, mc -> AutoPaterns.get().automation().phase() == Phase.IDLE, 10);
		} finally {
			ctx.runOnClient(mc -> Keys.resetAll());
		}
		Object[] c = ctx.computeOnClient(mc -> {
			List<String> wrong = new ArrayList<>();
			for (KeyDef d : KeyDef.values()) {
				KeyMapping km = Keys.mapping(d);
				InputConstants.Key def = InputConstants.Type.KEYSYM.getOrCreate(d.defaultKey);
				if (!KeyMappingHelper.getBoundKeyOf(km).equals(def) && !(d.defaultKey == GLFW.GLFW_KEY_UNKNOWN && km.isUnbound())) {
					wrong.add(d + "=" + Keys.label(d));
				}
			}
			return new Object[] {wrong, Keys.conflicts(Keys.mapping(KeyDef.RECORD)).isEmpty(), Keys.label(KeyDef.RECORD)};
		});
		r.check("Keys.resetAll : toutes les touches du mod par défaut", ((List<?>) c[0]).isEmpty(), String.valueOf(c[0]));
		r.check("RECORD de nouveau sans conflit", Boolean.TRUE.equals(c[1]), String.valueOf(c[2]));
		TestUtil.ensureIdle(ctx, r);
	}

	// =========================================================================================== 11 / 12

	/** Options graphiques observées (getters Mojang confirmés). */
	private static final Map<String, Function<Options, OptionInstance<?>>> OPTIONS = new LinkedHashMap<>();

	static {
		OPTIONS.put("renderDistance", Options::renderDistance);
		OPTIONS.put("simulationDistance", Options::simulationDistance);
		OPTIONS.put("graphicsPreset", Options::graphicsPreset);
		OPTIONS.put("particles", Options::particles);
		OPTIONS.put("cloudStatus", Options::cloudStatus);
		OPTIONS.put("ambientOcclusion", Options::ambientOcclusion);
		OPTIONS.put("entityShadows", Options::entityShadows);
		OPTIONS.put("improvedTransparency", Options::improvedTransparency);
		OPTIONS.put("biomeBlendRadius", Options::biomeBlendRadius);
		OPTIONS.put("entityDistanceScaling", Options::entityDistanceScaling);
		OPTIONS.put("vignette", Options::vignette);
		OPTIONS.put("chunkSectionFadeInTime", Options::chunkSectionFadeInTime);
		OPTIONS.put("weatherRadius", Options::weatherRadius);
		OPTIONS.put("mipmapLevels", Options::mipmapLevels);
		OPTIONS.put("cutoutLeaves", Options::cutoutLeaves);
		OPTIONS.put("maxAnisotropyBit", Options::maxAnisotropyBit);
		OPTIONS.put("textureFiltering", Options::textureFiltering);
		OPTIONS.put("framerateLimit", Options::framerateLimit);
		OPTIONS.put("inactivityFpsLimit", Options::inactivityFpsLimit);
		OPTIONS.put("enableVsync", Options::enableVsync);
		OPTIONS.put("guiScale", Options::guiScale);
	}

	private static Map<String, Object> snapshot(Minecraft mc) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (Map.Entry<String, Function<Options, OptionInstance<?>>> e : OPTIONS.entrySet()) {
			m.put(e.getKey(), e.getValue().apply(mc.options).get());
		}
		return m;
	}

	private static List<String> diff(Map<String, Object> a, Map<String, Object> b) {
		List<String> out = new ArrayList<>();
		for (String k : a.keySet()) {
			if (!Objects.equals(a.get(k), b.get(k))) out.add(k + " : " + a.get(k) + " → " + b.get(k));
		}
		return out;
	}

	private static Path backupFile() {
		return ModPaths.file("graphismes-origine.txt");
	}

	/** Options plus coûteuses que les défauts des tests, pour que le mode performance ait quelque chose à changer. */
	private static void raiseGraphics(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> {
			Options o = mc.options;
			o.renderDistance().set(12);
			o.cloudStatus().set(CloudStatus.FANCY);
			o.entityShadows().set(true);
			o.vignette().set(true);
			o.ambientOcclusion().set(true);
			o.particles().set(ParticleStatus.ALL);
			o.biomeBlendRadius().set(2);
			o.entityDistanceScaling().set(1.0);
		});
		ctx.waitTicks(2);
	}

	private static void performanceApplyRestore(ClientGameTestContext ctx, Report r) {
		PerformanceMode perf = AutoPaterns.get().performance();
		ctx.runOnClient(mc -> {
			if (perf.isActive()) perf.restore(mc);
		});
		raiseGraphics(ctx);
		r.check("aucune sauvegarde graphique avant l'activation", !Files.exists(backupFile()));
		Map<String, Object> before = ctx.computeOnClient(AutoPaternsClientGameTest::snapshot);
		r.info("valeurs notées : " + before);
		boolean enabledWas = ctx.computeOnClient(mc -> perf.isEnabled());
		CompletableFuture<?>[] wait = new CompletableFuture<?>[1];
		String details = ctx.computeOnClient(mc -> {
			perf.setEnabled(true);
			StringBuilder sb = new StringBuilder();
			wait[0] = perf.apply(mc, sb, perf.isEnabled());
			return sb.toString();
		});
		try {
			if (wait[0] != null) {
				r.check("rechargement des ressources terminé", TestUtil.waitUntil(ctx, mc -> wait[0].isDone(), 1200));
			}
			ctx.waitTicks(2);
			Map<String, Object> during = ctx.computeOnClient(AutoPaternsClientGameTest::snapshot);
			List<String> changed = diff(before, during);
			r.info("résumé : " + Texts.strip(details) + " · résultats : " + ctx.computeOnClient(mc -> String.valueOf(perf.results())));
			r.check("mode performance actif", ctx.computeOnClient(mc -> perf.isActive()));
			r.check("des options ont changé", !changed.isEmpty() && ctx.computeOnClient(mc -> perf.appliedCount()) > 0, String.valueOf(changed));
			r.check("fichier de restauration écrit (graphismes-origine.txt)", Files.isRegularFile(backupFile()));
			if (Files.isRegularFile(backupFile())) {
				String bk = TestUtil.read(backupFile());
				r.check("fichier de restauration : valeurs d'origine (avant modification)",
					bk.contains("viewDistance=I:12") && bk.contains("cloudRenderMode=E:FANCY"), bk);
			}
		} finally {
			ctx.runOnClient(mc -> {
				perf.restore(mc);
				perf.setEnabled(enabledWas);
			});
		}
		ctx.waitTicks(2);
		Map<String, Object> after = ctx.computeOnClient(AutoPaternsClientGameTest::snapshot);
		List<String> d = diff(before, after);
		r.check("restauration : égalité EXACTE avec les valeurs notées", d.isEmpty(), String.valueOf(d));
		r.check("mode performance inactif après restauration", !ctx.computeOnClient(mc -> perf.isActive()));
		r.check("fichier de restauration supprimé", !Files.exists(backupFile()));
		r.check("aucune restauration en attente", !ctx.computeOnClient(mc -> perf.hasPendingRestore()));
	}

	private static void performanceCrashRecovery(ClientGameTestContext ctx, Report r) {
		PerformanceMode perf = AutoPaterns.get().performance();
		raiseGraphics(ctx);
		Map<String, Object> before = ctx.computeOnClient(AutoPaternsClientGameTest::snapshot);
		Map<String, Object> during = ctx.computeOnClient(mc -> {
			perf.apply(mc, new StringBuilder(), true);
			return snapshot(mc);
		});
		r.check("mode performance appliqué", !diff(before, during).isEmpty() && Files.isRegularFile(backupFile()), String.valueOf(diff(before, during)));
		// « crash » : un nouveau module, sans aucun état en mémoire, démarre comme au lancement suivant du jeu
		// (onLoad → recoverAfterCrash, qui lit graphismes-origine.txt).
		ctx.runOnClient(mc -> new PerformanceMode().onLoad(mc, AutoPaterns.get().config().props()));
		ctx.waitTicks(2);
		Map<String, Object> recovered = ctx.computeOnClient(AutoPaternsClientGameTest::snapshot);
		List<String> d = diff(before, recovered);
		r.check("récupération : options remises EXACTEMENT aux valeurs d'avant", d.isEmpty(), String.valueOf(d));
		r.check("récupération : fichier de restauration supprimé", !Files.exists(backupFile()));
		// l'instance d'origine croit encore le mode actif : sa restauration ne doit rien casser
		ctx.runOnClient(perf::restore);
		ctx.waitTicks(1);
		List<String> d2 = diff(before, ctx.computeOnClient(AutoPaternsClientGameTest::snapshot));
		r.check("restauration ultérieure sans effet (valeurs inchangées)", d2.isEmpty() && !Files.exists(backupFile()), String.valueOf(d2));
	}

	private static void performanceRecovery2x(ClientGameTestContext ctx, Report r) {
		raiseGraphics(ctx); // 12, FANCY, ombres, vignette
		// fichier 2.x : sans en-tête, clés 2.x, encodage T:valeur
		TestUtil.write(backupFile(), "viewDistance=I:7\ncloudRenderMode=E:FAST\nentityShadows=B:false\nvignette=B:false\n");
		ctx.runOnClient(mc -> new PerformanceMode().onLoad(mc, AutoPaterns.get().config().props()));
		ctx.waitTicks(2);
		Object[] v = ctx.computeOnClient(mc -> new Object[] {mc.options.renderDistance().get(), mc.options.cloudStatus().get(),
			mc.options.entityShadows().get(), mc.options.vignette().get()});
		r.check("graphismes-origine.txt 2.x restauré au lancement (7, FAST, ombres off, vignette off)",
			Integer.valueOf(7).equals(v[0]) && v[1] == CloudStatus.FAST && Boolean.FALSE.equals(v[2]) && Boolean.FALSE.equals(v[3]),
			java.util.Arrays.toString(v));
		r.check("graphismes-origine.txt 2.x supprimé après restauration", !Files.exists(backupFile()));
	}
}
