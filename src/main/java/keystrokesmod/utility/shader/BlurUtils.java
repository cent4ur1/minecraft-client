package keystrokesmod.utility.shader;

import keystrokesmod.utility.RenderUtils;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;

public class BlurUtils {
    private static Framebuffer stencilFrameBufferBlur = new Framebuffer(1, 1, false);
    private static Framebuffer stencilFrameBufferBloom = new Framebuffer(1, 1, false);
    private static boolean blurPrepared;
    private static boolean bloomPrepared;

    public static void prepareBlur() {
        blurPrepared = OpenGlHelper.isFramebufferEnabled();
        if (!blurPrepared) {
            GlStateManager.colorMask(false, false, false, false);
            return;
        }

        stencilFrameBufferBlur = RenderUtils.createFrameBuffer(stencilFrameBufferBlur);
        stencilFrameBufferBlur.framebufferClear();
        stencilFrameBufferBlur.bindFramebuffer(false);
    }

    public static void prepareBloom() {
        bloomPrepared = OpenGlHelper.isFramebufferEnabled();
        if (!bloomPrepared) {
            GlStateManager.colorMask(false, false, false, false);
            return;
        }

        stencilFrameBufferBloom = RenderUtils.createFrameBuffer(stencilFrameBufferBloom);
        stencilFrameBufferBloom.framebufferClear();
        stencilFrameBufferBloom.bindFramebuffer(false);
    }

    public static void blurEnd(int passes, float radius) {
        if (!blurPrepared) {
            GlStateManager.colorMask(true, true, true, true);
            return;
        }

        stencilFrameBufferBlur.unbindFramebuffer();
        KawaseBlur.renderBlur(stencilFrameBufferBlur.framebufferTexture, passes, radius);
    }

    public static void bloomEnd(int passes, float radius) {
        if (!bloomPrepared) {
            GlStateManager.colorMask(true, true, true, true);
            return;
        }

        stencilFrameBufferBloom.unbindFramebuffer();
        KawaseBloom.renderBlur(stencilFrameBufferBloom.framebufferTexture, passes, radius);
    }
}
