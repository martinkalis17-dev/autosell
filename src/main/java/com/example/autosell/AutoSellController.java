package com.example.autosell;

import com.mojang.brigadier.Command;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.Set;

/**
 * AutoSell : toutes les 10 secondes, envoie /sell, déplace les objets de
 * l'inventaire dans le menu de vente (sauf épées et totems), puis clique
 * sur le bouton vert de confirmation.
 *
 * Commande en jeu :  /autosell   (active / désactive)
 *
 * Réglages (constantes ci-dessous) à adapter si le menu de votre serveur
 * est différent.
 */
public class AutoSellController implements ClientModInitializer {

    // ----- RÉGLAGES -----
    private static final String SELL_COMMAND = "sell";   // sans le /
    private static final int INTERVAL_TICKS = 200;       // 200 ticks = 10 secondes
    private static final int OPEN_TIMEOUT_TICKS = 60;    // attente max de l'ouverture du menu
    private static final int SCAN_DELAY_TICKS = 5;       // attente avant de chercher le bouton
    private static final int CLICK_DELAY_TICKS = 2;      // ticks entre deux déplacements d'objets

    // Objets à ne jamais vendre : tout objet dont l'id finit par _sword, et les totems.
    private static final Set<String> KEEP_EXACT = Set.of("totem_of_undying");
    private static final String KEEP_SUFFIX = "_sword";

    // Objets reconnus comme "bouton vert de confirmation" (id sans "minecraft:").
    private static final Set<String> CONFIRM_ITEMS = Set.of(
        "lime_stained_glass_pane", "green_stained_glass_pane",
        "lime_concrete", "green_concrete",
        "lime_terracotta", "green_terracotta",
        "lime_wool", "green_wool", "emerald_block");

    private enum State { IDLE, WAIT_GUI, SCAN_DELAY, MOVING, CONFIRM_DELAY, CLOSING }

    private boolean enabled = false;
    private State state = State.IDLE;
    private int timer = 0;
    private int wait = 0;
    private GenericContainerScreenHandler handler;
    private int confirmSlot = -1;
    private final Queue<Integer> queue = new ArrayDeque<>();

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("autosell").executes(ctx -> {
                enabled = !enabled;
                state = State.IDLE;
                timer = 0;
                ctx.getSource().sendFeedback(Text.literal(
                    "AutoSell " + (enabled ? "activé (toutes les 10 s)" : "désactivé")));
                return Command.SINGLE_SUCCESS;
            })));

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private static String pathOf(ItemStack stack) {
        return Registries.ITEM.getId(stack.getItem()).getPath();
    }

    private static boolean isProtected(ItemStack stack) {
        String p = pathOf(stack);
        return KEEP_EXACT.contains(p) || p.endsWith(KEEP_SUFFIX);
    }

    private int findConfirm(GenericContainerScreenHandler h, int topSlots) {
        for (int i = 0; i < topSlots; i++) {
            ItemStack s = h.slots.get(i).getStack();
            if (s.isEmpty()) continue;
            String name = s.getName().getString().toLowerCase();
            if (CONFIRM_ITEMS.contains(pathOf(s)) || name.contains("confirm")) return i;
        }
        return -1;
    }

    private void say(MinecraftClient c, String msg) {
        if (c.player != null) c.player.sendMessage(Text.literal("[AutoSell] " + msg), false);
    }

    private void tick(MinecraftClient c) {
        if (!enabled || c.player == null || c.interactionManager == null) return;

        switch (state) {
            case IDLE -> {
                if (++timer >= INTERVAL_TICKS) {
                    timer = 0;
                    if (c.currentScreen == null) {
                        c.player.networkHandler.sendChatCommand(SELL_COMMAND);
                        state = State.WAIT_GUI;
                        wait = 0;
                    }
                }
            }
            case WAIT_GUI -> {
                if (c.currentScreen instanceof GenericContainerScreen) {
                    state = State.SCAN_DELAY;
                    wait = 0;
                } else if (++wait > OPEN_TIMEOUT_TICKS) {
                    say(c, "Le menu /sell ne s'est pas ouvert.");
                    state = State.IDLE;
                }
            }
            case SCAN_DELAY -> {
                if (!(c.currentScreen instanceof GenericContainerScreen gcs)) { state = State.IDLE; return; }
                if (++wait < SCAN_DELAY_TICKS) return;

                handler = gcs.getScreenHandler();
                int top = handler.getRows() * 9;
                confirmSlot = findConfirm(handler, top);
                if (confirmSlot < 0) {
                    say(c, "Bouton vert introuvable, annulation.");
                    c.player.closeHandledScreen();
                    state = State.IDLE;
                    return;
                }

                queue.clear();
                for (int i = top; i < handler.slots.size(); i++) {
                    ItemStack s = handler.slots.get(i).getStack();
                    if (!s.isEmpty() && !isProtected(s)) queue.add(i);
                }
                if (queue.isEmpty()) {
                    c.player.closeHandledScreen();
                    state = State.IDLE;
                    return;
                }
                state = State.MOVING;
                wait = 0;
            }
            case MOVING -> {
                if (!(c.currentScreen instanceof GenericContainerScreen)) { state = State.IDLE; return; }
                if (++wait < CLICK_DELAY_TICKS) return;
                wait = 0;
                Integer next = queue.poll();
                if (next == null) { state = State.CONFIRM_DELAY; wait = 0; return; }
                c.interactionManager.clickSlot(handler.syncId, next, 0, SlotActionType.QUICK_MOVE, c.player);
            }
            case CONFIRM_DELAY -> {
                if (!(c.currentScreen instanceof GenericContainerScreen)) { state = State.IDLE; return; }
                if (++wait < 4) return;
                c.interactionManager.clickSlot(handler.syncId, confirmSlot, 0, SlotActionType.PICKUP, c.player);
                state = State.CLOSING;
                wait = 0;
            }
            case CLOSING -> {
                if (++wait < 6) return;
                if (c.currentScreen != null) c.player.closeHandledScreen();
                state = State.IDLE;
                timer = 0;
            }
        }
    }
}
