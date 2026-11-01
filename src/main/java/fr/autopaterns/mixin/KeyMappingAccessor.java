package fr.autopaterns.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.KeyMapping;

/** Accès au compteur de clics brefs (remplace la réflexion sur « field_1661 » de la 2.x). */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {
	@Accessor("clickCount")
	int autopaterns$getClickCount();

	@Accessor("clickCount")
	void autopaterns$setClickCount(int value);
}
