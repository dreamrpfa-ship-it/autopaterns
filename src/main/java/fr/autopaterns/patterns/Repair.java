package fr.autopaterns.patterns;

import fr.autopaterns.core.AutoPaterns;
import fr.autopaterns.core.Notifier;
import fr.autopaterns.core.Phase;
import fr.autopaterns.input.InputSim;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/**
 * Réparation (phase REPAIR, 2.x §11) : un bloc cassé à l'enregistrement est toujours là à la lecture. Le joueur
 * est replacé au tick du cassage, vise le bloc et le mine (attaque maintenue), puis la lecture reprend à ce tick.
 */
final class Repair {
	static final int REPAIR_TIMEOUT_TICKS = 300;
	static final int MAX_REPAIRS = 30;
	static final float REPAIR_TURN = 30.0f;
	private static final int MAX_OBSTRUCTED = 20;
	private static final float AIM_TOLERANCE = 0.5f;

	private final Playback pb;
	BreakEvent event;
	/** Indice de {@link #event} dans la liste des blocs cassés. */
	private int breakIdx;
	int ticks;
	private int obstructed;
	/** Réparations de la répétition en cours. */
	int count;

	Repair(Playback pb) {
		this.pb = pb;
	}

	void clear() {
		event = null;
		ticks = 0;
		obstructed = 0;
	}

	BlockPos pos() {
		BreakEvent ev = event;
		return ev == null ? null : new BlockPos(ev.x(), ev.y(), ev.z());
	}

	void begin(Minecraft mc, LocalPlayer p, BreakEvent ev, int idx) {
		if (++count > MAX_REPAIRS) {
			pb.errorStop(mc, Msg.t("repair.tooMany", MAX_REPAIRS));
			return;
		}
		InputSim.releaseAll(mc);
		event = ev;
		breakIdx = idx;
		ticks = 0;
		obstructed = 0;
		if (!pb.safeRestore(mc, p, pb.frameAt(ev.tick()), true)) return;
		PlayerOps.stopHorizontal(p);
		pb.camera.reset(p);
		pb.diag.invalidateEnd();
		AutoPaterns.get().automation().setPhase(Phase.REPAIR);
		Notifier.hud(Msg.t("repair.begin", ev.x(), ev.y(), ev.z()));
	}

	void step(Minecraft mc, LocalPlayer p) {
		BreakEvent ev = event;
		if (ev == null) {
			AutoPaterns.get().automation().setPhase(Phase.PLAYING);
			return;
		}
		if (!PlayerOps.blockStillThere(mc, ev)) {
			InputSim.setKey(mc.options.keyAttack, false);
			if (!pb.safeRestore(mc, p, pb.frameAt(ev.tick()), true)) return;
			pb.camera.reset(p);
			AutoPaterns.get().automation().setPhase(Phase.PLAYING);
			pb.index = Math.max(0, Math.min(ev.tick(), pb.frames().size() - 1));
			pb.breakCursor = breakIdx + 1; // saute l'événement réparé
			pb.diag.repairs++;
			event = null;
			Notifier.hud(Msg.t("repair.done", pb.index));
			if (pb.checkpoint(mc, p)) pb.applyFrame(mc, p, pb.index);
			return;
		}
		if (++ticks > REPAIR_TIMEOUT_TICKS) {
			pb.errorStop(mc, Msg.t("repair.timeout", ev.x(), ev.y(), ev.z()));
			return;
		}
		double dx = ev.x() + 0.5 - p.getX();
		double dy = ev.y() + 0.5 - p.getEyeY();
		double dz = ev.z() + 0.5 - p.getZ();
		float tYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float tPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
		float yaw = p.getYRot();
		float pitch = p.getXRot();
		float dYaw = PlayerOps.wrapDegrees(tYaw - yaw);
		float dPitch = tPitch - pitch;
		pb.camera.applyRotation(p, yaw + clamp(dYaw, REPAIR_TURN), pitch + clamp(dPitch, REPAIR_TURN));
		PlayerOps.stopHorizontal(p);
		boolean aimed = Math.abs(dYaw) < AIM_TOLERANCE && Math.abs(dPitch) < AIM_TOLERANCE; // avant la rotation de ce tick
		if (!aimed) {
			InputSim.setKey(mc.options.keyAttack, false);
			return;
		}
		if (PlayerOps.aimsAt(mc, ev.x(), ev.y(), ev.z())) {
			obstructed = 0;
			InputSim.setKey(mc.options.keyAttack, true);
		} else {
			InputSim.setKey(mc.options.keyAttack, false);
			if (++obstructed > MAX_OBSTRUCTED) pb.errorStop(mc, Msg.t("repair.hidden", ev.x(), ev.y(), ev.z()));
		}
	}

	private static float clamp(float v, float max) {
		return Math.max(-max, Math.min(max, v));
	}
}
