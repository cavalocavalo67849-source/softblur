package com.example.softblur;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
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

@Mod(modid = "softblur", name = "Soft Blur", version = "1.0.0",
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

    private int texId = -1;
    private int texW = 0;
    private int texH = 0;
    private boolean hasFrame = false;

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (!SoftBlurMod.enabled || SoftBlurMod.strength <= 0.001f) {
            hasFrame = false;
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

        if (hasFrame) {
            drawPreviousFrame(SoftBlurMod.strength);
        }

        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
        hasFrame = true;
    }

    private void drawPreviousFrame(float alpha) {
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

        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0.0f, 0.0f);
        GL11.glVertex2f(0.0f, 0.0f);
        GL11.glTexCoord2f(1.0f, 0.0f);
        GL11.glVertex2f(1.0f, 0.0f);
        GL11.glTexCoord2f(1.0f, 1.0f);
        GL11.glVertex2f(1.0f, 1.0f);
        GL11.glTexCoord2f(0.0f, 1.0f);
        GL11.glVertex2f(0.0f, 1.0f);
        GL11.glEnd();

        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();

        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.popMatrix();
    }
}

class BlurCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "softblur";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/softblur <on|off|0-90>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        if (args.length != 1) {
            reply(sender, "Uso: " + getCommandUsage(sender));
            return;
        }

        String arg = args[0].toLowerCase();
        if (arg.equals("on")) {
            SoftBlurMod.enabled = true;
            reply(sender, "Soft Blur ligado (" + Math.round(SoftBlurMod.strength * 100) + ").");
        } else if (arg.equals("off")) {
            SoftBlurMod.enabled = false;
            reply(sender, "Soft Blur desligado.");
        } else {
            try {
                int value = Integer.parseInt(arg);
                value = Math.max(0, Math.min(90, value));
                SoftBlurMod.strength = value / 100.0f;
                SoftBlurMod.enabled = value > 0;
                reply(sender, "Intensidade do Soft Blur: " + value);
            } catch (NumberFormatException e) {
                reply(sender, "Uso: " + getCommandUsage(sender));
                return;
            }
        }
        SoftBlurMod.saveConfig();
    }

    private void reply(ICommandSender sender, String msg) {
        sender.addChatMessage(new ChatComponentText("[SoftBlur] " + msg));
    }
                  }
