package fr.autopaterns.fishing;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;

/**
 * Voie GPU directe (mode de capture 0 de la 2.x) : copie d'une région de la texture couleur du rendu principal vers
 * un tampon lisible par le CPU, puis lecture du tampon mappé. Appels directs (plus de réflexion ni de noms
 * intermédiaires), regroupés ici pour être vérifiés à la compilation :
 * <ul>
 *   <li>CONFIRMÉ : {@code RenderSystem.getDevice()}, {@code GpuDevice#createBuffer(Supplier<String>, int, long)},
 *       {@code GpuDevice#createCommandEncoder()}, {@code GpuBuffer.USAGE_MAP_READ / USAGE_COPY_DST},
 *       {@code GpuBuffer#map(boolean read, boolean write)} → vue {@code data()} / {@code close()} (26.2).</li>
 *   <li><b>NON CONFIRMÉ</b> : {@code RenderTarget#getColorTexture()}, {@code GpuTexture#getWidth(int)} /
 *       {@code getHeight(int)}, {@code CommandEncoder#copyTextureToBuffer(GpuTexture, GpuBuffer, offset, Runnable,
 *       int mip, int x, int y, int w, int h)} (signature 1.21.11 ; l'offset est passé en littéral {@code int}
 *       pour compiler qu'il soit {@code int} ou {@code long}).</li>
 * </ul>
 * Si ces appels ne compilent pas en 26.2, remplacer le corps de {@link #start} par
 * {@code throw new UnsupportedOperationException("copie GPU indisponible");} : la pêche passe alors
 * automatiquement en capture d'écran (repli 2.x, journal peche-erreur.txt), sans autre changement.
 * <p>
 * Format supposé RGBA8 ; l'orientation des lignes (OpenGL : origine en bas ; Vulkan : en haut) est déterminée
 * par le calibrage de {@link FishingBot}, qui abandonne aussi cette voie si l'image diffère de l'écran.
 * Aucune fuite : le tampon est fermé après lecture, sur exception synchrone ou à l'annulation (délai dépassé).
 */
final class GpuCopy {
	private GpuCopy() {}

	static ScreenGrab.Pending start(Minecraft mc, int x, int y, int w, int h, int step, boolean bottomUp,
									ScreenGrab.Sink sink, Consumer<Throwable> onError) {
		var target = mc.gameRenderer.mainRenderTarget();
		if (target == null) throw new IllegalStateException("rendu principal indisponible");
		var tex = target.getColorTexture();                       // NON CONFIRMÉ (26.2)
		if (tex == null) throw new IllegalStateException("texture couleur du rendu principal indisponible");
		int texW = tex.getWidth(0);                                // NON CONFIRMÉ (niveau de mip 0)
		int texH = tex.getHeight(0);                               // NON CONFIRMÉ
		if (texW <= 0 || texH <= 0) throw new IllegalStateException("texture vide " + texW + "×" + texH);
		int cx = ScreenGrab.clamp(x, 0, texW - 1);
		int cy = ScreenGrab.clamp(y, 0, texH - 1);
		int cw = Math.max(1, Math.min(texW - cx, w));
		int ch = Math.max(1, Math.min(texH - cy, h));
		int srcY = bottomUp ? texH - (cy + ch) : cy;
		int st = Math.max(1, step);

		var device = RenderSystem.getDevice();
		GpuBuffer buf = device.createBuffer(() -> "AutoPaterns peche",
			GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) cw * ch * 4);
		ScreenGrab.Pending p = new ScreenGrab.Pending();
		p.attach(buf);
		try {
			var encoder = device.createCommandEncoder();
			encoder.copyTextureToBuffer(tex, buf, 0,                  // NON CONFIRMÉ (signature 26.2)
				() -> onCopied(p, buf, cw, ch, cx, cy, st, bottomUp, sink, onError),
				0, cx, srcY, cw, ch);
		} catch (Throwable t) {
			p.cancel(); // ferme le tampon (corrige la fuite 2.x sur exception synchrone)
			throw t;
		}
		return p;
	}

	/** Copie terminée : lecture du tampon mappé (lecture seule), conversion RGBA8 → ARGB, ligne 0 = haut. */
	private static void onCopied(ScreenGrab.Pending p, GpuBuffer buf, int cw, int ch, int cx, int cy, int step,
								 boolean bottomUp, ScreenGrab.Sink sink, Consumer<Throwable> onError) {
		int ow = Math.max(1, cw / step);
		int oh = Math.max(1, ch / step);
		int[] out = null;
		Throwable err = null;
		synchronized (p) {
			if (p.isCancelled()) {
				p.release();
				return;
			}
			try (var view = buf.map(true, false)) {             // 26.2 : GpuBuffer#map (remplace CommandEncoder#mapBuffer)
				ByteBuffer bb = view.data();
				long need = (long) cw * ch * 4;
				if (bb.limit() < need) throw new IllegalStateException("tampon GPU trop petit (" + bb.limit() + " < " + need + ")");
				int[] px = new int[ow * oh];
				for (int r = 0; r < oh; r++) {
					int srcRow = bottomUp ? (ch - 1 - r * step) : r * step;
					int base = srcRow * cw * 4;
					int o = r * ow;
					for (int c = 0; c < ow; c++) {
						int i = base + c * step * 4;
						px[o + c] = 0xFF000000 | (bb.get(i) & 255) << 16 | (bb.get(i + 1) & 255) << 8 | (bb.get(i + 2) & 255);
					}
				}
				out = px;
			} catch (VirtualMachineError e) {
				throw e;
			} catch (Throwable t) {
				err = t;
			} finally {
				p.release(); // ferme le tampon GPU
			}
		}
		ScreenGrab.deliver(p, out, ow, oh, cx, cy, step, err, sink, onError);
	}
}
