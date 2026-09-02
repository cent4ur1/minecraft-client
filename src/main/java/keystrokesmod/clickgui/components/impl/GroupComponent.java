package keystrokesmod.clickgui.components.impl;

import keystrokesmod.clickgui.components.Component;
import keystrokesmod.module.impl.client.Gui;
import keystrokesmod.module.setting.impl.GroupSetting;
import keystrokesmod.utility.Timer;
import keystrokesmod.utility.font.RavenFontRenderer;
import org.lwjgl.opengl.GL11;

public class GroupComponent extends Component {
    public GroupSetting setting;
    private ModuleComponent component;
    public float o;
    private float x;
    private float y;
    public boolean opened;

    private Timer smoothTimer;
    private float animationProgress;
    private float animationStartProgress;
    private float animationTargetProgress;

    private static final float ANIMATION_DURATION = 250f;

    public GroupComponent(GroupSetting setting, ModuleComponent moduleComponent, float o) {
        this.setting = setting;
        this.component = moduleComponent;
        this.o = o;
        this.x = moduleComponent.categoryComponent.getX() + moduleComponent.categoryComponent.getWidth();
        this.y = moduleComponent.categoryComponent.getY() + moduleComponent.yPos;
        this.opened = setting.isOpened();
        this.animationProgress = opened ? 1f : 0f;
        this.animationStartProgress = this.animationProgress;
        this.animationTargetProgress = this.animationProgress;
    }

    /**
     * Current live animation progress: 0 closed, 1 open.
     * Used for height, indentation, and reveal during animation.
     */
    public float getAnimationProgress() {
        if (smoothTimer != null) {
            if (System.currentTimeMillis() - smoothTimer.last >= ANIMATION_DURATION + 30) {
                smoothTimer = null;
                animationProgress = animationTargetProgress;
                animationStartProgress = animationTargetProgress;
            } else {
                animationProgress = smoothTimer.getValueFloat(animationStartProgress, animationTargetProgress, 1);
                if (animationProgress == animationTargetProgress) {
                    smoothTimer = null;
                    animationStartProgress = animationTargetProgress;
                }
            }
        }
        return animationProgress;
    }

    public void render() {
        float progress = getAnimationProgress();
        RavenFontRenderer renderer = Gui.getClickGuiSettingFontRenderer();
        GL11.glPushMatrix();
        GL11.glScaled(0.5D, 0.5D, 0.5D);
        float strX = ((this.component.categoryComponent.getX() + 4) * 2) + 1;
        float strY = (this.component.categoryComponent.getY() + this.o + 4) * 2;
        drawString(renderer, "[", strX, strY);

        int firstBracketWidth = renderer.getStringWidth("[");
        int fontHeight = renderer.getFontHeight();
        float arrowWidth = Math.max(6F, fontHeight * 0.45F);
        float arrowX = strX + firstBracketWidth;
        float centerX = arrowX + arrowWidth / 2F;
        float centerY = strY + fontHeight / 2F - 1.5F;
        float chevronSize = Math.max(4.5F, fontHeight * 0.3F);

        GL11.glPushMatrix();
        GL11.glTranslatef(centerX, centerY, 0F);
        GL11.glRotatef(90F * progress, 0F, 0F, 1F);
        drawChevron(chevronSize);
        GL11.glPopMatrix();

        drawString(renderer, "]  " + this.setting.getName(), strX + firstBracketWidth + arrowWidth, strY);
        GL11.glPopMatrix();
    }

    private void drawChevron(float size) {
        float half = size / 2F;
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1F, 1F, 1F, 1F);
        GL11.glLineWidth(1.5F);
        GL11.glBegin(GL11.GL_LINE_STRIP);
        GL11.glVertex2f(-half, -half);
        GL11.glVertex2f(half, 0F);
        GL11.glVertex2f(-half, half);
        GL11.glEnd();
        GL11.glLineWidth(1F);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    public void updateHeight(float n) {
        this.o = n;
    }

    @Override
    public float getOffset() {
        return this.o;
    }

    public void drawScreen(int x, int y) {
        this.y = this.component.categoryComponent.getModuleY() + this.o;
        this.x = this.component.categoryComponent.getX();
    }

    public boolean onClick(int x, int y, int b) {
        if (this.overGroup(x, y) && (b == 0 || b == 1) && this.component.isOpened) {
            float currentProgress = getAnimationProgress();
            this.animationStartProgress = currentProgress;
            this.opened = !this.opened;
            this.setting.setOpened(this.opened);
            this.animationTargetProgress = this.opened ? 1f : 0f;
            (this.smoothTimer = new Timer(ANIMATION_DURATION)).start();
            this.component.updateSettingPositions();
            return true;
        }
        return false;
    }

    public void onGuiClosed() {
        smoothTimer = null;
        animationProgress = opened ? 1f : 0f;
        animationStartProgress = animationProgress;
        animationTargetProgress = animationProgress;
    }

    public boolean overGroup(int x, int y) {
        return x > this.x && x < this.x + this.component.categoryComponent.getWidth() && y > this.y && y < this.y + 11;
    }

    private void drawString(RavenFontRenderer renderer, String text, float x, float y) {
        renderer.drawString(text, x, y, -1, false);
    }
}
