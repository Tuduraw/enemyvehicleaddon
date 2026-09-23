package com.example.enemyvehicleaddon.client.screen;

import com.example.enemyvehicleaddon.block.EnemyControlBlockEntity;
import com.example.enemyvehicleaddon.network.EnemyControlSettingsUpdatePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Enemy Control Block's own settings (the "Enemy Settings" button on the Drone Center config screen): leash radius, the player whitelist, and structure-template arming.
 *
 * Edits are local until Save, which sends the complete settings in one payload (server-side sanitised, see EnemyControlBlockEntity#enemyvehicleaddon$setEnemySettings()); Cancel/Escape discards them. The whitelist is paged (ROWS_PER_PAGE names per page, prev/next) rather than scrolled - the same paging approach the prerequisite mod's own waypoint screen uses. All mutable state lives in fields so a window resize (which re-runs init()) keeps unsaved edits. */
public class EnemyControlSettingsScreen extends Screen {

	private static final int ROWS_PER_PAGE = 4;
	private static final int ROW_HEIGHT = 22;
	private static final int PANEL_WIDTH = 300;

	private final int x;
	private final int y;
	private final int z;
	private final double originalRadius;
	private final List<String> whitelist;

	private String radiusText;
	/** Desired template state - sent with Save; the server may refuse arming (see EnemyControlBlockEntity#enemyvehicleaddon$armTemplate()) and reports the outcome in the action bar. */
	private boolean templateArmed;
	private String pendingName = "";
	private int page;
	/** Shown under the name field when the last Add attempt was rejected; cleared on the next successful add. */
	private Text addError;

	private int top;
	private int left;

	public EnemyControlSettingsScreen(int x, int y, int z, double leashRadius, List<String> whitelist, boolean templateArmed) {
		super(Text.translatable("gui.enemyvehicleaddon.enemy_settings.title"));
		this.x = x;
		this.y = y;
		this.z = z;
		this.originalRadius = leashRadius;
		this.radiusText = formatRadius(leashRadius);
		this.whitelist = new ArrayList<>(whitelist);
		this.templateArmed = templateArmed;
	}

	private static String formatRadius(double radius) {
		return radius == Math.rint(radius) ? Long.toString((long) radius) : Double.toString(radius);
	}

	private int pageCount() {
		return Math.max(1, (this.whitelist.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
	}

	@Override
	protected void init() {
		this.left = this.width / 2 - PANEL_WIDTH / 2;
		this.top = Math.max(8, this.height / 2 - 133);
		this.page = Math.min(this.page, this.pageCount() - 1);

		// Leash radius
		TextFieldWidget radiusField = new TextFieldWidget(this.textRenderer, this.left + 150, this.top + 18, 150, 20,
				Text.translatable("gui.enemyvehicleaddon.enemy_settings.radius"));
		radiusField.setMaxLength(9);
		radiusField.setText(this.radiusText);
		radiusField.setChangedListener(text -> this.radiusText = text);
		this.addDrawableChild(radiusField);

		// Whitelist entry
		TextFieldWidget nameField = new TextFieldWidget(this.textRenderer, this.left, this.top + 58, 196, 20,
				Text.translatable("gui.enemyvehicleaddon.enemy_settings.name"));
		nameField.setMaxLength(EnemyControlBlockEntity.MAX_NAME_LENGTH);
		nameField.setText(this.pendingName);
		nameField.setChangedListener(text -> this.pendingName = text);
		this.addDrawableChild(nameField);
		this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.enemyvehicleaddon.enemy_settings.add"),
				button -> this.enemyvehicleaddon$addName()
		).dimensions(this.left + 200, this.top + 58, 100, 20).build());

		// Current page of the whitelist - names are drawn in render(), only the remove buttons are widgets.
		int listTop = this.top + 94;
		int start = this.page * ROWS_PER_PAGE;
		for (int row = 0; row < ROWS_PER_PAGE; row++) {
			int index = start + row;
			if (index >= this.whitelist.size()) {
				break;
			}
			this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.enemyvehicleaddon.enemy_settings.remove"),
					button -> {
						this.whitelist.remove(index);
						this.clearAndInit();
					}
			).dimensions(this.left + 220, listTop + row * ROW_HEIGHT, 80, 20).build());
		}

		// Paging
		int pagerY = listTop + ROWS_PER_PAGE * ROW_HEIGHT + 2;
		ButtonWidget prev = ButtonWidget.builder(Text.literal("<"), button -> {
			this.page--;
			this.clearAndInit();
		}).dimensions(this.left, pagerY, 40, 20).build();
		prev.active = this.page > 0;
		this.addDrawableChild(prev);
		ButtonWidget next = ButtonWidget.builder(Text.literal(">"), button -> {
			this.page++;
			this.clearAndInit();
		}).dimensions(this.left + PANEL_WIDTH - 40, pagerY, 40, 20).build();
		next.active = this.page < this.pageCount() - 1;
		this.addDrawableChild(next);

		// Structure template (see EnemyControlBlockEntity's template section)
		this.addDrawableChild(ButtonWidget.builder(this.enemyvehicleaddon$templateText(), button -> {
			this.templateArmed = !this.templateArmed;
			button.setMessage(this.enemyvehicleaddon$templateText());
		}).dimensions(this.left, pagerY + 38, PANEL_WIDTH, 20).build());

		// Save / Cancel
		int buttonsY = pagerY + 62;
		this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.enemyvehicleaddon.enemy_settings.save"),
				button -> this.enemyvehicleaddon$save()
		).dimensions(this.left, buttonsY, PANEL_WIDTH / 2 - 2, 20).build());
		this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.enemyvehicleaddon.enemy_settings.cancel"),
				button -> this.close()
		).dimensions(this.left + PANEL_WIDTH / 2 + 2, buttonsY, PANEL_WIDTH / 2 - 2, 20).build());

		this.setInitialFocus(nameField);
	}

	/** Same acceptance rule the server applies (EnemyControlBlockEntity#enemyvehicleaddon$isAcceptableName()), plus duplicate and size checks, so the player sees a rejection immediately instead of the name silently vanishing on save. */
	private void enemyvehicleaddon$addName() {
		String name = this.pendingName.strip();
		if (!EnemyControlBlockEntity.enemyvehicleaddon$isAcceptableName(name)) {
			this.addError = Text.translatable("gui.enemyvehicleaddon.enemy_settings.error.invalid");
			return;
		}
		String lower = name.toLowerCase(Locale.ROOT);
		for (String existing : this.whitelist) {
			if (existing.toLowerCase(Locale.ROOT).equals(lower)) {
				this.addError = Text.translatable("gui.enemyvehicleaddon.enemy_settings.error.duplicate");
				return;
			}
		}
		if (this.whitelist.size() >= EnemyControlBlockEntity.MAX_WHITELIST_SIZE) {
			this.addError = Text.translatable("gui.enemyvehicleaddon.enemy_settings.error.full");
			return;
		}
		this.whitelist.add(name);
		this.pendingName = "";
		this.addError = null;
		this.page = this.pageCount() - 1;
		this.clearAndInit();
	}

	/** An unparsable radius falls back to the value the screen was opened with; range clamping is left to the server. */
	private void enemyvehicleaddon$save() {
		double radius;
		try {
			radius = Double.parseDouble(this.radiusText.strip());
		} catch (NumberFormatException e) {
			radius = this.originalRadius;
		}
		ClientPlayNetworking.send(new EnemyControlSettingsUpdatePayload(this.x, this.y, this.z, radius, List.copyOf(this.whitelist), this.templateArmed));
		this.close();
	}

	private Text enemyvehicleaddon$templateText() {
		return Text.translatable("gui.enemyvehicleaddon.enemy_settings.template",
				Text.translatable(this.templateArmed ? "options.on" : "options.off"));
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, this.top, 0xFFFFFFFF);

		context.drawTextWithShadow(this.textRenderer, Text.translatable("gui.enemyvehicleaddon.enemy_settings.radius"),
				this.left, this.top + 24, 0xFFFFFFFF);
		context.drawTextWithShadow(this.textRenderer, Text.translatable("gui.enemyvehicleaddon.enemy_settings.radius_range",
						formatRadius(EnemyControlBlockEntity.MIN_LEASH_RADIUS), formatRadius(EnemyControlBlockEntity.MAX_LEASH_RADIUS)),
				this.left, this.top + 44, 0xFFAAAAAA);

		if (this.addError != null) {
			context.drawTextWithShadow(this.textRenderer, this.addError, this.left, this.top + 82, 0xFFFF5555);
		} else {
			context.drawTextWithShadow(this.textRenderer, Text.translatable("gui.enemyvehicleaddon.enemy_settings.whitelist_count",
					this.whitelist.size(), EnemyControlBlockEntity.MAX_WHITELIST_SIZE), this.left, this.top + 82, 0xFFAAAAAA);
		}

		int listTop = this.top + 94;
		int start = this.page * ROWS_PER_PAGE;
		for (int row = 0; row < ROWS_PER_PAGE; row++) {
			int index = start + row;
			if (index >= this.whitelist.size()) {
				break;
			}
			context.drawTextWithShadow(this.textRenderer, Text.literal(this.whitelist.get(index)),
					this.left + 4, listTop + row * ROW_HEIGHT + 6, 0xFFFFFFFF);
		}

		int pagerY = listTop + ROWS_PER_PAGE * ROW_HEIGHT + 2;
		context.drawCenteredTextWithShadow(this.textRenderer, Text.literal((this.page + 1) + " / " + this.pageCount()),
				this.width / 2, pagerY + 6, 0xFFFFFFFF);

		// The one behaviour most likely to surprise: an empty whitelist targets everyone, the owner included.
		Text notice = this.whitelist.isEmpty()
				? Text.translatable("gui.enemyvehicleaddon.enemy_settings.empty_warning")
				: Text.translatable("gui.enemyvehicleaddon.enemy_settings.whitelist_note");
		context.drawCenteredTextWithShadow(this.textRenderer, notice, this.width / 2, pagerY + 25,
				this.whitelist.isEmpty() ? 0xFFFF5555 : 0xFFAAAAAA);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
