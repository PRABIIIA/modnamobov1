package com.example.mobmorph.mixin;

import com.example.mobmorph.client.ClientDisguises;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Если игрок превращён — вместо игрока рисуем настоящего моба. */
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEntityRendererMixin {

    @SuppressWarnings("unchecked")
    @Inject(method = "render(Lnet/minecraft/client/network/AbstractClientPlayerEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
            at = @At("HEAD"), cancellable = true)
    private void mobmorph$render(AbstractClientPlayerEntity player, float yaw, float tickDelta,
                                 MatrixStack matrices, VertexConsumerProvider vcp, int light, CallbackInfo ci) {
        LivingEntity fake = ClientDisguises.get(player);
        if (fake == null) return;

        ClientDisguises.sync(player, fake);
        EntityRenderer<LivingEntity> renderer = (EntityRenderer<LivingEntity>)
                MinecraftClient.getInstance().getEntityRenderDispatcher().getRenderer(fake);
        renderer.render(fake, yaw, tickDelta, matrices, vcp, light);
        ci.cancel();
    }
}
