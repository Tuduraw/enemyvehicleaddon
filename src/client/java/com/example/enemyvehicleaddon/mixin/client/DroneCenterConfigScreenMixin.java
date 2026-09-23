package com.example.enemyvehicleaddon.mixin.client;

import com.example.enemyvehicleaddon.block.EnemyControlBlock;
import com.example.enemyvehicleaddon.network.EnemyControlSettingsRequestPayload;
import com.example.tudursvehiclemod.client.screen.DroneCenterConfigScreen;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds an "エネミー設定" button to the bottom of the prerequisite mod's own Drone Center config screen, but only when that screen is showing an Enemy Control Block - this is now the only way to reach EnemyControlSettingsScreen.
 *
 * Originally this used sneak + right-click on the block itself, but that hijacked the exact interaction vanilla uses to let a player place a block against the Enemy Control Block while sneaking (this same class of conflict is exactly why the prerequisite mod's own DroneCenterBlock#tudursvehiclemod$handleUse() explicitly PASSes while the player is sneaking, opening its config screen only on an ordinary click - see that method's own doc). A button inside the screen that ordinary click already opens has no such conflict.
 *
 * remap = false at class level (DroneCenterConfigScreen and its own init() are the prerequisite mod's, not Minecraft's); TAIL is safe regardless of how many buttons the prerequisite mod's own init() adds above this addon's own button, since this always ends up last. */
@Mixin(value = DroneCenterConfigScreen.class, remap = false)
public abstract class DroneCenterConfigScreenMixin extends Screen {

	protected DroneCenterConfigScreenMixin(Text title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void enemyvehicleaddon$addEnemySettingsButton(CallbackInfo ci) {
		DroneCenterConfigScreenAccessor accessor = (DroneCenterConfigScreenAccessor) this;
		BlockPos pos = new BlockPos(accessor.enemyvehicleaddon$getBlockX(), accessor.enemyvehicleaddon$getBlockY(), accessor.enemyvehicleaddon$getBlockZ());
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null || !(client.world.getBlockState(pos).getBlock() instanceof EnemyControlBlock)) {
			return;
		}
		int centerX = this.width / 2;
		// Appended after the prerequisite mod's own last button (Close, at startY+172 - see that screen's own init()).
		int startY = this.height / 2 - 90;
		this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.enemyvehicleaddon.enemy_settings.open_button"),
				button -> ClientPlayNetworking.send(new EnemyControlSettingsRequestPayload(pos.getX(), pos.getY(), pos.getZ()))
		).dimensions(centerX - 100, startY + 196, 200, 20).build());
	}
}
