package fr.autopaterns.input;

import org.lwjgl.glfw.GLFW;

/**
 * Tous les raccourcis du mod. Les noms de traduction sont ceux de la 2.x : les touches déjà
 * personnalisées dans options.txt sont donc reprises automatiquement.
 */
public enum KeyDef {
	MENU("key.autopaterns.menu", GLFW.GLFW_KEY_O, Cat.MACRO),
	STOP("key.autopaterns.stop", GLFW.GLFW_KEY_F6, Cat.MACRO),
	RECORD("key.autopaterns.record", GLFW.GLFW_KEY_F7, Cat.MACRO),
	LOOP("key.autopaterns.loop", GLFW.GLFW_KEY_F8, Cat.MACRO),
	PLAY("key.autopaterns.play", GLFW.GLFW_KEY_F9, Cat.MACRO),
	PERF("key.autopaterns.perf", GLFW.GLFW_KEY_F10, Cat.MACRO),
	HUD_EDIT("key.autopaterns.hud.edit", GLFW.GLFW_KEY_UNKNOWN, Cat.MACRO),
	WP_RECORD("key.autopaterns.waypoints.startstop", GLFW.GLFW_KEY_F12, Cat.WAYPOINTS),
	WP_MENU("key.autopaterns.waypoints.menu", GLFW.GLFW_KEY_J, Cat.WAYPOINTS),
	FINDER_SELECT("key.autopaterns.finder.select", GLFW.GLFW_KEY_N, Cat.WAYPOINTS),
	FISHING("key.autopaterns.fishing.startstop", GLFW.GLFW_KEY_K, Cat.WAYPOINTS);

	public enum Cat {
		MACRO("macro"), WAYPOINTS("waypoints");
		public final String id;
		Cat(String id) { this.id = id; }
	}

	public final String translationKey;
	public final int defaultKey;
	public final Cat category;

	KeyDef(String translationKey, int defaultKey, Cat category) {
		this.translationKey = translationKey;
		this.defaultKey = defaultKey;
		this.category = category;
	}
}
