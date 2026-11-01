package fr.autopaterns.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import fr.autopaterns.core.AutoPaterns;
import fr.autopaterns.core.ModPaths;
import fr.autopaterns.core.Phase;
import fr.autopaterns.core.Texts;
import fr.autopaterns.hud.HudConf;
import fr.autopaterns.hud.HudLayout;
import fr.autopaterns.hud.HudModule;
import fr.autopaterns.patterns.BreakEvent;
import fr.autopaterns.patterns.CameraTrack;
import fr.autopaterns.patterns.PatternEngine;
import fr.autopaterns.patterns.PatternOptions;
import fr.autopaterns.patterns.PatternStore;
import fr.autopaterns.patterns.TickFrame;
import fr.autopaterns.selling.SellRecipes;
import fr.autopaterns.selling.SellSteps;
import fr.autopaterns.selling.SellingService;
import fr.autopaterns.waypoints.BlockFinder;
import fr.autopaterns.waypoints.Waypoint;
import fr.autopaterns.waypoints.WaypointPattern;
import fr.autopaterns.waypoints.Waypoints;
import fr.autopaterns.waypoints.WaypointsTestAccess;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Tests des formats de fichiers (sans serveur) : ventes / corbeille et grammaire des étapes (point 8), migration des
 * données 2.x (point 13). Les fichiers d'exemple reprennent les exemples des specs 2.x ({@code /home/claude/spec}).
 * Les fichiers réels du mod touchés (hud.txt, ventes.txt, corbeille.txt) sont sauvegardés puis remis tels quels.
 */
final class FormatTests {
	private FormatTests() {}

	// =============================================================================================== exemples 2.x

	/** ventes.txt (spec 02 §3.3, verbatim). */
	static final String VENTES_2X = "# AutoPaterns : ventes capturées (item, puis les clics dans la boutique)\n"
		+ "item.minecraft.diamond\t22,1,0,c,item.minecraft.diamond,RGlhbWFudA;13,0,0,c,item.minecraft.lime_dye,VmVuZHJlIHRvdXQ\n"
		+ "block.minecraft.dirt\t40,0,1,p,block.minecraft.dirt,VGVycmU\n";

	/** Pattern 2.x le plus ancien (en-tête VOXORIA-MC 1, frames à 9 valeurs, fins de ligne CRLF). */
	static final String PATTERN_V1 = "VOXORIA-MC 1\r\n"
		+ "ticks 3\r\n"
		+ "0 0 0 0 90.0 12.5 100.5 -60.0 -20.5\r\n"
		+ "1 0 0 0 90.0 12.5 100.5 -60.0 -20.5\r\n"
		+ "65 1 0 2 90.5 12.0 100.6 -60.0 -20.3\r\n";

	/** VOXORIA-MC 2 : piste caméra, pas de blocs cassés ni d'options. */
	static final String PATTERN_V2 = "VOXORIA-MC 2\n"
		+ "ticks 2\n"
		+ "1 0 0 0 45.0 0.0 10.5 -60.0 10.5\n"
		+ "1 0 0 0 46.0 0.5 10.6 -60.0 10.6\n"
		+ "frames 3\n"
		+ "0.0 45.0 0.0\n"
		+ "0.5 45.5 0.25\n"
		+ "1.0 46.0 0.5\n";

	/** AUTOPATERN 3 : blocs cassés ; la 2e frame est une ligne « NaN NaN NaN -1 » réécrite par la 2.x. */
	static final String PATTERN_V3 = "AUTOPATERN 3\n"
		+ "ticks 3\n"
		+ "0 0 0 0 90.0 12.5 100.5 64.0 -20.5 0.0 -0.0784000015258789 0.0 1\n"
		+ "129 2 0 0 90.0 12.5 100.5 64.0 -20.5 NaN NaN NaN -1\n"
		+ "1 0 0 0 90.5 12.0 100.6 64.0 -20.3 0.1 -0.0784000015258789 0.2 3\n"
		+ "frames 0\n"
		+ "breaks 1\n"
		+ "1 100 64 -22 Block{minecraft:oak_log}[axis=y]\n";

	/** Options 2.x (19 lignes, ordre 2.x) avec des valeurs différentes des défauts. */
	static final List<String> V4_OPTIONS = List.of(
		"onError=COMMAND",
		"command=/home retour",
		"items=item.minecraft.diamond_pickaxe||||||||item.minecraft.bread",
		"refill=1,0,0,0,0,0,0,0,1",
		"sell=1",
		"sellBetween=1",
		"sellThreshold=3",
		"sellCommand=/shop",
		"sellSteps=item:droit ; nom:entièreté:gauche",
		"sellPage=suivant",
		"sellCategory=brown_dye",
		"sellDelay=6",
		"sellItems=item.minecraft.diamond|item.minecraft.emerald",
		"trash=1",
		"trashBetween=0",
		"trashThreshold=2",
		"trashCommand=/trash",
		"trashDelay=3",
		"trashItems=item.minecraft.rotten_flesh|item.minecraft.bone");

	/** AUTOPATERN 4 (spec 06 §3.2, options modifiées) : forme canonique, la réécriture doit être identique octet pour octet. */
	static final String PATTERN_V4 = "AUTOPATERN 4\n"
		+ "ticks 3\n"
		+ "0 0 0 0 90.0 12.5 100.5 64.0 -20.5 0.0 -0.0784000015258789 0.0 1\n"
		+ "1 0 0 0 90.0 12.5 100.5 64.0 -20.5 0.0 -0.0784000015258789 0.0 1\n"
		+ "65 1 0 0 90.5 12.0 100.6 64.0 -20.3 0.1 -0.0784000015258789 0.2 3\n"
		+ "frames 2\n"
		+ "0.0 90.0 12.5\n"
		+ "1.0 90.5 12.0\n"
		+ "breaks 1\n"
		+ "2 100 64 -22 Block{minecraft:stone}\n"
		+ "options 19\n"
		+ String.join("\n", V4_OPTIONS) + "\n";

	/** hud.txt 2.x (6 champs, en-tête 2.x), valeurs différentes des défauts. */
	static final String HUD_2X = "# AutoPaterns : modules du HUD (activé, x, y, taille %, fond, toujours visible)\n"
		+ "STATUT=1,0.0100,0.0200,150,0,1\n"
		+ "PROGRESSION=0,-1.0000,-1.0000,100,1,0\n"
		+ "ECART=1,0.5000,0.2500,75,1,0\n"
		+ "COMPTEURS=1,-1.0000,-1.0000,100,1,0\n"
		+ "INVENTAIRE=1,-1.0000,-1.0000,100,1,0\n"
		+ "BUTIN=1,0.9000,0.1000,200,1,1\n"
		+ "TOUCHES=1,1.0000,0.8000,100,1,0\n"
		+ "NOTIF=1,-1.0000,-1.0000,100,1,0\n";

	/** waypoints.txt 2.x (spec 04 §3.3) ; la ligne P « Coffres » est au format ancien à 11 champs. */
	static final String WAYPOINTS_2X = "# AutoPaterns waypoints v1\n"
		+ "L\tMine diamants\n"
		+ "F\t1\t32\t5\t1\t1\tminecraft.diamond_ore,minecraft.ancient_debris\n"
		+ "P\tMine diamants\t1\t0\t0\t0\t10\t0\t1\t128\t1\t0\t8\t1\n"
		+ "W\tplay.example.net\tminecraft:overworld\t-120\t-58\t340\tM\t1761998400000\n"
		+ "W\tplay.example.net\tminecraft:overworld\t-121\t-58\t341\tS\t1761998400731\n"
		+ "P\tCoffres\t0\t1\t0\t0\t10\t1\t3\t0\t1\n"
		+ "W\tsolo\tminecraft:overworld\t12\t64\t-8\tC\t1761998500000\n";

	// =============================================================================================== point 8

	/** Aller-retour ventes.txt / corbeille.txt (copies dans gametest-data, puis fichiers réels via le module). */
	static void sellFiles(ClientGameTestContext ctx, Report r) throws IOException {
		Path dir = TestUtil.dataDir().resolve("vente");
		Path ventes = dir.resolve("ventes.txt");
		Path corbeille = dir.resolve("corbeille.txt");
		Files.deleteIfExists(ventes);
		Files.deleteIfExists(corbeille);
		Files.deleteIfExists(ventes.resolveSibling("ventes.txt.bak"));
		Files.deleteIfExists(corbeille.resolveSibling("corbeille.txt.bak"));

		// --- ventes.txt 2.x + une ligne illisible (doit être ignorée sans perdre les autres)
		TestUtil.write(ventes, VENTES_2X + "item.minecraft.bad\tpas,un,clic\n");
		SellRecipes sr = new SellRecipes(ventes, false);
		r.equal("ventes.txt 2.x : nombre d'items lus (ligne illisible ignorée)", 2, sr.size());
		List<SellRecipes.Click> dia = sr.get("item.minecraft.diamond");
		r.check("ventes.txt : clics du diamant", dia != null && dia.size() == 2, String.valueOf(dia));
		if (dia != null && dia.size() == 2) {
			r.equal("clic 1 décodé (slot, bouton, action, menu, item, nom)",
				new SellRecipes.Click(22, 1, 0, false, "item.minecraft.diamond", "Diamant"), dia.get(0));
			r.equal("clic 2 décodé", new SellRecipes.Click(13, 0, 0, false, "item.minecraft.lime_dye", "Vendre tout"), dia.get(1));
			r.equal("ré-encodage identique au texte 2.x", "22,1,0,c,item.minecraft.diamond,RGlhbWFudA", dia.get(0).encode());
		}
		List<SellRecipes.Click> dirt = sr.get("block.minecraft.dirt");
		r.equal("clic maj+clic inventaire (action 1, p)",
			List.of(new SellRecipes.Click(40, 0, 1, true, "block.minecraft.dirt", "Terre")), dirt);

		// --- écriture (ajout d'un item avec accents et codes §) puis relecture
		sr.put("item.minecraft.emerald", List.of(new SellRecipes.Click(5, 1, 0, false, "item.minecraft.emerald", "§aÉmeraude, très chère; 100%")));
		String written = TestUtil.read(ventes);
		r.check("ventes.txt réécrit : lignes 2.x conservées à l'identique",
			written.contains(VENTES_2X.split("\n")[1] + "\n") && written.contains(VENTES_2X.split("\n")[2] + "\n"), written);
		r.check("ventes.txt réécrit : en-tête de vente", written.startsWith("# AutoPaterns : ventes capturées"));
		SellRecipes sr2 = new SellRecipes(ventes, false);
		r.equal("ventes.txt : relecture identique (ordre et contenu)", sr.all(), sr2.all());
		r.equal("ventes.txt : ordre d'insertion conservé", new ArrayList<>(sr.all().keySet()), new ArrayList<>(sr2.all().keySet()));

		// --- corbeille.txt 2.x (la 2.x y écrivait l'en-tête des ventes)
		String flesh = b64("Chair pourrie");
		String corbeille2x = "# AutoPaterns : ventes capturées (item, puis les clics dans la boutique)\n"
			+ "item.minecraft.rotten_flesh\t12,0,1,p,item.minecraft.rotten_flesh," + flesh + "\n";
		TestUtil.write(corbeille, corbeille2x);
		SellRecipes tr = new SellRecipes(corbeille, true);
		r.equal("corbeille.txt 2.x : clic lu",
			List.of(new SellRecipes.Click(12, 0, 1, true, "item.minecraft.rotten_flesh", "Chair pourrie")), tr.get("item.minecraft.rotten_flesh"));
		tr.put("item.minecraft.bone", List.of(new SellRecipes.Click(30, 0, 1, true, "item.minecraft.bone", "Os")));
		String tw = TestUtil.read(corbeille);
		r.check("corbeille.txt réécrit : en-tête propre à la corbeille (3.0)", tw.startsWith("# AutoPaterns : corbeilles capturées"), tw.split("\n")[0]);
		SellRecipes tr2 = new SellRecipes(corbeille, true);
		r.equal("corbeille.txt : relecture identique", tr.all(), tr2.all());

		// --- fichiers réels via le module (ventes.txt / corbeille.txt du dossier de jeu), puis remise en état
		Path realV = ModPaths.file("ventes.txt");
		Path realC = ModPaths.file("corbeille.txt");
		byte[] oldV = TestUtil.readBytesOrNull(realV);
		byte[] oldC = TestUtil.readBytesOrNull(realC);
		try {
			TestUtil.write(realV, VENTES_2X);
			TestUtil.write(realC, corbeille2x);
			// horodatage distinct : le module relit le fichier s'il a changé sur le disque
			FileTime future = FileTime.fromMillis(System.currentTimeMillis() + 10_000L);
			Files.setLastModifiedTime(realV, future);
			Files.setLastModifiedTime(realC, future);
			Object[] got = ctx.computeOnClient(mc -> {
				SellingService s = AutoPaterns.get().selling();
				return new Object[] {s.uiCaptureCount(false), s.uiHasCapture("item.minecraft.diamond", false),
					s.uiHasCapture("block.minecraft.dirt", false), s.uiCaptureCount(true),
					s.uiHasCapture("item.minecraft.rotten_flesh", true)};
			});
			r.check("module vente : ventes.txt 2.x relu (2 captures, diamant et terre)",
				Integer.valueOf(2).equals(got[0]) && Boolean.TRUE.equals(got[1]) && Boolean.TRUE.equals(got[2]), java.util.Arrays.toString(got));
			r.check("module vente : corbeille.txt 2.x relu (1 capture)",
				Integer.valueOf(1).equals(got[3]) && Boolean.TRUE.equals(got[4]), java.util.Arrays.toString(got));
		} finally {
			TestUtil.restoreBytes(realV, oldV);
			TestUtil.restoreBytes(realC, oldC);
		}
	}

	private static String b64(String s) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
	}

	/** Grammaire des étapes de vente (2.x + correction du « : » dans un nom). Sur le thread client (traductions). */
	static void sellSteps(ClientGameTestContext ctx, Report r) {
		ctx.runOnClient(mc -> {
			r.equal("étapes par défaut 2.x",
				List.of(new SellSteps.Step(SellSteps.ITEM, "", 1, 0), new SellSteps.Step(SellSteps.NAME, "entièreté", 0, 0)),
				SellSteps.parse(PatternOptions.DEFAULT_STEPS));
			r.equal("item / attendre / nom contenant « : » avec bouton",
				List.of(new SellSteps.Step(SellSteps.ITEM, "", 0, 0), new SellSteps.Step(SellSteps.WAIT, "", 0, 20),
					new SellSteps.Step(SellSteps.NAME, "Vendre: tout", 1, 0)),
				SellSteps.parse("item ; attendre:20 ; nom:Vendre: tout:droit"));
			r.equal("séparateur retour à la ligne, bouton omis, majuscules et accents",
				List.of(new SellSteps.Step(SellSteps.NAME, "Confirmer", 0, 0), new SellSteps.Step(SellSteps.ITEM, "", 1, 0)),
				SellSteps.parse("nom:Confirmer:\nITEM:Droite"));
			r.equal("attendre sans valeur = 10 ticks, borné à 200",
				List.of(new SellSteps.Step(SellSteps.WAIT, "", 0, 10), new SellSteps.Step(SellSteps.WAIT, "", 0, 200)),
				SellSteps.parse("attendre ; attendre:999"));
			for (String bad : new String[] {"", " ; ", "sauter:1", "item:milieu", "nom:", "attendre:x"}) {
				String err = null;
				try {
					SellSteps.parse(bad);
				} catch (IllegalArgumentException e) {
					err = e.getMessage();
				}
				r.check("étape invalide refusée : « " + bad + " »", err != null && !err.isBlank(), err);
			}
			r.equal("normalisation (codes §, casse, accents, espaces)", "emeraude verte", SellSteps.normalize("§aÉmeraude   Verte "));
			PatternOptions o = PatternOptions.parse(V4_OPTIONS);
			r.equal("PatternOptions : sérialisation 2.x stable", V4_OPTIONS, o.serialize());
			r.equal("PatternOptions : copie identique", o.serialize(), o.copy().serialize());
		});
	}

	/** uiTest sans aucun item : refus correct, rien ne démarre. */
	static void uiTestRefusal(ClientGameTestContext ctx, Report r) {
		String[] res = ctx.computeOnClient(mc -> {
			SellingService s = AutoPaterns.get().selling();
			String sell = s.uiTest(false, new PatternOptions());
			String sellPhase = AutoPaterns.get().automation().phase().name();
			String trash = s.uiTest(true, new PatternOptions());
			String trashPhase = AutoPaterns.get().automation().phase().name();
			return new String[] {sell, Texts.tr("autopaterns.selling.ui.nothingSell"), sellPhase,
				trash, Texts.tr("autopaterns.selling.ui.nothingTrash"), trashPhase};
		});
		r.equal("uiTest vente sans item : message de refus", res[1], res[0]);
		r.equal("uiTest vente : aucune vente démarrée", Phase.IDLE.name(), res[2]);
		r.equal("uiTest corbeille sans item : message de refus", res[4], res[3]);
		r.equal("uiTest corbeille : rien démarré", Phase.IDLE.name(), res[5]);
	}

	// =============================================================================================== point 13

	/** Patterns 2.x (v1 à v4) : lecture hors module (PatternStore sur un dossier de test), réécriture, relecture. */
	static void patternsOffline(Report r) throws IOException {
		Path dir = TestUtil.dataDir().resolve("patterns-store");
		PatternStore store = new PatternStore(dir);
		Files.createDirectories(store.patternsDir());
		String[][] samples = {{"v1", PATTERN_V1}, {"v2", PATTERN_V2}, {"v3", PATTERN_V3}, {"v4", PATTERN_V4}};
		for (String[] s : samples) {
			for (String n : new String[] {s[0], s[0] + "_rw", s[0] + "_rw2"}) {
				Files.deleteIfExists(store.patternsDir().resolve(n + ".apt"));
				Files.deleteIfExists(store.patternsDir().resolve(n + ".apt.bak"));
			}
			TestUtil.write(store.patternsDir().resolve(s[0] + ".apt"), s[1]);
		}

		PatternStore.Loaded v1 = store.loadNamed("v1");
		r.equal("VOXORIA-MC 1 (CRLF) : 3 frames", 3, v1.frames().size());
		r.equal("VOXORIA-MC 1 : frame 3 (touches, attaques, slot, angles, position)",
			new TickFrame(65, 1, 0, 2, 90.5f, 12.0f, 100.6, -60.0, -20.3, Double.NaN, Double.NaN, Double.NaN, -1), v1.frames().get(2));
		r.check("VOXORIA-MC 1 : pas de caméra, pas de blocs, options par défaut",
			v1.camera() == null && v1.breaks().isEmpty() && v1.options().serialize().equals(new PatternOptions().serialize()));

		PatternStore.Loaded v2 = store.loadNamed("v2");
		r.check("VOXORIA-MC 2 : 2 frames + 3 images caméra",
			v2.frames().size() == 2 && v2.camera() != null && v2.camera().size() == 3,
			"frames " + v2.frames().size() + ", caméra " + (v2.camera() == null ? "null" : v2.camera().size()));
		if (v2.camera() != null && v2.camera().size() == 3) {
			r.check("VOXORIA-MC 2 : image caméra 2 exacte",
				v2.camera().timeAt(1) == 0.5 && v2.camera().yawAt(1) == 45.5f && v2.camera().pitchAt(1) == 0.25f);
		}

		PatternStore.Loaded v3 = store.loadNamed("v3");
		r.equal("AUTOPATERN 3 : 3 frames (ligne « NaN NaN NaN -1 » de la 2.x récupérée)", 3, v3.frames().size());
		r.check("AUTOPATERN 3 : frame récupérée sans état physique (ancien format)",
			v3.frames().size() == 3 && !v3.frames().get(1).fullState() && v3.frames().get(1).keys() == 129
				&& v3.frames().get(1).attacks() == 2, v3.frames().size() == 3 ? String.valueOf(v3.frames().get(1)) : "");
		r.equal("AUTOPATERN 3 : bloc cassé (état avec propriétés)",
			List.of(new BreakEvent(1, 100, 64, -22, "Block{minecraft:oak_log}[axis=y]")), v3.breaks());

		PatternStore.Loaded v4 = store.loadNamed("v4");
		r.equal("AUTOPATERN 4 : options 2.x lues sans perte", V4_OPTIONS, v4.options().serialize());
		PatternOptions o4 = v4.options();
		r.check("AUTOPATERN 4 : options interprétées (OnError, items, remplacement, vente, corbeille)",
			o4.onError == PatternOptions.OnError.COMMAND && "/home retour".equals(o4.command)
				&& "item.minecraft.diamond_pickaxe".equals(o4.slotItems[0]) && "item.minecraft.bread".equals(o4.slotItems[8])
				&& o4.slotItems[1] == null && o4.refill[0] && o4.refill[8] && !o4.refill[4]
				&& o4.sellEnabled && o4.sellBetween && o4.sellThreshold == 3 && o4.sellDelay == 6 && o4.sellItems.size() == 2
				&& o4.trashEnabled && o4.trashThreshold == 2 && o4.trashItems.contains("item.minecraft.bone"),
			o4.toString());

		// réécriture (toujours en v4) puis relecture : aucune perte, et réécriture stable
		for (String[] s : samples) {
			String n = s[0];
			PatternStore.Loaded a = store.loadNamed(n);
			store.saveNamed(n + "_rw", a);
			PatternStore.Loaded b = store.loadNamed(n + "_rw");
			r.check(n + " : relecture après réécriture identique (frames, caméra, blocs, options)", sameLoaded(a, b), describe(a) + " / " + describe(b));
			store.saveNamed(n + "_rw2", b);
			String t1 = TestUtil.read(store.patternsDir().resolve(n + "_rw.apt"));
			String t2 = TestUtil.read(store.patternsDir().resolve(n + "_rw2.apt"));
			r.check(n + " : réécriture stable (deux écritures successives identiques)", t1.equals(t2));
			r.check(n + " : fichier réécrit au format AUTOPATERN 4 sans « NaN »", t1.startsWith("AUTOPATERN 4\n") && !t1.contains("NaN"));
		}
		r.equal("AUTOPATERN 4 canonique : réécriture identique octet pour octet", PATTERN_V4,
			TestUtil.read(store.patternsDir().resolve("v4_rw.apt")));
		String v1rw = TestUtil.read(store.patternsDir().resolve("v1_rw.apt"));
		r.check("VOXORIA-MC 1 : frames réécrites en 9 valeurs (pas de « NaN NaN NaN -1 »)",
			v1rw.contains("\n65 1 0 2 90.5 12.0 100.6 -60.0 -20.3\n"), v1rw);
	}

	/** Patterns 2.x chargés par l'API du module (uiLoad / uiSaveOptions / uiDelete) dans le vrai dossier patterns/. */
	static void patternsModule(ClientGameTestContext ctx, Report r) {
		Path pd = ModPaths.file("patterns");
		String[][] samples = {{"gt_migr_v1", PATTERN_V1, "3"}, {"gt_migr_v4", PATTERN_V4, "3"}};
		for (String[] s : samples) {
			TestUtil.deleteIfExists(pd.resolve(s[0] + ".apt.bak"));
			TestUtil.write(pd.resolve(s[0] + ".apt"), s[1]);
		}
		for (String[] s : samples) {
			String name = s[0];
			Object[] res = ctx.computeOnClient(mc -> {
				PatternEngine pe = AutoPaterns.get().patterns();
				String load = pe.uiLoad(name);
				return new Object[] {load, pe.patternSize(), pe.currentName(), pe.options().serialize(), pe.patternSummary()};
			});
			r.check("module : uiLoad(« " + name + " ») accepté", String.valueOf(res[0]).startsWith("✔"), String.valueOf(res[0]));
			r.equal("module : nombre de ticks de « " + name + " »", Integer.parseInt(s[2]), res[1]);
			r.equal("module : pattern courant", name, res[2]);
			List<String> expected = name.endsWith("v4") ? V4_OPTIONS : new PatternOptions().serialize();
			r.equal("module : options de « " + name + " »", expected, res[3]);
			r.info("résumé : " + res[4]);
			// réécriture par le module (enregistrement des options) puis relecture
			Object[] res2 = ctx.computeOnClient(mc -> {
				PatternEngine pe = AutoPaterns.get().patterns();
				String save = pe.uiSaveOptions(pe.uiOptions());
				String load = pe.uiLoad(name);
				return new Object[] {save, load, pe.patternSize(), pe.options().serialize()};
			});
			r.check("module : réécriture (uiSaveOptions) acceptée", String.valueOf(res2[0]).startsWith("✔"), String.valueOf(res2[0]));
			r.check("module : relecture après réécriture identique",
				String.valueOf(res2[1]).startsWith("✔") && res[1].equals(res2[2]) && expected.equals(res2[3]), java.util.Arrays.toString(res2));
			Path file = pd.resolve(name + ".apt");
			String text = Files.isRegularFile(file) ? TestUtil.read(file) : "(absent)";
			r.check("module : fichier réécrit en AUTOPATERN 4", text.startsWith("AUTOPATERN 4\n") && !text.contains("NaN"));
			String del = ctx.computeOnClient(mc -> AutoPaterns.get().patterns().uiDelete(name));
			r.check("module : suppression de « " + name + " »", del.startsWith("✔") && !Files.exists(pd.resolve(name + ".apt")), del);
		}
	}

	private static boolean sameLoaded(PatternStore.Loaded a, PatternStore.Loaded b) {
		if (!a.frames().equals(b.frames())) return false;
		if (!a.breaks().equals(b.breaks())) return false;
		if (!a.options().serialize().equals(b.options().serialize())) return false;
		CameraTrack ca = a.camera(), cb = b.camera();
		if (ca == null || cb == null) return ca == cb;
		if (ca.size() != cb.size()) return false;
		for (int i = 0; i < ca.size(); i++) {
			if (Double.compare(ca.timeAt(i), cb.timeAt(i)) != 0 || Float.compare(ca.yawAt(i), cb.yawAt(i)) != 0
				|| Float.compare(ca.pitchAt(i), cb.pitchAt(i)) != 0) return false;
		}
		return true;
	}

	private static String describe(PatternStore.Loaded l) {
		return l.frames().size() + " frames, caméra " + (l.camera() == null ? 0 : l.camera().size()) + ", " + l.breaks().size() + " blocs";
	}

	/** hud.txt 2.x (6 champs) : lecture, réécriture au format 3, relecture. Sur le thread client (pas de course avec le HUD). */
	static void hudMigration(ClientGameTestContext ctx, Report r) {
		ctx.runOnClient(mc -> {
			HudLayout live = AutoPaterns.get().hud().layout();
			live.flush(); // aucune écriture différée du HUD ne doit écraser le fichier de test
			Path f = live.file();
			byte[] old = TestUtil.readBytesOrNull(f);
			try {
				TestUtil.write(f, HUD_2X);
				HudLayout a = new HudLayout();
				a.load();
				HudConf st = a.conf(HudModule.STATUT);
				r.check("hud.txt 2.x : STATUT lu (activé, x, y, échelle, fond, toujours visible)",
					st.enabled && st.fx == 0.01 && st.fy == 0.02 && st.scale == 150 && !st.background && st.always,
					confText(st));
				r.check("hud.txt 2.x : PROGRESSION désactivée et automatique",
					!a.conf(HudModule.PROGRESSION).enabled && a.conf(HudModule.PROGRESSION).auto());
				HudConf ec = a.conf(HudModule.ECART);
				r.check("hud.txt 2.x : ECART (0,5 ; 0,25 ; 75 %)", ec.fx == 0.5 && ec.fy == 0.25 && ec.scale == 75, confText(ec));
				HudConf bu = a.conf(HudModule.BUTIN);
				r.check("hud.txt 2.x : BUTIN (200 %, toujours visible)", bu.scale == 200 && bu.always && bu.fx == 0.9, confText(bu));
				r.check("hud.txt 2.x : champs 3.0 absents → défauts (opacité 100, largeur auto)",
					st.opacity == 100 && st.width == 0 && a.opacity() == 100);
				a.save();
				String written = TestUtil.read(f);
				r.check("hud.txt réécrit au format 3 avec les 6 champs 2.x en tête de ligne",
					written.contains("# format 3") && written.contains("\nSTATUT=1,0.0100,0.0200,150,0,1,")
						&& written.contains("\nBUTIN=1,0.9000,0.1000,200,1,1,"), written);
				HudLayout b = new HudLayout();
				b.load();
				List<String> diffs = new ArrayList<>();
				for (HudModule m : HudModule.values()) {
					if (!sameConf(a.conf(m), b.conf(m))) diffs.add(m + " : " + confText(a.conf(m)) + " ≠ " + confText(b.conf(m)));
				}
				if (a.opacity() != b.opacity()) diffs.add("opacité globale");
				r.check("hud.txt : relecture après réécriture identique (8 modules)", diffs.isEmpty(), String.join(" ; ", diffs));
			} finally {
				TestUtil.restoreBytes(f, old);
			}
		});
	}

	static boolean sameConf(HudConf a, HudConf b) {
		if (a.enabled != b.enabled || Double.compare(a.fx, b.fx) != 0 || Double.compare(a.fy, b.fy) != 0 || a.scale != b.scale
			|| a.background != b.background || a.always != b.always || a.opacity != b.opacity || a.width != b.width) return false;
		for (HudModule.Info i : a.module.infos) if (a.shows(i.key()) != b.shows(i.key())) return false;
		return true;
	}

	static String confText(HudConf c) {
		StringBuilder sb = new StringBuilder();
		sb.append(c.module).append('[').append(c.enabled ? "on" : "off").append(' ').append(c.fx).append(',').append(c.fy)
			.append(' ').append(c.scale).append("% fond=").append(c.background).append(" toujours=").append(c.always)
			.append(" opacité=").append(c.opacity).append(" largeur=").append(c.width);
		for (HudModule.Info i : c.module.infos) sb.append(' ').append(c.shows(i.key()) ? '+' : '-').append(i.key());
		return sb.append(']').toString();
	}

	/** waypoints.txt 2.x (ligne P à 11 champs) : lecteur du module, réécriture, relecture ; puis import par l'API publique. */
	static void waypointsMigration(ClientGameTestContext ctx, Report r) {
		List<String> lines = List.of(WAYPOINTS_2X.split("\n"));
		List<String[]> other = new ArrayList<>();
		List<WaypointPattern> ps = WaypointsTestAccess.parse(lines, other);
		r.equal("waypoints.txt 2.x : 2 patterns lus", 2, ps.size());
		if (ps.size() == 2) {
			WaypointPattern a = ps.get(0), b = ps.get(1);
			r.check("pattern 14 champs : réglages lus",
				"Mine diamants".equals(a.name) && a.mine && !a.click && a.holdTicks == 10 && a.labelMode == 1 && a.maxDistance == 128
					&& a.visible && !a.dupes && a.similarRadius == 8 && a.throughWalls, WaypointsTestAccess.write(a));
			r.equal("pattern 14 champs : points (contexte, dimension, position, type, date)",
				List.of(new Waypoint("play.example.net", "minecraft:overworld", -120, -58, 340, 'M', 1761998400000L),
					new Waypoint("play.example.net", "minecraft:overworld", -121, -58, 341, 'S', 1761998400731L)), a.points);
			r.check("ancienne ligne P à 11 champs : valeurs migrées (dupes=0, rayon=0, à travers les murs=1)",
				"Coffres".equals(b.name) && !b.mine && b.click && b.colorIndex == 1 && b.labelMode == 3 && b.maxDistance == 0
					&& b.visible && !b.dupes && b.similarRadius == 0 && b.throughWalls, WaypointsTestAccess.write(b));
			r.equal("ancienne ligne P : point solo conservé",
				List.of(new Waypoint("solo", "minecraft:overworld", 12, 64, -8, 'C', 1761998500000L)), b.points);
		}
		String[] l = null, f = null;
		for (String[] o : other) {
			if (o.length > 0 && "L".equals(o[0])) l = o;
			if (o.length > 0 && "F".equals(o[0])) f = o;
		}
		r.check("ligne L (dernier pattern lancé) transmise", l != null && l.length >= 2 && "Mine diamants".equals(l[1]));
		BlockFinder finder = new BlockFinder();
		String fLine = "";
		if (f != null) {
			WaypointsTestAccess.readFinder(finder, f);
			fLine = WaypointsTestAccess.writeFinder(finder);
		}
		r.check("ligne F (surbrillance) lue",
			f != null && finder.enabled && finder.radius == 32 && finder.colorIndex == 5 && finder.variants && finder.throughWalls
				&& finder.targets.size() == 2, String.valueOf(finder.targets));
		r.equal("ligne F réécrite à l'identique", WAYPOINTS_2X.split("\n")[2] + "\n", fLine);

		// réécriture complète puis relecture
		StringBuilder sb = new StringBuilder(WaypointsTestAccess.header()).append('\n');
		sb.append("L\tMine diamants\n").append(fLine);
		for (WaypointPattern p : ps) sb.append(WaypointsTestAccess.write(p));
		String rewritten = sb.toString();
		List<WaypointPattern> again = WaypointsTestAccess.parse(List.of(rewritten.split("\n")), new ArrayList<>());
		StringBuilder sb2 = new StringBuilder(WaypointsTestAccess.header()).append('\n').append("L\tMine diamants\n").append(fLine);
		for (WaypointPattern p : again) sb2.append(WaypointsTestAccess.write(p));
		r.equal("waypoints.txt : relecture après réécriture identique", rewritten, sb2.toString());
		r.check("ancienne ligne P réécrite avec 14 champs",
			rewritten.contains("P\tCoffres\t0\t1\t0\t0\t10\t1\t3\t0\t1\t0\t0\t1\n"), rewritten);
		r.check("lignes P/W au format 14 champs reproduites à l'identique",
			rewritten.contains(WAYPOINTS_2X.split("\n")[3] + "\n" + WAYPOINTS_2X.split("\n")[4] + "\n" + WAYPOINTS_2X.split("\n")[5] + "\n"));

		// import par l'API publique du module (lignes L/F ignorées, noms rendus uniques), puis suppression
		Object[] imp = ctx.computeOnClient(mc -> {
			Waypoints st = AutoPaterns.get().waypoints().state();
			int before = st.patterns.size();
			String msg = st.importText(WAYPOINTS_2X);
			List<WaypointPattern> added = new ArrayList<>(st.patterns.subList(before, st.patterns.size()));
			StringBuilder d = new StringBuilder();
			boolean ok = added.size() == 2;
			if (ok) {
				WaypointPattern b = added.get(1);
				ok = b.click && !b.dupes && b.similarRadius == 0 && b.throughWalls && b.points.size() == 1
					&& added.get(0).points.size() == 2 && added.get(0).similarRadius == 8;
			}
			for (WaypointPattern p : added) {
				d.append(p.name).append('(').append(p.points.size()).append(") ");
				st.delete(p);
			}
			return new Object[] {msg, ok, d.toString(), st.patterns.size() == before};
		});
		r.check("import 2.x par l'API du module (importText)", Boolean.TRUE.equals(imp[1]), imp[0] + " · " + imp[2]);
		r.check("patterns importés supprimés (delete)", Boolean.TRUE.equals(imp[3]));
	}

	/** Contrôle qu'aucun fichier temporaire d'écriture atomique n'est resté dans le dossier du mod. */
	static void noTempFiles(Report r) throws IOException {
		List<String> tmp = new ArrayList<>();
		try (var s = Files.walk(ModPaths.root(), 3)) {
			s.filter(p -> p.getFileName().toString().endsWith(".tmp")).forEach(p -> tmp.add(ModPaths.root().relativize(p).toString()));
		}
		r.check("aucun fichier .tmp laissé par les écritures atomiques", tmp.isEmpty(), String.valueOf(tmp));
	}
}
