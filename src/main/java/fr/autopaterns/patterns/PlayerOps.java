package fr.autopaterns.patterns;

import java.util.Collection;

import fr.autopaterns.input.InputSim;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Opérations sur le joueur et le monde partagées par l'enregistrement et la lecture (thread client). */
final class PlayerOps {
	private PlayerOps() {}

	// ------------------------------------------------------------------ angles (formules 2.x)

	static float wrapDegrees(float a) {
		float r = a % 360f;
		if (r >= 180f) r -= 360f;
		if (r < -180f) r += 360f;
		return r;
	}

	/** Rotation de {@code cur} vers {@code target} par le plus court chemin, limitée à {@code max} degrés. */
	static float turnToward(float cur, float target, float max) {
		float d = wrapDegrees(target - cur);
		return Math.abs(d) <= max ? cur + d : cur + Math.copySign(max, d);
	}

	/** Pitch rapproché de {@code target}, limité à {@code max} degrés. */
	static float approachPitch(float pitch, float target, float max) {
		float d = target - pitch;
		return Math.abs(d) <= max ? target : pitch + Math.copySign(max, d);
	}

	// ------------------------------------------------------------------ touches

	/** Touches maintenues et clics brefs d'une frame enregistrée. */
	static void applyKeys(Minecraft mc, TickFrame f) {
		Options o = mc.options;
		InputSim.setKey(o.keyUp, f.has(TickFrame.K_FORWARD));
		InputSim.setKey(o.keyDown, f.has(TickFrame.K_BACK));
		InputSim.setKey(o.keyLeft, f.has(TickFrame.K_LEFT));
		InputSim.setKey(o.keyRight, f.has(TickFrame.K_RIGHT));
		InputSim.setKey(o.keyJump, f.has(TickFrame.K_JUMP));
		InputSim.setKey(o.keyShift, f.has(TickFrame.K_SNEAK));
		InputSim.setKey(o.keySprint, f.has(TickFrame.K_SPRINT));
		InputSim.setKey(o.keyAttack, f.has(TickFrame.K_ATTACK));
		InputSim.setKey(o.keyUse, f.has(TickFrame.K_USE));
		InputSim.addClicks(o.keyAttack, f.attacks());
		InputSim.addClicks(o.keyUse, f.uses());
	}

	// ------------------------------------------------------------------ position

	/**
	 * Le joueur tiendrait-il en {@code (x, y, z)} sans traverser de bloc ? Boîte du joueur (demi-largeur 0,299,
	 * hauteur 1,8 ou 1,5 accroupi, bornée par la hauteur actuelle du joueur) comparée aux formes de collision
	 * réelles. Corrige la 2.x qui testait 8 points et refusait tapis, neige, échelles ou barrières voisines.
	 */
	static boolean spaceFree(Minecraft mc, LocalPlayer p, double x, double y, double z, boolean sneaking) {
		ClientLevel w = mc.level;
		if (w == null) return true;
		double h = sneaking ? 1.5 : 1.8;
		if (p != null && p.getBbHeight() > 0.1) h = Math.min(h, p.getBbHeight());
		AABB box = new AABB(x - 0.299, y + 0.01, z - 0.299, x + 0.299, y + h - 0.01, z + 0.299);
		try {
			int x0 = (int) Math.floor(box.minX), x1 = (int) Math.floor(box.maxX);
			int y0 = (int) Math.floor(box.minY) - 1, y1 = (int) Math.floor(box.maxY);
			int z0 = (int) Math.floor(box.minZ), z1 = (int) Math.floor(box.maxZ);
			for (int bx = x0; bx <= x1; bx++) {
				for (int by = y0; by <= y1; by++) {
					for (int bz = z0; bz <= z1; bz++) {
						BlockPos pos = new BlockPos(bx, by, bz);
						VoxelShape shape = w.getBlockState(pos).getCollisionShape(w, pos);
						if (shape.isEmpty()) continue;
						for (AABB a : shape.toAabbs()) {
							if (a.move(bx, by, bz).intersects(box)) return false;
						}
					}
				}
			}
			return true;
		} catch (LinkageError e) {
			return spaceFreeLegacy(w, x, y, z);
		}
	}

	/** Test 2.x (8 points à 0,5 et 1,5 bloc de hauteur), utilisé seulement si le test exact est indisponible. */
	private static boolean spaceFreeLegacy(ClientLevel w, double x, double y, double z) {
		double[] dys = {0.5, 1.5};
		double[] ds = {-0.299, 0.299};
		for (double dy : dys) {
			for (double dx : ds) {
				for (double dz : ds) {
					BlockPos bp = BlockPos.containing(x + dx, y + dy, z + dz);
					if (!w.getBlockState(bp).getCollisionShape(w, bp).isEmpty()) return false;
				}
			}
		}
		return true;
	}

	/**
	 * Replace le joueur sur une frame : position, vitesse et sprint (format complet) ; avec {@code snap}, les
	 * positions précédentes sont aussi alignées pour que le rendu ne fasse pas glisser la correction.
	 */
	static void restoreState(LocalPlayer p, TickFrame f, boolean snap) {
		p.setPos(f.x(), f.y(), f.z());
		if (f.fullState()) {
			p.setDeltaMovement(f.vx(), f.vy(), f.vz());
			p.setSprinting(f.sprinting());
		} else {
			p.setDeltaMovement(0, p.getDeltaMovement().y, 0);
		}
		if (snap) {
			p.xo = f.x();
			p.xOld = f.x();
			p.yo = f.y();
			p.yOld = f.y();
			p.zo = f.z();
			p.zOld = f.z();
		}
	}

	/** Annule la vitesse horizontale. */
	static void stopHorizontal(LocalPlayer p) {
		p.setDeltaMovement(0, p.getDeltaMovement().y, 0);
	}

	// ------------------------------------------------------------------ blocs

	/**
	 * Identifiant texte d'un état de bloc au format 2.x ({@code Block{minecraft:oak_log}[axis=y]}), reconstruit
	 * explicitement pour rester comparable aux fichiers 2.x même si {@code BlockState#toString} change.
	 */
	static String stateKey(BlockState s) {
		try {
			StringBuilder sb = new StringBuilder(48).append("Block{")
				.append(BuiltInRegistries.BLOCK.getKey(s.getBlock())).append('}');
			Collection<Property<?>> props = s.getProperties();
			if (!props.isEmpty()) {
				sb.append('[');
				boolean first = true;
				for (Property<?> prop : props) {
					if (!first) sb.append(',');
					first = false;
					sb.append(prop.getName()).append('=').append(valueName(s, prop));
				}
				sb.append(']');
			}
			return sb.toString();
		} catch (RuntimeException | LinkageError e) {
			return s.toString();
		}
	}

	private static <T extends Comparable<T>> String valueName(BlockState s, Property<T> prop) {
		return prop.getName(s.getValue(prop));
	}

	/** Le bloc qui aurait dû être cassé est-il toujours là, dans le même état ? */
	static boolean blockStillThere(Minecraft mc, BreakEvent ev) {
		ClientLevel w = mc.level;
		return w != null && stateKey(w.getBlockState(new BlockPos(ev.x(), ev.y(), ev.z()))).equals(ev.state());
	}

	/** Le réticule vise-t-il ce bloc ? */
	static boolean aimsAt(Minecraft mc, int x, int y, int z) {
		return mc.hitResult instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK
			&& b.getBlockPos().getX() == x && b.getBlockPos().getY() == y && b.getBlockPos().getZ() == z;
	}
}
