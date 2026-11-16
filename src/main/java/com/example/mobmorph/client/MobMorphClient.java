package com.example.mobmorph.client;

import com.example.mobmorph.MobMorph;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.UUID;

public class MobMorphClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        KeyBinding ability = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.mobmorph.ability", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "category.mobmorph"));
        KeyBinding grab = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.mobmorph.grab", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "category.mobmorph"));

        ClientPlayNetworking.registerGlobalReceiver(MobMorph.SYNC, (client, handler, buf, sender) -> {
            UUID id = buf.readUuid();
            boolean has = buf.readBoolean();
            Identifier type = has ? buf.readIdentifier() : null;
            int carried = has ? buf.readInt() : 0;
            client.execute(() -> ClientDisguises.set(id, type, carried));
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (ability.wasPressed()) send(false);
            while (grab.wasPressed()) send(true);
            ClientDisguises.tick(client);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientDisguises.clear());
    }

    private static void send(boolean secondary) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(secondary);
        ClientPlayNetworking.send(MobMorph.ABILITY, buf);
    }
}
