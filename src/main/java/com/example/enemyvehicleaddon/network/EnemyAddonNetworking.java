package com.example.enemyvehicleaddon.network;

import com.example.enemyvehicleaddon.block.EnemyControlBlockEntity;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.text.Text;

public final class EnemyAddonNetworking {

	/** Same reach a vanilla container screen is allowed (8 blocks). */
	private static final double MAX_EDIT_DISTANCE_SQUARED = 64.0;

	private EnemyAddonNetworking() {
	}

	public static void register() {
		PayloadTypeRegistry.playS2C().register(EnemyControlSettingsOpenPayload.ID, EnemyControlSettingsOpenPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(EnemyControlSettingsUpdatePayload.ID, EnemyControlSettingsUpdatePayload.CODEC);
		PayloadTypeRegistry.playC2S().register(EnemyControlSettingsRequestPayload.ID, EnemyControlSettingsRequestPayload.CODEC);

		// Sent when the player presses the button DroneCenterConfigScreenMixin adds to the prerequisite mod's own config screen - see that payload's own doc.
		ServerPlayNetworking.registerGlobalReceiver(EnemyControlSettingsRequestPayload.ID, (payload, context) ->
				context.server().execute(() -> {
					ServerPlayerEntity player = context.player();
					BlockPos pos = new BlockPos(payload.x(), payload.y(), payload.z());
					if (player.squaredDistanceTo(Vec3d.ofCenter(pos)) > MAX_EDIT_DISTANCE_SQUARED) {
						return;
					}
					if (player.getEntityWorld().getBlockEntity(pos) instanceof EnemyControlBlockEntity blockEntity) {
						if (!blockEntity.enemyvehicleaddon$canManage(player)) {
							player.sendMessage(Text.translatable("message.enemyvehicleaddon.no_permission"), true);
							return;
						}
						ServerPlayNetworking.send(player, new EnemyControlSettingsOpenPayload(
								pos.getX(), pos.getY(), pos.getZ(),
								blockEntity.enemyvehicleaddon$getLeashRadius(),
								blockEntity.enemyvehicleaddon$getWhitelist(),
								blockEntity.enemyvehicleaddon$isTemplateArmed()));
					}
				}));

		ServerPlayNetworking.registerGlobalReceiver(EnemyControlSettingsUpdatePayload.ID, (payload, context) ->
				context.server().execute(() -> {
					ServerPlayerEntity player = context.player();
					BlockPos pos = new BlockPos(payload.x(), payload.y(), payload.z());
					// Re-checked here, not just when the screen was opened: the payload could come from a modified client, or from a player who has since walked away / lost creative mode.
					if (player.squaredDistanceTo(Vec3d.ofCenter(pos)) > MAX_EDIT_DISTANCE_SQUARED) {
						return;
					}
					if (player.getEntityWorld().getBlockEntity(pos) instanceof EnemyControlBlockEntity blockEntity
							&& blockEntity.enemyvehicleaddon$canManage(player)) {
						blockEntity.enemyvehicleaddon$setEnemySettings(payload.leashRadius(), payload.whitelist());
						// Template arming is saved together with the rest (see EnemyControlBlockEntity's template section); arming can be refused, so the outcome is reported either way.
						if (payload.templateArmed() != blockEntity.enemyvehicleaddon$isTemplateArmed()) {
							if (payload.templateArmed()) {
								String refusal = blockEntity.enemyvehicleaddon$armTemplate((net.minecraft.server.world.ServerWorld) player.getEntityWorld());
								player.sendMessage(Text.translatable(refusal != null ? refusal : "message.enemyvehicleaddon.template.armed"), true);
							} else {
								blockEntity.enemyvehicleaddon$disarmTemplate();
								player.sendMessage(Text.translatable("message.enemyvehicleaddon.template.disarmed"), true);
							}
						}
					}
				}));
	}
}
