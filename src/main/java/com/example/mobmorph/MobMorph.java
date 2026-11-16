package com.example.mobmorph;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.command.suggestion.SuggestionProviders;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.Collection;
import java.util.List;

public class MobMorph implements ModInitializer {
    public static final String MOD_ID = "mobmorph";
    /** сервер -> клиенты: кто в кого превращён */
    public static final Identifier SYNC = new Identifier(MOD_ID, "sync");
    /** клиент -> сервер: нажата способность */
    public static final Identifier ABILITY = new Identifier(MOD_ID, "ability");

    private static final SimpleCommandExceptionType BAD_MOB =
            new SimpleCommandExceptionType(Text.literal("Этот моб не подходит для превращения"));

    @Override
    public void onInitialize() {
        ServerPlayNetworking.registerGlobalReceiver(ABILITY, (server, player, handler, buf, sender) -> {
            boolean secondary = buf.readBoolean();
            server.execute(() -> MorphManager.useAbility(player, secondary));
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> MorphManager.sendAll(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                MorphManager.forget(server, handler.player.getUuid()));
        ServerTickEvents.END_SERVER_TICK.register(MorphManager::tick);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) ->
                dispatcher.register(CommandManager.literal("morph")
                        .requires(s -> s.hasPermissionLevel(2))
                        .then(CommandManager.literal("off")
                                .executes(c -> off(c.getSource(), List.of(c.getSource().getPlayerOrThrow())))
                                .then(CommandManager.argument("targets", EntityArgumentType.players())
                                        .executes(c -> off(c.getSource(), EntityArgumentType.getPlayers(c, "targets")))))
                        .then(CommandManager.argument("mob", IdentifierArgumentType.identifier())
                                .suggests(SuggestionProviders.SUMMONABLE_ENTITIES)
                                .executes(c -> morph(c.getSource(),
                                        IdentifierArgumentType.getIdentifier(c, "mob"),
                                        List.of(c.getSource().getPlayerOrThrow())))
                                .then(CommandManager.argument("targets", EntityArgumentType.players())
                                        .executes(c -> morph(c.getSource(),
                                                IdentifierArgumentType.getIdentifier(c, "mob"),
                                                EntityArgumentType.getPlayers(c, "targets")))))));
    }

    private static int morph(ServerCommandSource src, Identifier id, Collection<ServerPlayerEntity> targets)
            throws CommandSyntaxException {
        EntityType<?> type = Registries.ENTITY_TYPE.getOrEmpty(id).orElse(null);
        if (type == null || type == EntityType.PLAYER) throw BAD_MOB.create();
        Entity probe = type.create(src.getWorld());
        boolean ok = probe instanceof LivingEntity;
        if (probe != null) probe.discard();
        if (!ok) throw BAD_MOB.create();

        for (ServerPlayerEntity p : targets) MorphManager.set(p, id);
        src.sendFeedback(() -> Text.literal("Превращено игроков: " + targets.size() + " → " + id), true);
        return targets.size();
    }

    private static int off(ServerCommandSource src, Collection<ServerPlayerEntity> targets) {
        for (ServerPlayerEntity p : targets) MorphManager.clear(p);
        src.sendFeedback(() -> Text.literal("Превращение снято: " + targets.size()), true);
        return targets.size();
    }
}
