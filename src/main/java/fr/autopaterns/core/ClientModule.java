package fr.autopaterns.core;

import java.util.Properties;

import net.minecraft.client.Minecraft;

/**
 * Cycle de vie commun des modules. Toutes les méthodes sont appelées sur le thread client,
 * à travers {@link Guard} (une exception désactive le mod proprement au lieu de faire planter le jeu).
 */
public interface ClientModule {
	/** Nom court pour les journaux. */
	String name();

	/** Pendant onInitializeClient : enregistrement des événements Fabric propres au module (rendu, HUD…). */
	default void onInit() {}

	/** Premier END tick (même sur l'écran titre) : chargement des fichiers. */
	default void onLoad(Minecraft mc, Properties config) {}

	/** Écrit ses clés dans config.txt (appelé par {@link fr.autopaterns.config.Config#save()}). */
	default void saveConfig(Properties config) {}

	/** Chaque fin de tick client (après les raccourcis), même sans joueur. */
	default void onEndTick(Minecraft mc) {}

	/** Arrêt d'urgence global ou désactivation du mod : relâcher tout, annuler tout travail en cours. */
	default void onHalt(Minecraft mc) {}

	/** Fermeture du jeu. */
	default void onStopping(Minecraft mc) {}
}
