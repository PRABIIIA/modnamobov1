package com.example.mobmorph;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.SmallFireballEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.*;

/** Вся серверная логика: кто в кого превращён + способности мобов. */
public final class MorphManager {
    private static final Map<UUID, Identifier> TYPES = new HashMap<>();
    private static final Map<UUID, BlockState> CARRIED = new HashMap<>();   // блок в руках эндермена
    private static final Map<UUID, Integer> FUSE = new HashMap<>();         // таймер крипера
    private static final Map<UUID, Integer> LAST_USE = new HashMap<>();     // кулдаун способностей

    private MorphManager() {}

    // ---------- состояние и синхронизация ----------

    public static void set(ServerPlayerEntity p, Identifier type) {
        TYPES.put(p.getUuid(), type);
        CARRIED.remove(p.getUuid());
        FUSE.remove(p.getUuid());
        broadcast(p.getServer(), p.getUuid());
    }

    public static void clear(ServerPlayerEntity p) {
        forget(p.getServer(), p.getUuid());
    }

    public static void forget(MinecraftServer server, UUID id) {
        TYPES.remove(id);
        CARRIED.remove(id);
        FUSE.remove(id);
        LAST_USE.remove(id);
        broadcast(server, id);
    }

    public static void sendAll(ServerPlayerEntity to) {
        for (UUID id : TYPES.keySet()) ServerPlayNetworking.send(to, MobMorph.SYNC, packet(id));
    }

    private static void broadcast(MinecraftServer server, UUID id) {
        if (server == null) return;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList())
            ServerPlayNetworking.send(p, MobMorph.SYNC, packet(id));
    }

    private static PacketByteBuf packet(UUID id) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeUuid(id);
        Identifier type = TYPES.get(id);
        buf.writeBoolean(type != null);
        if (type != null) {
            buf.writeIdentifier(type);
            BlockState carried = CARRIED.get(id);
            buf.writeInt(carried == null ? 0 : Block.getRawIdFromState(carried));
        }
        return buf;
    }

    // ---------- пассивные эффекты (каждый тик) ----------

    public static void tick(MinecraftServer server) {
        for (Map.Entry<UUID, Identifier> e : new ArrayList<>(TYPES.entrySet())) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
            if (p == null || !p.isAlive() || p.isSpectator()) continue;
            EntityType<?> t = Registries.ENTITY_TYPE.get(e.getValue());

            // крипер: запал и взрыв
            Integer fuse = FUSE.get(p.getUuid());
            if (fuse != null) {
                if (fuse <= 1) {
                    FUSE.remove(p.getUuid());
                    p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 20, 4, false, false));
                    p.getWorld().createExplosion(null, p.getX(), p.getY(), p.getZ(), 3.0f,
                            World.ExplosionSourceType.MOB);
                } else {
                    FUSE.put(p.getUuid(), fuse - 1);
                }
            }

            // паук: лазание по стенам (Shift — висеть на месте)
            if ((t == EntityType.SPIDER || t == EntityType.CAVE_SPIDER) && p.horizontalCollision) {
                Vec3d v = p.getVelocity();
                p.setVelocity(v.x, p.isSneaking() ? 0.0 : 0.2, v.z);
                p.velocityModified = true;
                p.fallDistance = 0;
            }

            // эндермен: боится воды и дождя
            if (t == EntityType.ENDERMAN && p.isWet() && p.age % 10 == 0) {
                p.damage(p.getDamageSources().drown(), 1.0f);
            }

            // случайные звуки моба
            if (p.getRandom().nextInt(160) == 0) ambient(p, t);
        }
    }

    private static void ambient(ServerPlayerEntity p, EntityType<?> t) {
        Entity e = t.create(p.getWorld());
        if (e instanceof MobEntity m) {
            m.setPosition(p.getPos());
            m.playAmbientSound();
        }
        if (e != null) e.discard();
    }

    // ---------- активные способности (кнопки R / G) ----------

    public static void useAbility(ServerPlayerEntity p, boolean secondary) {
        Identifier id = TYPES.get(p.getUuid());
        if (id == null || !p.isAlive()) return;
        EntityType<?> t = Registries.ENTITY_TYPE.get(id);

        int now = p.getServer().getTicks();
        Integer last = LAST_USE.get(p.getUuid());
        if (last != null && now - last < 10) return;
        LAST_USE.put(p.getUuid(), now);

        if (secondary) {
            if (t == EntityType.ENDERMAN) grabOrPlaceBlock(p);
            else p.sendMessage(Text.literal("У этого моба нет такой способности"), true);
            return;
        }

        World w = p.getWorld();
        Vec3d look = p.getRotationVec(1.0f);

        if (t == EntityType.CREEPER) {
            if (!FUSE.containsKey(p.getUuid())) {
                FUSE.put(p.getUuid(), 30); // 1.5 сек, как у настоящего
                w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_CREEPER_PRIMED,
                        SoundCategory.HOSTILE, 1.0f, 0.5f);
            }
        } else if (t == EntityType.ENDERMAN) {
            teleport(p);
        } else if (t == EntityType.SKELETON || t == EntityType.STRAY || t == EntityType.WITHER_SKELETON) {
            ArrowEntity arrow = new ArrowEntity(w, p);
            arrow.setVelocity(p, p.getPitch(), p.getYaw(), 0.0f, 2.5f, 1.0f);
            arrow.pickupType = PersistentProjectileEntity.PickupPermission.DISALLOWED;
            w.spawnEntity(arrow);
            w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_SKELETON_SHOOT,
                    SoundCategory.HOSTILE, 1.0f, 1.0f);
        } else if (t == EntityType.BLAZE) {
            SmallFireballEntity f = new SmallFireballEntity(w, p, look.x, look.y, look.z);
            f.setPosition(p.getX() + look.x, p.getEyeY() - 0.1, p.getZ() + look.z);
            w.spawnEntity(f);
            w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_BLAZE_SHOOT,
                    SoundCategory.HOSTILE, 1.0f, 1.0f);
        } else {
            ambient(p, t); // у остальных — просто «голос» моба
        }
    }

    private static void teleport(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        HitResult hit = p.raycast(32.0, 1.0f, false);
        Vec3d pos;
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos bp = bhr.getBlockPos().offset(bhr.getSide());
            pos = new Vec3d(bp.getX() + 0.5, bp.getY(), bp.getZ() + 0.5);
            if (bhr.getSide() == Direction.DOWN) pos = pos.add(0, -1, 0);
        } else {
            pos = hit.getPos();
        }
        w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_ENDERMAN_TELEPORT,
                SoundCategory.HOSTILE, 1.0f, 1.0f);
        w.spawnParticles(ParticleTypes.PORTAL, p.getX(), p.getY() + 1, p.getZ(), 30, 0.3, 0.5, 0.3, 0.1);
        p.requestTeleport(pos.x, pos.y, pos.z);
        p.fallDistance = 0;
        w.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENTITY_ENDERMAN_TELEPORT,
                SoundCategory.HOSTILE, 1.0f, 1.0f);
        w.spawnParticles(ParticleTypes.PORTAL, pos.x, pos.y + 1, pos.z, 30, 0.3, 0.5, 0.3, 0.1);
    }

    private static void grabOrPlaceBlock(ServerPlayerEntity p) {
        World w = p.getWorld();
        HitResult hit = p.raycast(5.0, 1.0f, false);
        if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) return;
        BlockPos pos = bhr.getBlockPos();
        BlockState carried = CARRIED.get(p.getUuid());

        if (carried == null) { // взять блок
            BlockState state = w.getBlockState(pos);
            if (state.getHardness(w, pos) < 0 || w.getBlockEntity(pos) != null) {
                p.sendMessage(Text.literal("Этот блок взять нельзя"), true);
                return;
            }
            CARRIED.put(p.getUuid(), state);
            w.removeBlock(pos, false);
        } else {               // поставить блок
            BlockPos target = pos.offset(bhr.getSide());
            if (!w.getBlockState(target).isReplaceable()) return;
            w.setBlockState(target, carried);
            CARRIED.remove(p.getUuid());
        }
        w.playSound(null, pos, SoundEvents.ENTITY_ENDERMAN_AMBIENT, SoundCategory.HOSTILE, 1.0f, 1.0f);
        broadcast(p.getServer(), p.getUuid());
    }
}
