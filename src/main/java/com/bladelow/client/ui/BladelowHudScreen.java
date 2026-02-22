package com.bladelow.client.ui;

import net.minecraft.block.Block;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class BladelowHudScreen extends Screen {
    private static final List<String> DEFAULT_BLOCK_IDS = List.of(
        "minecraft:stone", "minecraft:cobblestone", "minecraft:oak_planks", "minecraft:spruce_planks",
        "minecraft:birch_planks", "minecraft:glass", "minecraft:bricks", "minecraft:deepslate_tiles",
        "minecraft:iron_block", "minecraft:gold_block", "minecraft:quartz_block", "minecraft:obsidian",
        "minecraft:polished_andesite", "minecraft:polished_diorite", "minecraft:polished_granite", "minecraft:sandstone",
        "minecraft:cut_sandstone", "minecraft:red_sandstone", "minecraft:stone_bricks", "minecraft:mossy_stone_bricks",
        "minecraft:blackstone", "minecraft:polished_blackstone", "minecraft:basalt", "minecraft:smooth_stone",
        "minecraft:calcite", "minecraft:tuff", "minecraft:amethyst_block", "minecraft:copper_block",
        "minecraft:cut_copper", "minecraft:oxidized_copper", "minecraft:packed_mud", "minecraft:mud_bricks",
        "minecraft:terracotta", "minecraft:white_concrete", "minecraft:gray_concrete", "minecraft:black_concrete"
    );

    private static final int GRID_COLS = 4;
    private static final int GRID_ROWS = 3;
    private static final int GRID_CAPACITY = GRID_COLS * GRID_ROWS;
    private static final int TILE_W = 56;
    private static final int TILE_H = 22;
    private static final int PANEL_W = 352;
    private static final int PANEL_H = 266;
    private static final int LEFT_X = 8;
    private static final int LEFT_W = 236;
    private static final int RIGHT_X = 248;
    private static final int RIGHT_W = 96;
    private static final int SLOT_Y = 126;
    private static final int FIELD_Y = 148;
    private static final int ACTION_Y = 198;
    private static final String[] SHAPE_MODES = {"line", "selection"};
    private static final String[] BLUEPRINT_PRESETS = {"line20", "wall5x5"};

    private final List<ButtonWidget> blockButtons = new ArrayList<>();
    private final List<String> filteredBlockIds = new ArrayList<>();
    private final List<String> allBlockIds = new ArrayList<>();
    private final String[] selectedSlots = new String[3];

    private TextFieldWidget searchField;
    private TextFieldWidget xField;
    private TextFieldWidget yField;
    private TextFieldWidget zField;
    private TextFieldWidget countField;
    private TextFieldWidget topYField;
    private TextFieldWidget blueprintField;
    private TextFieldWidget webField;

    private ButtonWidget axisButton;
    private ButtonWidget smartMoveButton;
    private ButtonWidget moveModeButton;
    private ButtonWidget previewModeButton;
    private ButtonWidget profileButton;
    private ButtonWidget slot1Button;
    private ButtonWidget slot2Button;
    private ButtonWidget slot3Button;
    private ButtonWidget shapeModeButton;
    private ButtonWidget reachButton;

    private int pageIndex = 0;
    private int activeSlot = 0;

    private String statusText = "Ready";
    private String axis = "x";
    private boolean smartMoveEnabled = true;
    private boolean previewBeforeBuild = false;
    private String moveMode = "walk";
    private double reachDistance = 4.5;
    private String shapeMode = "line";
    private String hoveredBlockId;
    private int profileIndex = 0;
    private static final String[] PROFILE_PRESETS = {"builder", "safe", "fast"};

    public BladelowHudScreen() {
        super(Text.literal("Bladelow Builder"));
        selectedSlots[0] = "minecraft:stone";
        ensureBlockCatalog();
        rebuildFilter();
    }

    @Override
    protected void init() {
        int panelX = this.width / 2 - PANEL_W / 2;
        int panelY = this.height / 2 - PANEL_H / 2;

        this.searchField = new TextFieldWidget(this.textRenderer, panelX + LEFT_X, panelY + 28, 168, 18, Text.literal("Search"));
        this.searchField.setPlaceholder(Text.literal("Search blocks (e.g. stone)"));
        this.searchField.setChangedListener(value -> {
            pageIndex = 0;
            rebuildFilter();
            updateBlockButtons();
        });
        addDrawableChild(this.searchField);

        addDrawableChild(ButtonWidget.builder(Text.literal("<"), btn -> changePage(-1))
            .dimensions(panelX + LEFT_X + 172, panelY + 28, 24, 18)
            .build());

        addDrawableChild(ButtonWidget.builder(Text.literal(">"), btn -> changePage(1))
            .dimensions(panelX + LEFT_X + 200, panelY + 28, 24, 18)
            .build());

        int gridStartX = panelX + LEFT_X;
        int gridStartY = panelY + 50;
        for (int i = 0; i < GRID_CAPACITY; i++) {
            int col = i % GRID_COLS;
            int row = i / GRID_COLS;
            int x = gridStartX + col * (TILE_W + 4);
            int y = gridStartY + row * (TILE_H + 3);
            final int idx = i;

            ButtonWidget btn = addDrawableChild(ButtonWidget.builder(Text.literal(""), b -> assignVisibleToActiveSlot(idx))
                .dimensions(x, y, TILE_W, TILE_H)
                .build());
            blockButtons.add(btn);
        }

        slot1Button = addDrawableChild(ButtonWidget.builder(Text.literal("Slot 1"), b -> setActiveSlot(0))
            .dimensions(panelX + LEFT_X, panelY + SLOT_Y, 62, 18)
            .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("x"), b -> clearSlot(0))
            .dimensions(panelX + LEFT_X + 64, panelY + SLOT_Y, 12, 18)
            .build());
        slot2Button = addDrawableChild(ButtonWidget.builder(Text.literal("Slot 2"), b -> setActiveSlot(1))
            .dimensions(panelX + LEFT_X + 80, panelY + SLOT_Y, 62, 18)
            .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("x"), b -> clearSlot(1))
            .dimensions(panelX + LEFT_X + 144, panelY + SLOT_Y, 12, 18)
            .build());
        slot3Button = addDrawableChild(ButtonWidget.builder(Text.literal("Slot 3"), b -> setActiveSlot(2))
            .dimensions(panelX + LEFT_X + 160, panelY + SLOT_Y, 62, 18)
            .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("x"), b -> clearSlot(2))
            .dimensions(panelX + LEFT_X + 224, panelY + SLOT_Y, 12, 18)
            .build());

        int fieldW = 66;
        this.xField = new TextFieldWidget(this.textRenderer, panelX + LEFT_X, panelY + FIELD_Y, fieldW, 18, Text.literal("X"));
        this.yField = new TextFieldWidget(this.textRenderer, panelX + LEFT_X + 70, panelY + FIELD_Y, fieldW, 18, Text.literal("Y"));
        this.zField = new TextFieldWidget(this.textRenderer, panelX + LEFT_X + 140, panelY + FIELD_Y, fieldW, 18, Text.literal("Z"));
        this.countField = new TextFieldWidget(this.textRenderer, panelX + LEFT_X, panelY + 172, 102, 18, Text.literal("Count"));
        this.topYField = new TextFieldWidget(this.textRenderer, panelX + LEFT_X + 210, panelY + FIELD_Y, 118, 18, Text.literal("TopY"));

        if (this.client != null && this.client.player != null) {
            this.xField.setText(Integer.toString(this.client.player.getBlockX()));
            this.yField.setText(Integer.toString(this.client.player.getBlockY()));
            this.zField.setText(Integer.toString(this.client.player.getBlockZ()));
            this.topYField.setText(Integer.toString(this.client.player.getBlockY() + 6));
        }
        this.countField.setText("20");

        addDrawableChild(this.xField);
        addDrawableChild(this.yField);
        addDrawableChild(this.zField);
        addDrawableChild(this.countField);
        addDrawableChild(this.topYField);

        this.axisButton = addDrawableChild(ButtonWidget.builder(Text.literal("Axis: X"), btn -> cycleAxis())
            .dimensions(panelX + LEFT_X + 106, panelY + 172, 100, 18)
            .build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Run"), btn -> buildShapeAction())
            .dimensions(panelX + LEFT_X, panelY + ACTION_Y, 56, 20)
            .build());

        this.previewModeButton = addDrawableChild(ButtonWidget.builder(Text.literal("Prev:OFF"), btn -> togglePreviewMode())
            .dimensions(panelX + LEFT_X + 60, panelY + ACTION_Y, 56, 20)
            .build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Confirm"), btn -> sendCommand("bladeconfirm"))
            .dimensions(panelX + LEFT_X + 120, panelY + ACTION_Y, 56, 20)
            .build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), btn -> sendCommand("bladecancel"))
            .dimensions(panelX + LEFT_X + 180, panelY + ACTION_Y, 56, 20)
            .build());

        this.shapeModeButton = addDrawableChild(ButtonWidget.builder(Text.literal(shapeLabel(this.shapeMode)), btn -> toggleShapeMode())
            .dimensions(panelX + RIGHT_X, panelY + 170, RIGHT_W, 18)
            .build());

        addDrawableChild(ButtonWidget.builder(Text.literal("-"), btn -> adjustReach(-0.25))
            .dimensions(panelX + RIGHT_X, panelY + 190, 20, 18)
            .build());

        this.reachButton = addDrawableChild(ButtonWidget.builder(Text.literal(reachLabel()), btn -> {
        })
            .dimensions(panelX + RIGHT_X + 22, panelY + 190, 52, 18)
            .build());

        addDrawableChild(ButtonWidget.builder(Text.literal("+"), btn -> adjustReach(0.25))
            .dimensions(panelX + RIGHT_X + 76, panelY + 190, 20, 18)
            .build());

        this.moveModeButton = addDrawableChild(ButtonWidget.builder(Text.literal("Mode: WALK"), btn -> toggleMoveMode())
            .dimensions(panelX + RIGHT_X, panelY + 200, RIGHT_W, 18)
            .build());

        this.smartMoveButton = addDrawableChild(ButtonWidget.builder(Text.literal("Smart: ON"), btn -> toggleSmartMove())
            .dimensions(panelX + RIGHT_X, panelY + 220, RIGHT_W, 18)
            .build());

        this.blueprintField = new TextFieldWidget(this.textRenderer, panelX + RIGHT_X + 14, panelY + 48, RIGHT_W - 28, 18, Text.literal("Blueprint"));
        this.blueprintField.setText("line20");
        addDrawableChild(this.blueprintField);
        addDrawableChild(ButtonWidget.builder(Text.literal("<"), btn -> cycleBlueprint(-1))
            .dimensions(panelX + RIGHT_X, panelY + 48, 12, 18)
            .build());
        addDrawableChild(ButtonWidget.builder(Text.literal(">"), btn -> cycleBlueprint(1))
            .dimensions(panelX + RIGHT_X + RIGHT_W - 12, panelY + 48, 12, 18)
            .build());

        addDrawableChild(ButtonWidget.builder(Text.literal("BP Load"), btn -> loadBlueprint())
            .dimensions(panelX + RIGHT_X, panelY + 68, RIGHT_W, 18)
            .build());

        addDrawableChild(ButtonWidget.builder(Text.literal("BP Build"), btn -> buildBlueprint())
            .dimensions(panelX + RIGHT_X, panelY + 88, RIGHT_W, 18)
            .build());

        this.webField = new TextFieldWidget(this.textRenderer, panelX + RIGHT_X, panelY + 108, RIGHT_W, 18, Text.literal("Web"));
        this.webField.setPlaceholder(Text.literal("idx/url"));
        addDrawableChild(this.webField);

        addDrawableChild(ButtonWidget.builder(Text.literal("WebCat"), btn -> webCatalog())
            .dimensions(panelX + RIGHT_X, panelY + 128, 47, 18)
            .build());

        addDrawableChild(ButtonWidget.builder(Text.literal("WebImp"), btn -> webImport())
            .dimensions(panelX + RIGHT_X + 49, panelY + 128, 47, 18)
            .build());

        this.profileButton = addDrawableChild(ButtonWidget.builder(Text.literal("Prof"), btn -> cycleProfile())
            .dimensions(panelX + RIGHT_X, panelY + 148, 30, 18)
            .build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Mark"), btn -> markSelection())
            .dimensions(panelX + RIGHT_X + 33, panelY + 148, 30, 18)
            .build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), btn -> {
                if (this.client != null) {
                    this.client.setScreen(null);
                }
            })
            .dimensions(panelX + RIGHT_X + 66, panelY + 148, 30, 18)
            .build());

        updateSlotButtons();
        updateBlockButtons();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int panelX = this.width / 2 - PANEL_W / 2;
        int panelY = this.height / 2 - PANEL_H / 2;

        context.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, 0xD0111111);
        context.fill(panelX + 1, panelY + 1, panelX + PANEL_W - 1, panelY + 24, 0xAA1F2430);
        context.fill(panelX + LEFT_X - 2, panelY + 46, panelX + LEFT_X + LEFT_W + 2, panelY + 122, 0x7722262F);
        context.fill(panelX + LEFT_X - 2, panelY + 124, panelX + LEFT_X + LEFT_W + 2, panelY + 146, 0x66303646);
        context.fill(panelX + RIGHT_X - 2, panelY + 28, panelX + RIGHT_X + RIGHT_W + 2, panelY + 240, 0x66202638);
        drawBorder(context, panelX, panelY, PANEL_W, PANEL_H, 0xFFFFFFFF);

        context.drawText(this.textRenderer, Text.literal("Bladelow Builder (P)") , panelX + 10, panelY + 10, 0xFFFFFF, false);
        context.drawText(this.textRenderer, Text.literal("Visual Picker"), panelX + 10, panelY + 20, 0xCFCFCF, false);

        int totalPages = Math.max(1, (int) Math.ceil((double) filteredBlockIds.size() / GRID_CAPACITY));
        context.drawText(this.textRenderer, Text.literal("Page " + (pageIndex + 1) + "/" + totalPages), panelX + LEFT_X + 170, panelY + 32, 0xCFCFCF, false);

        context.drawText(this.textRenderer, Text.literal("Selected Slots"), panelX + LEFT_X, panelY + 116, 0xB5C9E8, false);
        context.drawText(this.textRenderer, Text.literal("XYZ"), panelX + LEFT_X, panelY + 138, 0xCFCFCF, false);
        context.drawText(this.textRenderer, Text.literal("Count"), panelX + LEFT_X, panelY + 162, 0xCFCFCF, false);
        context.drawText(this.textRenderer, Text.literal("Top Y"), panelX + LEFT_X + 212, panelY + 138, 0xCFCFCF, false);
        context.drawText(this.textRenderer, Text.literal("Automation"), panelX + RIGHT_X, panelY + 32, 0xCFCFCF, false);
        context.drawText(this.textRenderer, Text.literal("Blueprint/Web"), panelX + RIGHT_X, panelY + 40, 0x9DB3D2, false);
        context.fill(panelX + 6, panelY + 238, panelX + PANEL_W - 6, panelY + 258, 0x55303A4D);
        context.drawText(this.textRenderer, Text.literal("Status: " + statusText), panelX + 10, panelY + 244, 0xB9D9FF, false);

        super.render(context, mouseX, mouseY, delta);
        drawBlockIcons(context, mouseX, mouseY);
        drawSlotIcons(context, panelX, panelY);
    }

    private void drawSlotIcons(DrawContext context, int panelX, int panelY) {
        int[] slotX = {panelX + LEFT_X + 2, panelX + LEFT_X + 82, panelX + LEFT_X + 162};
        int slotY = panelY + SLOT_Y + 1;
        for (int i = 0; i < 3; i++) {
            int cardX = slotX[i] - 2;
            int cardY = slotY - 2;
            context.fill(cardX, cardY, cardX + 20, cardY + 20, activeSlot == i ? 0xAA2E5A95 : 0x88242A36);
            drawBorder(context, cardX, cardY, 20, 20, activeSlot == i ? 0xFF79B4FF : 0xFF596479);

            String idText = selectedSlots[i];
            if (idText == null) {
                context.drawText(this.textRenderer, Text.literal("-"), slotX[i] + 6, slotY + 6, 0xA8AFBA, false);
                continue;
            }
            Identifier id = Identifier.tryParse(idText);
            if (id == null || !Registries.BLOCK.containsId(id)) {
                continue;
            }
            ItemStack stack = new ItemStack(Registries.BLOCK.get(id).asItem());
            context.drawItem(stack, slotX[i] + 2, slotY + 2);
            context.drawText(this.textRenderer, Text.literal(shortBlockName(idText, 8)), slotX[i] + 22, slotY + 6, 0xD4E4FF, false);
        }
    }

    private void drawBlockIcons(DrawContext context, int mouseX, int mouseY) {
        hoveredBlockId = null;
        for (int i = 0; i < blockButtons.size(); i++) {
            int absolute = pageIndex * GRID_CAPACITY + i;
            if (absolute >= filteredBlockIds.size()) {
                continue;
            }

            String blockIdText = filteredBlockIds.get(absolute);
            Identifier id = Identifier.tryParse(blockIdText);
            if (id == null || !Registries.BLOCK.containsId(id)) {
                continue;
            }

            Block block = Registries.BLOCK.get(id);
            ItemStack stack = new ItemStack(block.asItem());
            ButtonWidget btn = blockButtons.get(i);
            boolean selected = isBlockInAnySlot(blockIdText);
            boolean hoveredTile = mouseX >= btn.getX() && mouseX <= btn.getX() + TILE_W && mouseY >= btn.getY() && mouseY <= btn.getY() + TILE_H;
            if (hoveredTile) {
                hoveredBlockId = blockIdText;
            }

            context.fill(btn.getX(), btn.getY(), btn.getX() + TILE_W, btn.getY() + TILE_H, hoveredTile ? 0xAA365A84 : 0x66262D3A);
            drawBorder(context, btn.getX(), btn.getY(), TILE_W, TILE_H, selected ? 0xFFE2B85C : (hoveredTile ? 0xFF9FCBFF : 0xFF596273));

            int iconX = btn.getX() + 3;
            int iconY = btn.getY() + 3;
            context.drawItem(stack, iconX, iconY);
            context.drawText(this.textRenderer, Text.literal(shortBlockName(blockIdText, 8)), btn.getX() + 22, btn.getY() + 7, 0xDFE6F2, false);
            if (selected) {
                context.fill(btn.getX() + TILE_W - 6, btn.getY() + 2, btn.getX() + TILE_W - 2, btn.getY() + 6, 0xFFE2B85C);
            }
        }

        if (hoveredBlockId != null) {
            Identifier id = Identifier.tryParse(hoveredBlockId);
            if (id != null && Registries.BLOCK.containsId(id)) {
                ItemStack stack = new ItemStack(Registries.BLOCK.get(id).asItem());
                int tipW = Math.min(190, this.textRenderer.getWidth(hoveredBlockId) + 40);
                int tipX = Math.min(mouseX + 10, this.width - tipW - 4);
                int tipY = Math.max(4, mouseY - 18);
                context.fill(tipX, tipY, tipX + tipW, tipY + 28, 0xE0222630);
                drawBorder(context, tipX, tipY, tipW, 28, 0xFF8BAAD0);
                context.fill(tipX + 4, tipY + 4, tipX + 24, tipY + 24, 0x663A4150);
                drawBorder(context, tipX + 4, tipY + 4, 20, 20, 0xFF7888A3);
                context.drawItem(stack, tipX + 6, tipY + 6);
                context.drawText(this.textRenderer, Text.literal(hoveredBlockId), tipX + 28, tipY + 10, 0xFFE6EEFA, false);
            }
        }
    }

    private void setActiveSlot(int slot) {
        this.activeSlot = slot;
        updateSlotButtons();
        statusText = "Active slot " + (slot + 1);
    }

    private void updateSlotButtons() {
        updateSlotButtonText(slot1Button, 0);
        updateSlotButtonText(slot2Button, 1);
        updateSlotButtonText(slot3Button, 2);
    }

    private void updateSlotButtonText(ButtonWidget button, int idx) {
        String prefix = activeSlot == idx ? ">" : "";
        button.setMessage(Text.literal(prefix + "S" + (idx + 1)));
    }

    private void clearSlot(int idx) {
        selectedSlots[idx] = null;
        if (activeSlot == idx) {
            statusText = "Cleared active slot " + (idx + 1);
        } else {
            statusText = "Cleared slot " + (idx + 1);
        }
        updateSlotButtons();
        updateBlockButtons();
    }

    private void assignVisibleToActiveSlot(int slotIndex) {
        int absolute = pageIndex * GRID_CAPACITY + slotIndex;
        if (absolute >= filteredBlockIds.size()) {
            return;
        }

        String blockId = filteredBlockIds.get(absolute);
        selectedSlots[activeSlot] = blockId;
        statusText = "Assigned to slot " + (activeSlot + 1);
        updateSlotButtons();
        updateBlockButtons();
    }

    private void changePage(int delta) {
        int totalPages = Math.max(1, (int) Math.ceil((double) filteredBlockIds.size() / GRID_CAPACITY));
        pageIndex = Math.max(0, Math.min(totalPages - 1, pageIndex + delta));
        updateBlockButtons();
    }

    private void cycleBlueprint(int delta) {
        if (this.blueprintField == null) {
            return;
        }
        String current = this.blueprintField.getText().trim().toLowerCase(Locale.ROOT);
        int idx = 0;
        for (int i = 0; i < BLUEPRINT_PRESETS.length; i++) {
            if (BLUEPRINT_PRESETS[i].equals(current)) {
                idx = i;
                break;
            }
        }
        int next = (idx + delta + BLUEPRINT_PRESETS.length) % BLUEPRINT_PRESETS.length;
        this.blueprintField.setText(BLUEPRINT_PRESETS[next]);
        statusText = "Blueprint: " + BLUEPRINT_PRESETS[next];
    }

    private void rebuildFilter() {
        filteredBlockIds.clear();
        String q = searchField == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
        List<String> source = allBlockIds.isEmpty() ? DEFAULT_BLOCK_IDS : allBlockIds;
        for (String id : source) {
            if (q.isEmpty() || id.toLowerCase(Locale.ROOT).contains(q)) {
                filteredBlockIds.add(id);
            }
        }
        if (filteredBlockIds.isEmpty()) {
            filteredBlockIds.add("minecraft:stone");
        }
    }

    private void ensureBlockCatalog() {
        if (!allBlockIds.isEmpty()) {
            return;
        }

        for (Identifier id : Registries.BLOCK.getIds()) {
            Block block = Registries.BLOCK.get(id);
            if (block.getDefaultState().isAir()) {
                continue;
            }
            if (!block.getDefaultState().getFluidState().isEmpty()) {
                continue;
            }
            if (block.asItem() == Items.AIR) {
                continue;
            }
            allBlockIds.add(id.toString());
        }
        allBlockIds.sort(Comparator.naturalOrder());
    }

    private void updateBlockButtons() {
        for (int i = 0; i < blockButtons.size(); i++) {
            int absolute = pageIndex * GRID_CAPACITY + i;
            ButtonWidget btn = blockButtons.get(i);

            if (absolute >= filteredBlockIds.size()) {
                btn.active = false;
                btn.visible = false;
                continue;
            }

            btn.active = true;
            btn.visible = true;
            String blockId = filteredBlockIds.get(absolute);
            btn.setMessage(Text.literal(""));
        }
    }

    private boolean isBlockInAnySlot(String blockId) {
        for (String slot : selectedSlots) {
            if (blockId.equals(slot)) {
                return true;
            }
        }
        return false;
    }

    private String shortBlockName(String blockId, int maxLen) {
        String name = blockId.startsWith("minecraft:") ? blockId.substring("minecraft:".length()) : blockId;
        return name.length() > maxLen ? name.substring(0, maxLen) : name;
    }

    private static void drawBorder(DrawContext context, int x, int y, int w, int h, int color) {
        context.fill(x, y, x + w, y + 1, color);
        context.fill(x, y + h - 1, x + w, y + h, color);
        context.fill(x, y, x + 1, y + h, color);
        context.fill(x + w - 1, y, x + w, y + h, color);
    }

    private void startBuild() {
        Integer x = parseInt(xField.getText());
        Integer y = parseInt(yField.getText());
        Integer z = parseInt(zField.getText());
        Integer count = parseInt(countField.getText());

        if (x == null || y == null || z == null || count == null) {
            statusText = "Invalid XYZ or count";
            return;
        }

        if (count < 1 || count > 4096) {
            statusText = "Count must be 1..4096";
            return;
        }

        List<String> blocks = new ArrayList<>();
        for (String slot : selectedSlots) {
            if (slot != null && !slot.isBlank() && !blocks.contains(slot)) {
                blocks.add(slot);
            }
        }
        if (blocks.isEmpty()) {
            statusText = "Assign at least one slot";
            return;
        }

        String blockSpec = String.join(",", blocks);
        String command = this.axis.equals("x")
            ? String.format("bladeplace %s %d %d %d %d", blockSpec, x, y, z, count)
            : String.format("bladeplace %s %d %d %d %d %s", blockSpec, x, y, z, count, this.axis);

        sendCommand(command);
    }

    private void cycleAxis() {
        this.axis = switch (this.axis) {
            case "x" -> "y";
            case "y" -> "z";
            default -> "x";
        };
        this.axisButton.setMessage(Text.literal("Axis: " + this.axis.toUpperCase()));
    }

    private void toggleSmartMove() {
        this.smartMoveEnabled = !this.smartMoveEnabled;
        this.smartMoveButton.setMessage(Text.literal("Smart: " + (this.smartMoveEnabled ? "ON" : "OFF")));
        sendCommand(this.smartMoveEnabled ? "blademove on" : "blademove off");
    }

    private void toggleMoveMode() {
        this.moveMode = switch (this.moveMode) {
            case "walk" -> "auto";
            case "auto" -> "teleport";
            default -> "walk";
        };
        this.moveModeButton.setMessage(Text.literal("Mode: " + this.moveMode.toUpperCase()));
        sendCommand("blademove mode " + this.moveMode);
    }

    private void togglePreviewMode() {
        this.previewBeforeBuild = !this.previewBeforeBuild;
        this.previewModeButton.setMessage(Text.literal(this.previewBeforeBuild ? "Prev:ON" : "Prev:OFF"));
        sendCommand("bladesafety preview " + (this.previewBeforeBuild ? "on" : "off"));
    }

    private void adjustReach(double delta) {
        this.reachDistance = Math.max(2.0, Math.min(8.0, this.reachDistance + delta));
        if (this.reachButton != null) {
            this.reachButton.setMessage(Text.literal(reachLabel()));
        }
        sendCommand("blademove reach " + String.format(Locale.ROOT, "%.2f", this.reachDistance));
    }

    private String reachLabel() {
        return "R:" + String.format(Locale.ROOT, "%.2f", this.reachDistance);
    }

    private void cycleProfile() {
        profileIndex = (profileIndex + 1) % PROFILE_PRESETS.length;
        String profile = PROFILE_PRESETS[profileIndex];
        this.profileButton.setMessage(Text.literal(profile.substring(0, Math.min(4, profile.length()))));
        sendCommand("bladeprofile load " + profile);
    }

    private void toggleShapeMode() {
        int idx = 0;
        for (int i = 0; i < SHAPE_MODES.length; i++) {
            if (SHAPE_MODES[i].equals(this.shapeMode)) {
                idx = i;
                break;
            }
        }
        this.shapeMode = SHAPE_MODES[(idx + 1) % SHAPE_MODES.length];
        this.shapeModeButton.setMessage(Text.literal(shapeLabel(this.shapeMode)));
        statusText = "Shape mode set to " + this.shapeMode;
    }

    private void buildShapeAction() {
        switch (this.shapeMode) {
            case "line" -> startBuild();
            case "selection" -> buildSelection();
        }
    }

    private String shapeLabel(String mode) {
        return switch (mode) {
            case "line" -> "Shape: LINE";
            case "selection" -> "Shape: SEL";
            default -> "Shape: ?";
        };
    }

    private void markSelection() {
        Integer x = parseInt(xField.getText());
        Integer y = parseInt(yField.getText());
        Integer z = parseInt(zField.getText());
        if (x == null || y == null || z == null) {
            statusText = "Invalid XYZ";
            return;
        }
        sendCommand("bladeselect add " + x + " " + y + " " + z);
    }

    private void buildSelection() {
        Integer topY = parseInt(topYField.getText());
        if (topY == null) {
            statusText = "Invalid TopY";
            return;
        }
        List<String> blocks = new ArrayList<>();
        for (String slot : selectedSlots) {
            if (slot != null && !slot.isBlank() && !blocks.contains(slot)) {
                blocks.add(slot);
            }
        }
        if (blocks.isEmpty()) {
            statusText = "Assign at least one slot";
            return;
        }
        sendCommand("bladeselect build " + String.join(",", blocks) + " " + topY);
    }

    private void loadBlueprint() {
        if (blueprintField == null) {
            statusText = "No blueprint field";
            return;
        }
        String name = blueprintField.getText().trim();
        if (name.isEmpty()) {
            statusText = "Blueprint name required";
            return;
        }
        sendCommand("bladeblueprint load " + name);
    }

    private void buildBlueprint() {
        if (blueprintField == null) {
            statusText = "No blueprint field";
            return;
        }
        Integer x = parseInt(xField.getText());
        Integer y = parseInt(yField.getText());
        Integer z = parseInt(zField.getText());
        if (x == null || y == null || z == null) {
            statusText = "Invalid XYZ";
            return;
        }
        String name = blueprintField.getText().trim();
        String blockSpec = selectedBlockSpec();
        if (name.isEmpty()) {
            sendCommand(blockSpec == null
                ? "bladeblueprint build " + x + " " + y + " " + z
                : "bladeblueprint build " + x + " " + y + " " + z + " " + blockSpec);
            return;
        }
        sendCommand(blockSpec == null
            ? "bladeblueprint build " + name + " " + x + " " + y + " " + z
            : "bladeblueprint build " + name + " " + x + " " + y + " " + z + " " + blockSpec);
    }

    private void blueprintInfo() {
        if (blueprintField == null) {
            statusText = "No blueprint field";
            return;
        }
        String name = blueprintField.getText().trim();
        if (name.isEmpty()) {
            sendCommand("bladeblueprint info");
            return;
        }
        sendCommand("bladeblueprint info " + name);
    }

    private void webCatalog() {
        sendCommand("bladeweb catalog 12");
    }

    private void webImport() {
        if (webField == null) {
            statusText = "No input field";
            return;
        }
        String value = webField.getText().trim();
        if (value.isEmpty()) {
            statusText = "Input index or URL";
            return;
        }
        Integer index = parseInt(value);
        if (index != null) {
            sendCommand("bladeweb import " + index);
            return;
        }
        sendCommand("bladeweb import " + value);
    }

    private String selectedBlockSpec() {
        List<String> blocks = new ArrayList<>();
        for (String slot : selectedSlots) {
            if (slot != null && !slot.isBlank() && !blocks.contains(slot)) {
                blocks.add(slot);
            }
        }
        if (blocks.isEmpty()) {
            return null;
        }
        return String.join(",", blocks);
    }

    private void sendCommand(String command) {
        if (this.client == null || this.client.player == null || this.client.player.networkHandler == null) {
            statusText = "No player/network";
            return;
        }

        this.client.player.networkHandler.sendChatCommand(command);
        statusText = "Ran: /" + command;
    }

    private Integer parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
