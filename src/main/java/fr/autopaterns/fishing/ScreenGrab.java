package fr.autopaterns.fishing;

import java.util.function.Consumer;

import com.mojang.blaze3d.platform.NativeImage;

import fr.autopaterns.core.Log;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Lecture des pixels du rendu principal, sans réflexion.
 * <ul>
 *   <li>{@link #screenshot} : voie portable (OpenGL et Vulkan), {@code Screenshot.takeScreenshot} sur
 *       {@code gameRenderer.mainRenderTarget()} (API CONFIRMÉE en 26.2).</li>
 *   <li>{@link #gpu} : copie GPU directe d'une région, isolée dans {@link GpuCopy} (signatures en partie NON CONFIRMÉES).</li>
 * </ul>
 * Les deux voies sont asynchrones. Le callback du jeu copie seulement les pixels utiles, libère aussitôt l'image
 * ou le tampon, puis le résultat repasse sur le thread client ({@code Minecraft.execute}). Une demande annulée
 * ({@link Pending#cancel}) ne livre jamais rien et libère ses ressources GPU.
 */
final class ScreenGrab {
	private ScreenGrab() {}

	/** Pixels ARGB (ligne 0 = haut) ; origine en pixels de framebuffer ; {@code scale} = pixels de framebuffer par pixel d'image. */
	interface Sink {
		void accept(int[] argb, int width, int height, int originX, int originY, int scale);
	}

	/** Une capture en vol. Thread-safe : le callback de rendu et le thread client se synchronisent sur l'objet. */
	static final class Pending {
		private boolean cancelled;
		private AutoCloseable resource;

		synchronized boolean isCancelled() {
			return cancelled;
		}

		/** Attache une ressource à libérer (tampon GPU) ; libérée tout de suite si la demande est déjà annulée. */
		synchronized void attach(AutoCloseable r) {
			resource = r;
			if (cancelled) releaseLocked();
		}

		/** Annule : plus aucune livraison, ressource libérée. */
		synchronized void cancel() {
			cancelled = true;
			releaseLocked();
		}

		/** Libère la ressource (une seule fois). */
		synchronized void release() {
			releaseLocked();
		}

		private void releaseLocked() {
			AutoCloseable r = resource;
			resource = null;
			if (r == null) return;
			try {
				r.close();
			} catch (VirtualMachineError e) {
				throw e;
			} catch (Throwable t) {
				Log.debug("pêche : libération d'une ressource de capture : " + t);
			}
		}
	}

	/**
	 * Capture d'écran complète puis recadrage CPU sur la région {@code [x, x+w) × [y, y+h)} (pixels de framebuffer,
	 * bornés à l'image). {@code div} reproduit la capture « réduite » de la 2.x : l'image est lue comme si elle était
	 * réduite d'un facteur {@code div} (échantillonnage au plus proche), sans utiliser la surcharge
	 * {@code takeScreenshot(RenderTarget, int, Consumer)} NON CONFIRMÉE en 26.2.
	 * Les exceptions synchrones remontent à l'appelant (repli en cascade).
	 */
	static Pending screenshot(Minecraft mc, int div, int x, int y, int w, int h, int step, Sink sink, Consumer<Throwable> onError) {
		Pending p = new Pending();
		var target = mc.gameRenderer.mainRenderTarget();
		if (target == null) throw new IllegalStateException("rendu principal indisponible");
		Screenshot.takeScreenshot(target, (NativeImage img) -> onImage(p, img, div, x, y, w, h, step, sink, onError));
		return p;
	}

	/** Callback de {@code Screenshot.takeScreenshot} : l'image nous appartient, elle est toujours fermée ici. */
	private static void onImage(Pending p, NativeImage img, int div, int x, int y, int w, int h, int step,
								Sink sink, Consumer<Throwable> onError) {
		int[] out = null;
		int ow = 0, oh = 0, ox = 0, oy = 0, scale = 1;
		Throwable err = null;
		try {
			if (img == null) throw new IllegalStateException("capture d'écran vide");
			if (!p.isCancelled()) {
				int d = Math.max(1, div);
				int iw = Math.max(1, img.getWidth() / d);
				int ih = Math.max(1, img.getHeight() / d);
				int s = Math.max(1, step / d);
				int x0 = clamp(x / d, 0, iw - 1);
				int y0 = clamp(y / d, 0, ih - 1);
				int w0 = Math.max(1, Math.min(iw - x0, w / d));
				int h0 = Math.max(1, Math.min(ih - y0, h / d));
				ow = Math.max(1, w0 / s);
				oh = Math.max(1, h0 / s);
				int maxX = img.getWidth() - 1;
				int maxY = img.getHeight() - 1;
				out = new int[ow * oh];
				for (int r = 0; r < oh; r++) {
					int sy = Math.min(maxY, (y0 + r * s) * d);
					int o = r * ow;
					for (int c = 0; c < ow; c++) {
						out[o + c] = img.getPixel(Math.min(maxX, (x0 + c * s) * d), sy); // ARGB
					}
				}
				ox = x0 * d;
				oy = y0 * d;
				scale = s * d;
			}
		} catch (VirtualMachineError e) {
			throw e;
		} catch (Throwable t) {
			err = t;
			out = null;
		} finally {
			if (img != null) {
				try {
					img.close();
				} catch (VirtualMachineError e) {
					throw e;
				} catch (Throwable ignored) {
					// image déjà libérée
				}
			}
		}
		if (out == null && err == null) return; // annulée avant lecture
		deliver(p, out, ow, oh, ox, oy, scale, err, sink, onError);
	}

	/** Copie GPU directe (voie isolée, voir {@link GpuCopy}). Les exceptions synchrones remontent à l'appelant. */
	static Pending gpu(Minecraft mc, int x, int y, int w, int h, int step, boolean bottomUp, Sink sink, Consumer<Throwable> onError) {
		return GpuCopy.start(mc, x, y, w, h, step, bottomUp, sink, onError);
	}

	/**
	 * Livre un résultat (pixels ou erreur) sur le thread client, sauf si la demande a été annulée entre-temps.
	 * Une exception du destinataire est journalisée ici : elle ne compte jamais comme un échec de capture.
	 */
	static void deliver(Pending p, int[] px, int w, int h, int ox, int oy, int scale, Throwable err,
						Sink sink, Consumer<Throwable> onError) {
		Runnable task = () -> {
			if (p.isCancelled()) return;
			try {
				if (err != null) onError.accept(err);
				else sink.accept(px, w, h, ox, oy, scale);
			} catch (VirtualMachineError e) {
				throw e;
			} catch (Throwable t) {
				Log.error("pêche : traitement d'une capture impossible", t);
			}
		};
		Minecraft mc = Minecraft.getInstance();
		if (mc == null) return;
		mc.execute(task);
	}

	/** Plus grand {@code d} dans [2..max] qui divise {@code a} et {@code b} ; 1 si aucun. */
	static int divisor(int a, int b, int max) {
		for (int d = max; d >= 2; d--) {
			if (a % d == 0 && b % d == 0) return d;
		}
		return 1;
	}

	static int clamp(int v, int min, int max) {
		return v < min ? min : (v > max ? max : v);
	}
}
