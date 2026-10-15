package de.davidsw.diawarsclient.mixin;

import de.davidsw.diawarsclient.ImageDownloader;
import net.minecraft.client.resources.MapTextureManager;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Serves downloaded full-resolution images wherever a vanilla map renders
 * (hand, inventory, item frames): when {@link ImageDownloader} holds a full
 * texture for the map id, it wins over the 128x128 vanilla texture. Geometry,
 * UVs and decorations are untouched, so this is a pure sharpness upgrade —
 * vanilla and Bedrock players simply keep the 128px maps from the server.
 */
@Mixin(MapTextureManager.class)
public abstract class MapTextureManagerMixin {
    @Inject(method = "prepareMapTexture", at = @At("HEAD"), cancellable = true)
    private void diawars$fullResolution(MapId mapId, MapItemSavedData data,
                                        CallbackInfoReturnable<Identifier> cir) {
        Identifier full = ImageDownloader.getTexture(mapId.id());
        if (full != null) {
            cir.setReturnValue(full);
        }
    }
}
