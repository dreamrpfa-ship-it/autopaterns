package fr.autopaterns.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import org.lwjgl.glfw.GLFW;

import fr.autopaterns.core.AutoPaterns;
import fr.autopaterns.core.Guard;
import fr.autopaterns.core.Phase;
import fr.autopaterns.input.KeyDef;
import fr.autopaterns.input.Keys;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;

/** Outils communs des tests en jeu. Tout accès au jeu passe par {@code runOnClient} / {@code computeOnClient}. */
final class TestUtil {
	private TestUtil() {}

	/** Attend {@code predicate} (évalué sur le thread client) au plus {@code timeout} ticks ; false si délai dépassé. */
	static boolean waitUntil(ClientGameTestContext ctx, Predicate<Minecraft> predicate, int timeout) {
		try {
			ctx.waitFor(predicate, timeout);
			return true;
		} catch (AssertionError e) {
			return false;
		}
	}

	/** Instance du mod, ou null si elle n'existe pas (mod non initialisé). */
	static AutoPaterns apOrNull() {
		try {
			return AutoPaterns.get();
		} catch (IllegalStateException e) {
			return null;
		}
	}

	static Phase phase(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> AutoPaterns.get().automation().phase());
	}

	static KeyMapping mapping(ClientGameTestContext ctx, KeyDef d) {
		return ctx.computeOnClient(mc -> Keys.mapping(d));
	}

	/** Appuie sur un raccourci du mod par l'entrée simulée (touche réellement assignée), puis attend un tick. */
	static void press(ClientGameTestContext ctx, KeyDef d) {
		ctx.getInput().pressKey(mapping(ctx, d));
	}

	/** Position du joueur {x, y, z}. */
	static double[] pos(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> new double[] {mc.player.getX(), mc.player.getY(), mc.player.getZ()});
	}

	static double dist(double[] a, double[] b) {
		double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	static String fmt(double d) {
		return String.format(Locale.ROOT, "%.4f", d);
	}

	static String fmt(double[] p) {
		return "(" + fmt(p[0]) + ", " + fmt(p[1]) + ", " + fmt(p[2]) + ")";
	}

	/** Touches de jeu actuellement enfoncées parmi déplacements, saut, accroupi, sprint, attaque, utiliser. */
	static String keysDown(Minecraft mc) {
		Options o = mc.options;
		KeyMapping[] keys = {o.keyUp, o.keyDown, o.keyLeft, o.keyRight, o.keyJump, o.keyShift, o.keySprint, o.keyAttack, o.keyUse};
		List<String> down = new ArrayList<>();
		for (KeyMapping k : keys) if (k.isDown()) down.add(k.getName());
		return String.join(", ", down);
	}

	/**
	 * Clique (souris simulée, bouton gauche) au centre du widget visible et actif de l'écran courant dont le libellé
	 * vaut {@code label}. Renvoie null si le clic a eu lieu, sinon la liste des libellés trouvés (diagnostic).
	 */
	static String clickWidget(ClientGameTestContext ctx, String label) {
		Object found = ctx.computeOnClient(mc -> {
			Screen s = mc.gui.screen();
			if (s == null) return "aucun écran";
			List<String> seen = new ArrayList<>();
			for (var el : s.children()) {
				if (!(el instanceof AbstractWidget w)) continue;
				String msg = w.getMessage().getString();
				seen.add(msg + (w.visible ? "" : " (caché)") + (w.active ? "" : " (inactif)"));
				if (w.visible && w.active && label.equals(msg)) {
					var win = mc.getWindow();
					double sx = (double) win.getScreenWidth() / win.getGuiScaledWidth();
					double sy = (double) win.getScreenHeight() / win.getGuiScaledHeight();
					return new double[] {(w.getX() + w.getWidth() / 2.0) * sx, (w.getY() + w.getHeight() / 2.0) * sy};
				}
			}
			return "widgets : " + seen;
		});
		if (found instanceof double[] xy) {
			ctx.getInput().setCursorPos(xy[0], xy[1]);
			ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
			return null;
		}
		return String.valueOf(found);
	}

	/** Capture d'écran nommée {@code autopaterns-<name>} ; un échec est enregistré sans interrompre les tests. */
	static void shot(ClientGameTestContext ctx, Report r, String name) {
		try {
			Path p = ctx.takeScreenshot("autopaterns-" + name);
			r.info("capture d'écran : " + p.getFileName());
		} catch (RuntimeException | AssertionError e) {
			r.fail("capture d'écran " + name, e);
		}
	}

	/** Ferme tout écran ouvert. */
	static void closeScreens(ClientGameTestContext ctx) {
		if (ctx.computeOnClient(mc -> mc.gui.screen() != null)) {
			ctx.setScreen(() -> null);
			ctx.waitTick();
		}
	}

	/** Remet l'automate au repos si un test précédent l'a laissé actif (enregistré dans le rapport). */
	static void ensureIdle(ClientGameTestContext ctx, Report r) {
		Phase ph = phase(ctx);
		if (ph != Phase.IDLE) {
			r.info("automate trouvé en phase " + ph + " : arrêt avant la suite");
			ctx.runOnClient(mc -> AutoPaterns.get().automation().stopAll(mc, null));
			ctx.waitTick();
		}
	}

	/** Vérifie que le mod n'a pas été mis en pause par une erreur interne ; le réactive le cas échéant. */
	static void guardOk(ClientGameTestContext ctx, Report r) {
		String err = ctx.computeOnClient(mc -> Guard.disabled() ? String.valueOf(Guard.lastError()) : null);
		r.check("mod toujours actif (aucune erreur interne interceptée par Guard)", err == null, err);
		if (err != null) ctx.runOnClient(mc -> Guard.reenable());
	}

	/** Dossier de travail des tests hors serveur : {@code <jeu>/autopaterns/gametest-data}. */
	static Path dataDir() {
		Path d = FabricLoader.getInstance().getGameDir().resolve("autopaterns").resolve("gametest-data");
		try {
			Files.createDirectories(d);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return d;
	}

	static String read(Path p) {
		try {
			return Files.readString(p, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	static void write(Path p, String text) {
		try {
			Files.createDirectories(p.getParent());
			Files.writeString(p, text, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	static byte[] readBytesOrNull(Path p) {
		try {
			return Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Remet un fichier dans l'état {@code bytes} (null = supprimé). */
	static void restoreBytes(Path p, byte[] bytes) {
		try {
			if (bytes == null) Files.deleteIfExists(p);
			else Files.write(p, bytes);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	static void deleteIfExists(Path p) {
		try {
			Files.deleteIfExists(p);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
