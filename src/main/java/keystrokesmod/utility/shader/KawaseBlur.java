package keystrokesmod.utility.shader;

import keystrokesmod.utility.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11.*;

public class KawaseBlur {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public static ShaderUtils kawaseDown = new ShaderUtils("kawaseDown");
    public static ShaderUtils kawaseUp = new ShaderUtils("kawaseUp");
    public static Framebuffer framebuffer;
    private static int currentIterations;
    private static int currentDisplayWidth;
    private static int currentDisplayHeight;

    private static final List<Framebuffer> framebufferList = new ArrayList<>();

    private static void initFrameBuffers(int iterations) {
        for (Framebuffer oldFramebuffer : framebufferList) {
            oldFramebuffer.deleteFramebuffer();
        }
        framebufferList.clear();

        for (int i = 1; i <= iterations; i++) {
            Framebuffer currentBuffer = new Framebuffer((int) (mc.displayWidth / Math.pow(3, i)), (int) (mc.displayHeight / Math.pow(3, i)), false);
            currentBuffer.setFramebufferFilter(GL_LINEAR);
            GlStateManager.bindTexture(currentBuffer.framebufferTexture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL14.GL_MIRRORED_REPEAT);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL14.GL_MIRRORED_REPEAT);
            GlStateManager.bindTexture(0);

            framebufferList.add(currentBuffer);
        }

        framebuffer = framebufferList.get(0);
        currentIterations = iterations;
        currentDisplayWidth = mc.displayWidth;
        currentDisplayHeight = mc.displayHeight;
    }

    public static void renderBlur(int stencilFrameBufferTexture, int iterations, float offset) {
        if (!OpenGlHelper.isFramebufferEnabled()) {
            return;
        }

        if (currentIterations != iterations || currentDisplayWidth != mc.displayWidth || currentDisplayHeight != mc.displayHeight) {
            initFrameBuffers(iterations);
        }

        renderFBO(framebufferList.get(0), mc.getFramebuffer().framebufferTexture, kawaseDown, offset);

        for (int i = 1; i < iterations; i++) {
            renderFBO(framebufferList.get(i), framebufferList.get(i - 1).framebufferTexture, kawaseDown, offset);
        }

        for (int i = iterations - 1; i > 0; i--) {
            renderFBO(framebufferList.get(i - 1), framebufferList.get(i).framebufferTexture, kawaseUp, offset);
        }

        Framebuffer destination = mc.getFramebuffer();
        destination.bindFramebuffer(true);

        RenderUtils.setAlphaLimit(0);
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        kawaseUp.init();
        kawaseUp.setUniformf("offset", offset, offset);
        kawaseUp.setUniformi("inTexture", 0);
        kawaseUp.setUniformi("check", 1);
        kawaseUp.setUniformi("textureToCheck", 16);
        kawaseUp.setUniformf("halfpixel", 1.0f / destination.framebufferWidth, 1.0f / destination.framebufferHeight);
        kawaseUp.setUniformf("iResolution", destination.framebufferWidth, destination.framebufferHeight);
        GL13.glActiveTexture(GL13.GL_TEXTURE16);
        RenderUtils.bindTexture(stencilFrameBufferTexture);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        RenderUtils.bindTexture(framebufferList.get(0).framebufferTexture);
        ShaderUtils.drawQuads();
        kawaseUp.unload();

        GlStateManager.bindTexture(0);
        GlStateManager.disableBlend();
    }

    private static void renderFBO(Framebuffer framebuffer, int framebufferTexture, ShaderUtils shader, float offset) {
        framebuffer.framebufferClear();
        framebuffer.bindFramebuffer(false);
        shader.init();
        RenderUtils.bindTexture(framebufferTexture);
        shader.setUniformf("offset", offset, offset);
        shader.setUniformi("inTexture", 0);
        shader.setUniformi("check", 0);
        shader.setUniformf("halfpixel", 1.0f / framebuffer.framebufferWidth, 1.0f / framebuffer.framebufferHeight);
        shader.setUniformf("iResolution", framebuffer.framebufferWidth, framebuffer.framebufferHeight);
        ShaderUtils.drawQuads();
        shader.unload();
    }
}
