package keystrokesmod.mixin.impl.render;

import keystrokesmod.module.impl.client.Settings;
import keystrokesmod.utility.CapeManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.client.renderer.entity.layers.LayerCape;
import net.minecraft.entity.player.EnumPlayerModelParts;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@SideOnly(Side.CLIENT)
@Mixin(LayerCape.class)
public class MixinLayerCape {
    @Shadow
    private final RenderPlayer playerRenderer;

    public MixinLayerCape(RenderPlayer playerRendererIn) {
        this.playerRenderer = playerRendererIn;
    }

    @Redirect(method = "doRenderLayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/entity/AbstractClientPlayer;isWearing(Lnet/minecraft/entity/player/EnumPlayerModelParts;)Z"))
    private boolean modifyIsWearing(AbstractClientPlayer player, EnumPlayerModelParts part) {
        if (player.equals(Minecraft.getMinecraft().thePlayer) && getSelectedCape() != null) {
            return true;
        }
        return player.isWearing(part);
    }

    @Redirect(method = "doRenderLayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/entity/AbstractClientPlayer;getLocationCape()Lnet/minecraft/util/ResourceLocation;"))
    private ResourceLocation modifyGetLocationCape(AbstractClientPlayer player) {
        ResourceLocation selectedCape = getSelectedCape();
        if (player.equals(Minecraft.getMinecraft().thePlayer) && selectedCape != null) {
            return selectedCape;
        }
        return player.getLocationCape();
    }

    private ResourceLocation getSelectedCape() {
        if (Settings.customCapes == null) {
            return null;
        }
        return CapeManager.getCape((int) Settings.customCapes.getInput());
    }
}
