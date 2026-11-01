package fr.autopaterns.patterns;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import fr.autopaterns.input.InputSim;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Enregistrement (2.x §9) : une {@link TickFrame} par tick, un échantillon de caméra par image, détection des
 * blocs cassés (blocs visés avec l'attaque enfoncée dont l'état change) et items de référence de la barre d'action.
 */
final class Recorder {
	private static final int INITIAL_CAPACITY = 12_000;
	private static final int CANDIDATE_TIMEOUT_TICKS = 100;

	/** Bloc visé pendant l'enregistrement, en attente de détection de cassage. */
	private static final class Candidate {
		final int x, y, z;
		final String state;
		int lastSeen;

		Candidate(int x, int y, int z, String state, int lastSeen) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.state = state;
			this.lastSeen = lastSeen;
		}
	}

	private List<TickFrame> frames;
	private CameraTrack camera;
	private List<BreakEvent> breaks;
	private PatternOptions options;
	private final Map<Long, Candidate> candidates = new HashMap<>();

	boolean active() {
		return frames != null;
	}

	int ticks() {
		return frames == null ? 0 : frames.size();
	}

	int breakCount() {
		return breaks == null ? 0 : breaks.size();
	}

	/** Début d'enregistrement : nouvelles options (défauts 2.x) et items actuels de la barre d'action. */
	void begin(LocalPlayer p) {
		frames = new ArrayList<>(INITIAL_CAPACITY);
		camera = new CameraTrack();
		breaks = new ArrayList<>();
		candidates.clear();
		options = new PatternOptions();
		captureHotbar(p, options);
	}

	/** START tick en enregistrement. */
	void tick(Minecraft mc, LocalPlayer p) {
		if (frames == null) return;
		int k = frames.size();
		frames.add(capture(mc, p));
		trackBreaks(mc, k);
		captureHotbar(p, options);
	}

	/** Image rendue : orientation de la caméra au temps {@code (ticks-1) + tp}. */
	void frame(float tp, float yaw, float pitch) {
		if (frames == null || frames.isEmpty() || camera.size() >= PatternStore.MAX_FRAMES) return;
		camera.add((frames.size() - 1) + (double) tp, yaw, pitch);
	}

	/**
	 * Fin d'enregistrement : renvoie le pattern (null si vide) et oublie l'enregistrement. Les blocs encore en
	 * attente dont l'état a déjà changé sont pris en compte (2.x les perdait).
	 */
	PatternStore.Loaded finish(Minecraft mc) {
		List<TickFrame> rec = frames;
		CameraTrack cam = camera;
		if (rec != null && !rec.isEmpty() && mc != null && mc.level != null) {
			int last = rec.size() - 1;
			for (Candidate c : candidates.values()) {
				String now = PlayerOps.stateKey(mc.level.getBlockState(new BlockPos(c.x, c.y, c.z)));
				if (!now.equals(c.state) && breaks.size() < PatternStore.MAX_BREAKS) {
					breaks.add(new BreakEvent(last, c.x, c.y, c.z, c.state));
				}
			}
		}
		List<BreakEvent> brk = breaks;
		PatternOptions opts = options;
		frames = null;
		camera = null;
		breaks = null;
		options = null;
		candidates.clear();
		if (rec == null || rec.isEmpty()) return null;
		return new PatternStore.Loaded(rec, cam != null && cam.size() >= 2 ? cam : null,
			brk != null ? brk : List.of(), opts != null ? opts : new PatternOptions());
	}

	private static TickFrame capture(Minecraft mc, LocalPlayer p) {
		Options o = mc.options;
		int keys = (o.keyUp.isDown() ? TickFrame.K_FORWARD : 0)
			| (o.keyDown.isDown() ? TickFrame.K_BACK : 0)
			| (o.keyLeft.isDown() ? TickFrame.K_LEFT : 0)
			| (o.keyRight.isDown() ? TickFrame.K_RIGHT : 0)
			| (o.keyJump.isDown() ? TickFrame.K_JUMP : 0)
			| (o.keyShift.isDown() ? TickFrame.K_SNEAK : 0)
			| (o.keySprint.isDown() ? TickFrame.K_SPRINT : 0)
			| (o.keyAttack.isDown() ? TickFrame.K_ATTACK : 0)
			| (o.keyUse.isDown() ? TickFrame.K_USE : 0);
		Vec3 v = p.getDeltaMovement();
		int state = (p.onGround() ? TickFrame.S_ON_GROUND : 0) | (p.isSprinting() ? TickFrame.S_SPRINTING : 0);
		return new TickFrame(keys, InputSim.clickCount(o.keyAttack), InputSim.clickCount(o.keyUse),
			p.getInventory().getSelectedSlot(), p.getYRot(), p.getXRot(),
			p.getX(), p.getY(), p.getZ(), v.x, v.y, v.z, state);
	}

	private void trackBreaks(Minecraft mc, int tick) {
		ClientLevel w = mc.level;
		if (w == null) return;
		if (mc.options.keyAttack.isDown() && mc.hitResult instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK) {
			BlockPos pos = b.getBlockPos();
			long key = packPos(pos.getX(), pos.getY(), pos.getZ());
			Candidate c = candidates.get(key);
			if (c == null) {
				BlockState st = w.getBlockState(pos);
				if (!st.isAir()) candidates.put(key, new Candidate(pos.getX(), pos.getY(), pos.getZ(), PlayerOps.stateKey(st), tick));
			} else {
				c.lastSeen = tick;
			}
		}
		for (Iterator<Candidate> it = candidates.values().iterator(); it.hasNext(); ) {
			Candidate c = it.next();
			String now = PlayerOps.stateKey(w.getBlockState(new BlockPos(c.x, c.y, c.z)));
			if (!now.equals(c.state)) {
				if (breaks.size() < PatternStore.MAX_BREAKS) breaks.add(new BreakEvent(tick, c.x, c.y, c.z, c.state));
				it.remove();
			} else if (tick - c.lastSeen > CANDIDATE_TIMEOUT_TICKS) {
				it.remove();
			}
		}
	}

	/** Clé interne (x, z, y), comme en 2.x. */
	private static long packPos(int x, int y, int z) {
		return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
	}

	/** Mémorise le premier item vu dans chaque emplacement de la barre d'action. */
	static void captureHotbar(LocalPlayer p, PatternOptions opts) {
		Inventory inv = p.getInventory();
		for (int i = 0; i < 9; i++) {
			if (opts.slotItems[i] != null) continue;
			ItemStack st = inv.getItem(i);
			if (!st.isEmpty()) opts.slotItems[i] = PatternOptions.itemId(st);
		}
	}
}
