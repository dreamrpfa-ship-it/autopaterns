package fr.autopaterns.fishing;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.imageio.ImageIO;

import fr.autopaterns.core.Log;
import fr.autopaterns.core.ModPaths;
import fr.autopaterns.storage.AtomicFiles;
import net.minecraft.client.Minecraft;

/**
 * Fichiers de la pêche (mêmes emplacements et formats que la 2.x) :
 * <ul>
 *   <li>{@code autopaterns/peche-erreur.txt} : journal cumulé des erreurs de capture depuis le lancement ;</li>
 *   <li>{@code autopaterns/peche-diagnostic/peche-NN-<debut|jeu>.png} : images de diagnostic (RGB).</li>
 * </ul>
 * Différence 3.0 : les écritures se font sur un fil d'arrière-plan (plus d'à-coups sur le thread client) et
 * passent par {@link AtomicFiles}. Un seul fil : l'ordre des écritures est conservé.
 */
final class FishFiles {
	private static ExecutorService io;

	private FishFiles() {}

	static Path errorFile() {
		return ModPaths.file("peche-erreur.txt");
	}

	static Path diagDir() {
		return ModPaths.file("peche-diagnostic");
	}

	private static synchronized ExecutorService io() {
		if (io == null || io.isShutdown()) {
			io = Executors.newSingleThreadExecutor(r -> {
				Thread t = new Thread(r, "AutoPaterns-peche-fichiers");
				t.setDaemon(true);
				return t;
			});
		}
		return io;
	}

	private static void submit(String what, Runnable r) {
		try {
			io().execute(() -> {
				try {
					r.run();
				} catch (VirtualMachineError e) {
					throw e;
				} catch (Throwable t) {
					Log.error("pêche : " + what, t);
				}
			});
		} catch (RejectedExecutionException e) {
			Log.warn("pêche : écriture abandonnée (" + what + ")");
		}
	}

	/** Réécrit {@code peche-erreur.txt} en entier (UTF-8), comme la 2.x. */
	static void writeErrors(String content) {
		submit("peche-erreur.txt non écrit", () -> {
			try {
				AtomicFiles.writeString(errorFile(), content);
			} catch (IOException e) {
				Log.error("pêche : peche-erreur.txt non écrit", e);
			}
		});
	}

	/**
	 * Écrit une image de diagnostic : la dernière capture, bords de la jauge en jaune, zone verte en magenta,
	 * poisson en rouge (coordonnées {@code r} déjà locales à l'image). {@code onFail} est appelé sur le thread
	 * client si l'écriture échoue (ImageIO absent, disque…).
	 */
	static void writeDiagnostic(String name, int[] px, int w, int h, FishVision.Result r, Consumer<Throwable> onFail) {
		submit("image de diagnostic " + name, () -> {
			try {
				BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
				img.setRGB(0, 0, w, h, px, 0, w);
				if (r.found()) {
					for (int y = Math.max(0, r.top()); y <= Math.min(h - 1, r.bottom()); y++) {
						img.setRGB(ScreenGrab.clamp(r.x0() - 2, 0, w - 1), y, 0xFFFF00);
						img.setRGB(ScreenGrab.clamp(r.x1() + 1, 0, w - 1), y, 0xFFFF00);
					}
					if (r.hasZone()) {
						for (int y = Math.max(0, r.zoneTop()); y <= Math.min(h - 1, r.zoneBottom()); y++) {
							img.setRGB(ScreenGrab.clamp(r.x0() - 1, 0, w - 1), y, 0xFF00FF);
						}
					}
					if (r.hasFish()) {
						int fy = (int) Math.max(0, Math.round(r.fish()));
						for (int x = Math.max(0, r.x0() - 6); x < Math.min(w, r.x1() + 6); x++) {
							img.setRGB(x, Math.min(h - 1, fy), 0xFF0000);
						}
					}
				}
				AtomicFiles.write(diagDir().resolve(name), out -> {
					if (!ImageIO.write(img, "png", out)) throw new IOException("aucun encodeur PNG disponible");
				});
			} catch (Exception | LinkageError e) {
				Minecraft mc = Minecraft.getInstance();
				if (mc != null) mc.execute(() -> onFail.accept(e));
			}
		});
	}

	/** Fermeture du jeu : laisse 2 s aux écritures en attente. */
	static void shutdown() {
		ExecutorService e;
		synchronized (FishFiles.class) {
			e = io;
			io = null;
		}
		if (e == null) return;
		e.shutdown();
		try {
			e.awaitTermination(2, TimeUnit.SECONDS);
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
		}
	}
}
