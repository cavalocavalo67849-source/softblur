package com.example.softblur;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MathHelper;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;

@Mod(modid = "softblur", name = "Soft Blur", version = "1.1.0",
        clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]")
public class SoftBlurMod {

    public static float strength = 0.30f;
    public static boolean enabled = true;

    private static Configuration config;

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        config = new Configuration(event.getSuggestedConfigurationFile());
        strength = config.getFloat("strength", "general", 0.30f, 0.0f, 0.90f,
                "Intensidade do blur (0.0 a 0.9).");
        enabled = config.getBoolean("enabled", "general", true, "Liga/desliga o blur.");
        if (config.hasChanged()) config.save();
    }

    @EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(new BlurRenderer());
        ClientCommandHandler.instance.registerCommand(new BlurCommand());
    }

    public static void saveConfig() {
        if (config == null) return;
        config.get("general", "strength", 0.30f).set((double) strength);
        config.get("general", "enabled", true).set(enabled);
        config.save();
    }
}

class BlurRenderer {

    /** Quantas copias do frame anterior sao desenhadas ao longo do movimento. */
    private static final int PASSES = 4;
    /** Deslocamento maximo (fracao da tela) para evitar rastro gigante em viradas bruscas. */
    private static final float MAX_SHIFT = 0.25f;
    /** Movimento (fracao da tela por frame) a partir do qual o blur fica 100%. */
    private static final float FULL_MOTION = 0.003f;

    private int texId = -1;
    private int texW = 0;
    private int texH = 0;
    private boolean hasFrame = false;

    private boolean hasAngles = false;
    private float lastYaw = 0.0f;
    private float lastPitch = 0.0f;
    private EntityPlayerSP lastPlayer = null;

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (!SoftBlurMod.enabled || SoftBlurMod.strength <= 0.001f) {
            hasFrame = false;
            hasAngles = false;
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        int w = mc.displayWidth;
        int h = mc.displayHeight;
        if (w <= 0 || h <= 0) return;

        if (texId == -1) {
            texId = GL11.glGenTextures();
        }

        GlStateManager.bindTexture(texId);

        if (w != texW || h != texH) {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB, w, h, 0,
                    GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
            texW = w;
            texH = h;
            hasFrame = false;
        }

        // --- Movimento da camera desde o frame anterior ---
        float shiftX = 0.0f;
        float shiftY = 0.0f;
        boolean validMotion = false;

        EntityPlayerSP player = mc.thePlayer;
        if (player != null) {
            float pt = event.partialTicks;
            float yaw = player.prevRotationYaw + (player.rotationYaw - player.prevRotationYaw) * pt;
            float pitch = player.prevRotationPitch + (player.rotationPitch - player.prevRotationPitch) * pt;

            if (hasAngles && player == lastPlayer) {
                float dYaw = MathHelper.wrapAngleTo180_float(yaw - lastYaw);
                float dPitch = pitch - lastPitch;

                double tanHalf = Math.tan(Math.toRadians(mc.gameSettings.fovSetting) * 0.5);
                float aspect = (float) w / (float) h;

                // Girar para a direita move a cena para a esquerda; olhar para baixo move para cima.
                shiftX = (float) (-Math.toRadians(dYaw) / (2.0 * tanHalf * aspect));
                shiftY = (float) (Math.toRadians(dPitch) / (2.0 * tanHalf));

                shiftX = Math.max(-MAX_SHIFT, Math.min(MAX_SHIFT, shiftX));
                shiftY = Math.max(-MAX_SHIFT, Math.min(MAX_SHIFT, shiftY));
                validMotion = true;
            }

            lastYaw = yaw;
            lastPitch = pitch;
            lastPlayer = player;
            hasAngles = true;
        } else {
            hasAngles = false;
            lastPlayer = null;
        }

        if (hasFrame && validMotion) {
            float motion = (float) Math.sqrt(shiftX * shiftX + shiftY * shiftY);
            float factor = Math.min(1.0f, motion / FULL_MOTION);
            float effective = SoftBlurMod.strength * factor;
            if (effective > 0.001f) {
                drawPreviousFrame(effective, shiftX, shiftY);
            }
        }

        // Guarda o resultado para o proximo frame.
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
        hasFrame = true;
    }

    private void drawPreviousFrame(float totalStrength, float shiftX, float shiftY) {
        // Alpha por passada, para que o peso total do frame antigo seja totalStrength.
        float alpha = (float) (1.0 - Math.pow(1.0 - totalStrength, 1.0 / PASSES));

        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GL11.glOrtho(0.0, 1.0, 0.0, 1.0, -1.0, 1.0);

        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();

        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.disableLighting();
        GlStateManager.disableFog();
        GlStateManager.disableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.color(1.0f, 1.0f, 1.0f, alpha);

        // Da copia mais distante (sem deslocamento) ate a mais proxima do frame atual.
        for (int i = 0; i < PASSES; i++) {
            float t = (float) i / (float) PASSES;
            float ox = shiftX * t;
            float oy = shiftY * t;

            GL11.glBegin(GL11.GL_QUADS);
            GL11.glTexCoord2f(0.0f, 0.0f);
            GL11.glVertex2f(ox, oy);
            GL11.glTexCoord2f(1.0f, 0.0f);
            GL11.glVertex2f(1.0f + ox, oy);
            GL11.glTexCoord2f(1.0f, 1.
