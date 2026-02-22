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

    private static final int PANEL_W = 360;
    private static final int PANEL_H = 286;
    private static final int LEFT_X = 8;
    private static final int LEFT_W = 244;
    private static final int RIGHT_X = 256;
    private static final int RIGHT_W = 96;

    private static final String MODE_LINE = "line";
    private static final String MODE_SELECTION = "selection";
    private static final String MODE_BLUEPRINT = "blueprint";
    private static final String[] BLUEPRINT_PRESETS = {"line20", "wall5x5"};
    private static final String[] PROFILE_PRESETS = {"builder", "safe", "fast"};

    private static final class UiState {
        private static String mode = MODE_LINE;
        private static String axis = "x";
        private static boolean manualCoords = false;

        private static String x = "";
        private static String y = "";
        private static String z = "";
        private static String count = "20";
        private static String height = "6";

        private static String blueprint = "line20";
        private static String web = "";
        private static String limit = "12";
        private static String search = "";

        private static boolean smart = true;
        private static boolean preview = false;
        private static String moveMode = "walk";
        private static double reach = 4.5;
        private static int profileIndex = 0;

        private static int pageIndex = 0;
        private static int activeSlot = 0;
        private static final String[] selectedSlots = {"minecraft:stone", null, null};
    }

    private final List<ButtonWidget> blockButtons = new ArrayList<>();
    private final List<String> filteredBlockIds = new ArrayList<>();
    private final List<String> allBlockIds = new ArrayList<>();
    private final String[] selectedSlots = new String[3];

    private TextFieldWidget searchField;
    private TextFieldWidget xField;
    private TextFieldWidget yField;
    private TextFieldWidget zField;
    private TextFieldWidget countField;
    private TextFieldWidget heightField;
    private TextFieldWidget blueprintField;
    private TextFieldWidget webField;
    private TextFieldWidget catalogLimitField;

    private ButtonWidget modeLineButton;
    private ButtonWidget modeSelectionButton;
    private ButtonWidget modeBlueprintButton;

    private ButtonWidget slot1Button;
    private ButtonWidget slot2Button;
    private ButtonWidget slot3Button;

    private ButtonWidget axisXButton;
    private ButtonWidget axisYButton;
    private ButtonWidget axisZButton;
    private ButtonWidget coordsModeButton;

    private ButtonWidget countMinusButton;
    private ButtonWidget countPlusButton;
    private ButtonWidget markButton;

    private ButtonWidget runButton;
    private ButtonWidget previewModeButton;
    private ButtonWidget confirmButton;
    private ButtonWidget cancelButton;

    private ButtonWidget moveModeButton;
    private ButtonWidget smartMoveButton;
    private ButtonWidget reachButton;
    private ButtonWidget reachMinusButton;
    private ButtonWidget reachPlusButton;
    private ButtonWidget profileButton;
    private ButtonWidget statusDetailButton;

    private ButtonWidget bpPrevButton;
    private ButtonWidget bpNextButton;
    private ButtonWidget bpLoadButton;
    private ButtonWidget bpBuildButton;
    private ButtonWidget webCatalogButton;
    private ButtonWidget webImportButton;

    private int pageIndex;
    private int activeSlot;
    private int profileIndex;

    private String statusText = "Ready";
    private String axis;
    private String activeMode;
    private boolean manualCoords;
    private boolean smartMoveEnabled;
    private boolean previewBeforeBuild;
    private String moveMode;
    private double reachDistance;
    private String hoveredBlockId;

    public BladelowHudScreen() {
        super(Text.literal("Bladelow Builder"));
        ensureBlockCatalog();

        this.activeMode = UiState.mode;
        this.axis = UiState.axis;
        this.manualCoords = UiState.manualCoords;
        this.smartMoveEnabled = UiState.smart;
        this.previewBeforeBuild = UiState.preview;
        this.moveMode = UiState.moveMode;
        this.reachDistance = UiState.reach;
        this.profileIndex = UiState.profileIndex;

        this.pageIndex = UiState.pageIndex;
        this.activeSlot = UiState.activeSlot;
        System.arraycopy(UiState.selectedSlots, 0, this.selectedSlots, 0, this.selectedSlots.length);

        rebuildFilter();
    }

    @Override
    protected void init() {
        int panelX = Math.max(4, this.width / 2 - PANEL_W / 2);
        int panelY = Math.max(4, this.height / 2 - PANEL_H / 2);

        this.modeLineButton = addDrawableChild(ButtonWidget.builder(Text.literal("LINE"), b -> setMode(MODE_LINE))
            .dimensions(panelX + RIGHT_X, panelY + 28, 30, 18)
            .build());
        this.modeSelectionButton = addDrawableChild(ButtonWidget.builder(Text.literal("SEL"), b -> setMode(MODE_SELECTION))
            .dimensions(panelX + RIGHT_X + 32, panelY + 28, 30, 18)
            .build());
        this.modeBlueprintButton = addDrawableChild(ButtonWidget.builder(Text.literal("BP"), b -> setMode(MODE_BLUEPRINT))
            .dimensions(panelX + RIGHT_X + 64, panelY + 28, 32, 18)
            .build());

        this.searchField = new TextFieldWidget(this.textRenderer, panelX + LEFT_X, panelY + 28, 168, 18, Text.literal("Search"));
        this.searchField.setPlaceholder(Text.literal("Search blocks"));
        this.searchField.setText(UiState.search);
        this.searchField.setChangedListener(value -> {
            pageIndex = 0;
            rebuildFilter();
            updateBlockButtons();
        });
        addDrawableChild(this.searchField);

        addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> changePage(-1))
            .dimensions(panelX + LEFT_X + 172, panelY + 28, 24, 18)
            .build());
        addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> changePage(1))
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

        int slotsY = panelY + 126;
        this.slot1Button = addDrawableChild(ButtonWidget.builder(Text.literal("S1"), b -> setActiveSlot(0))
            .dimensions(panelX + LEFT_X, slotsY, 62, 18)
            .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("x"), b -> clearSlot(0))
            .dimensions(panelX + LEFT_X + 64, slotsY, 12, 18)
            .build());

        this.slot2Button = addDrawableChild(ButtonWidget.builder(Text.literal("S2"), b -> setActiveSlot(1))
            .dimensions(panelX + LEFT_X + 80, slotsY, 62, 18)
            .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("x"), b -> clearSlot(1))
            .dimensions(panelX + LEFT_X + 144, slotsY, 12, 18)
            .build());

        this.slot3Button = addDrawableChild(ButtonWidget.builder(Text.literal("S3"), b -> setActiveSlot(2))
            .dimensions(panelX + LEFT_X + 160, slotsY, 62, 18)
            .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("x"), b -> clearSlot(2))
            .dimensions(panelX + LEFT_X + 224, slotsY, 12, 18)
            .build());

        int fieldW = 66;
        int coordsY = panelY + 150;
        this.xField = new TextFieldWidget(this.textRenderer, panelX + LEFT_X, coordsY, fieldW, 18, Text.literal("X"));
        this.yField = new TextFieldWidget(this.textRenderer, panelX + LEFT_X + 70, coordsY, fieldW, 18, Text.literal("Y"));
        this.zField = new TextFieldWidget(this.textRenderer, panelX + LEFT_X + 140, coordsY, fieldW, 18, Text.literal("Z"));
        this.coordsModeButton = addDrawableChild(ButtonWidget.builder(Text.literal("Coords"), b -> toggleCoordsMode())
            .dimensions(panelX + LEFT_X + 210, coordsY, 42, 18)
            .build());

        loadCoordFields();

        addDrawableChild(this.xField);
        addDrawableChild(this.yField);
        addDrawableChild(this.zField);

        int rowY = panelY + 172;
        this.countField = new TextFieldWidget(this.textRenderer, panelX + LEFT_X, rowY, 60, 18, Text.literal("Count"));
        this.countField.setText(UiState.count);
        addDrawableChild(this.countField);

        this.countMinusButton = addDrawableChild(ButtonWidget.builder(Text.literal("-"), b -> stepCount(-1))
            .dimensions(panelX + LEFT_X + 62, rowY, 16, 18)
            .build());
        this.countPlusButton = addDrawableChild(ButtonWidget.builder(Text.literal("+"), b -> stepCount(1))
            .dimensions(panelX + LEFT_X + 80, rowY, 16, 18)
            .build());

        this.heightField = new TextFieldWidget(this.textRenderer, panelX + LEFT_X, rowY, 60, 18, Text.literal("Height"));
        this.heightField.setText(UiState.height);
        addDrawableChild(this.heightField);

        this.axisXButton = addDrawableChild(ButtonWidget.builder(Text.literal("X"), b -> setAxis("x"))
            .dimensions(panelX + LEFT_X + 100, rowY, 24, 18)
            .build());
        this.axisYButton = addDrawableChild(ButtonWidget.builder(Text.literal("Y"), b -> setAxis("y"))
            .dimensions(panelX + LEFT_X + 126, rowY, 24, 18)
            .build());
        this.axisZButton = addDrawableChild(ButtonWidget.builder(Text.literal("Z"), b -> setAxis("z"))
            .dimensions(panelX + LEFT_X + 152, rowY, 24, 18)
            .build());

        this.markButton = addDrawableChild(ButtonWidget.builder(Text.literal("Mark"), b -> markSelection())
            .dimensions(panelX + LEFT_X + 182, rowY, 70, 18)
            .build());

        int actionY = panelY + 198;
        this.runButton = addDrawableChild(ButtonWidget.builder(Text.literal("Run"), b -> runActiveMode())
            .dimensions(panelX + LEFT_X, actionY, 62, 20)
            .build());
        this.previewModeButton = addDrawableChild(ButtonWidget.builder(Text.literal("Prev"), b -> togglePreviewMode())
            .dimensions(panelX + LEFT_X + 66, actionY, 56, 20)
            .build());
        this.confirmButton = addDrawableChild(ButtonWidget.builder(Text.literal("OK"), b -> sendCommand("bladeconfirm"))
            .dimensions(panelX + LEFT_X + 126, actionY, 56, 20)
            .build());
        this.cancelButton = addDrawableChild(ButtonWidget.builder(Text.literal("Stop"), b -> sendCommand("bladecancel"))
            .dimensions(panelX + LEFT_X + 186, actionY, 66, 20)
            .build());

        this.moveModeButton = addDrawableChild(ButtonWidget.builder(Text.literal("Mode"), b -> toggleMoveMode())
            .dimensions(panelX + RIGHT_X, panelY + 52, RIGHT_W, 18)
            .build());
        this.smartMoveButton = addDrawableChild(ButtonWidget.builder(Text.literal("Smart"), b -> toggleSmartMove())
            .dimensions(panelX + RIGHT_X, panelY + 72, RIGHT_W, 18)
            .build());
        this.reachMinusButton = addDrawableChild(ButtonWidget.builder(Text.literal("-"), b -> adjustReach(-0.25))
            .dimensions(panelX + RIGHT_X, panelY + 92, 20, 18)
            .build());
        this.reachButton = addDrawableChild(ButtonWidget.builder(Text.literal("R"), b -> {
        })
            .dimensions(panelX + RIGHT_X + 22, panelY + 92, 52, 18)
            .build());
        this.reachPlusButton = addDrawableChild(ButtonWidget.builder(Text.literal("+"), b -> adjustReach(0.25))
            .dimensions(panelX + RIGHT_X + 76, panelY + 92, 20, 18)
            .build());

        this.profileButton = addDrawableChild(ButtonWidget.builder(Text.literal("Prof"), b -> cycleProfile())
            .dimensions(panelX + RIGHT_X, panelY + 112, 46, 18)
            .build());
        this.statusDetailButton = addDrawableChild(ButtonWidget.builder(Text.literal("Stat"), b -> sendCommand("bladestatus detail"))
            .dimensions(panelX + RIGHT_X + 50, panelY + 112, 46, 18)
            .build());

        this.blueprintField = new TextFieldWidget(this.textRenderer, panelX + RIGHT_X + 14, panelY + 152, RIGHT_W - 28, 18, Text.literal("Blueprint"));
        this.blueprintField.setText(UiState.blueprint);
        addDrawableChild(this.blueprintField);

        this.bpPrevButton = addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> cycleBlueprint(-1))
            .dimensions(panelX + RIGHT_X, panelY + 152, 12, 18)
            .build());
        this.bpNextButton = addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> cycleBlueprint(1))
            .dimensions(panelX + RIGHT_X + RIGHT_W - 12, panelY + 152, 12, 18)
            .build());

        this.bpLoadButton = addDrawableChild(ButtonWidget.builder(Text.literal("BP Load"), b -> loadBlueprint())
            .dimensions(panelX + RIGHT_X, panelY + 172, RIGHT_W, 18)
            .build());
        this.bpBuildButton = addDrawableChild(ButtonWidget.builder(Text.literal("BP Build"), b -> buildBlueprint())
            .dimensions(panelX + RIGHT_X, panelY + 192, RIGHT_W, 18)
            .build());

        this.webField = new TextFieldWidget(this.textRenderer, panelX + RIGHT_X, panelY + 214, RIGHT_W, 18, Text.literal("Web"));
        this.webField.setPlaceholder(Text.literal("index or URL"));
        this.webField.setText(UiState.web);
        addDrawableChild(this.webField);

        this.catalogLimitField = new TextFieldWidget(this.textRenderer, panelX + RIGHT_X, panelY + 234, 24, 18, Text.literal("Limit"));
        this.catalogLimitField.setText(UiState.limit);
        this.catalogLimitField.setPlaceholder(Text.literal("12"));
        addDrawableChild(this.catalogLimitField);

        this.webCatalogButton = addDrawableChild(ButtonWidget.builder(Text.literal("Cat"), b -> webCatalog())
            .dimensions(panelX + RIGHT_X + 26, panelY + 234, 26, 18)
            .build());
        this.webImportButton = addDrawableChild(ButtonWidget.builder(Text.literal("Imp"), b -> webImport())
            .dimensions(panelX + RIGHT_X + 54, panelY + 234, 42, 18)
            .build());

        refreshButtonLabels();
        updateModeUi();
        updateSlotButtons();
        updateAxisButtons();
        updateBlockButtons();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int panelX = Math.max(4, this.width / 2 - PANEL_W / 2);
        int panelY = Math.max(4, this.height / 2 - PANEL_H / 2);

        context.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, 0xD0111111);
        context.fill(panelX + 1, panelY + 1, panelX + PANEL_W - 1, panelY + 24, 0xAA1F2430);
        context.fill(panelX + LEFT_X - 2, panelY + 46, panelX + LEFT_X + LEFT_W + 2, panelY + 146, 0x7722262F);
        context.fill(panelX + LEFT_X - 2, panelY + 148, panelX + LEFT_X + LEFT_W + 2, panelY + 220, 0x66303646);
        context.fill(panelX + RIGHT_X - 2, panelY + 28, panelX + RIGHT_X + RIGHT_W + 2, panelY + 254, 0x66202638);
        drawBorder(context, panelX, panelY, PANEL_W, PANEL_H, 0xFFFFFFFF);

        context.drawText(this.textRenderer, Text.literal("Bladelow Builder (P)"), panelX + 10, panelY + 10, 0xFFFFFF, false);
        context.drawText(this.textRenderer, Text.literal("Mode-based HUD"), panelX + 10, panelY + 20, 0xCFCFCF, false);

        int totalPages = Math.max(1, (int) Math.ceil((double) filteredBlockIds.size() / GRID_CAPACITY));
        context.drawText(this.textRenderer, Text.literal("Page " + (pageIndex + 1) + "/" + totalPages), panelX + LEFT_X + 170, panelY + 32, 0xCFCFCF, false);

        context.drawText(this.textRenderer, Text.literal("Slots"), panelX + LEFT_X, panelY + 116, 0xB5C9E8, false);
        context.drawText(this.textRenderer, Text.literal("XYZ"), panelX + LEFT_X, panelY + 140, 0xCFCFCF, false);
        context.drawText(this.textRenderer, Text.literal(activeMode.equals(MODE_SELECTION) ? "Height" : "Count"), panelX + LEFT_X, panelY + 164, 0xCFCFCF, false);
        context.drawText(this.textRenderer, Text.literal("Automation"), panelX + RIGHT_X, panelY + 136, 0xCFCFCF, false);

        context.fill(panelX + 6, panelY + 262, panelX + PANEL_W - 6, panelY + 280, 0x55303A4D);
        context.drawText(this.textRenderer, Text.literal("Status: " + statusText), panelX + 10, panelY + 267, 0xB9D9FF, false);

        super.render(context, mouseX, mouseY, delta);
        drawBlockIcons(context, mouseX, mouseY);
        drawSlotIcons(context, panelX, panelY);
    }

    private void drawSlotIcons(DrawContext context, int panelX, int panelY) {
        int[] slotX = {panelX + LEFT_X + 2, panelX + LEFT_X + 82, panelX + LEFT_X + 162};
        int slotY = panelY + 127;
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

            context.drawItem(stack, btn.getX() + 3, btn.getY() + 3);
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

    private void setMode(String mode) {
        this.activeMode = mode;
        updateModeUi();
        statusText = "Mode: " + mode.toUpperCase(Locale.ROOT);
    }

    private void updateModeUi() {
        boolean line = MODE_LINE.equals(activeMode);
        boolean selection = MODE_SELECTION.equals(activeMode);
        boolean blueprint = MODE_BLUEPRINT.equals(activeMode);

        countField.visible = line;
        countField.active = line;
        countMinusButton.visible = line;
        countMinusButton.active = line;
        countPlusButton.visible = line;
        countPlusButton.active = line;

        axisXButton.visible = line;
        axisXButton.active = line;
        axisYButton.visible = line;
        axisYButton.active = line;
        axisZButton.visible = line;
        axisZButton.active = line;

        heightField.visible = selection;
        heightField.active = selection;
        markButton.visible = selection;
        markButton.active = selection;

        blueprintField.visible = blueprint;
        blueprintField.active = blueprint;
        bpPrevButton.visible = blueprint;
        bpPrevButton.active = blueprint;
        bpNextButton.visible = blueprint;
        bpNextButton.active = blueprint;
        bpLoadButton.visible = blueprint;
        bpLoadButton.active = blueprint;
        bpBuildButton.visible = blueprint;
        bpBuildButton.active = blueprint;
        webField.visible = blueprint;
        webField.active = blueprint;
        catalogLimitField.visible = blueprint;
        catalogLimitField.active = blueprint;
        webCatalogButton.visible = blueprint;
        webCatalogButton.active = blueprint;
        webImportButton.visible = blueprint;
        webImportButton.active = blueprint;

        runButton.setMessage(Text.literal(switch (activeMode) {
            case MODE_LINE -> "Run Line";
            case MODE_SELECTION -> "Build Sel";
            case MODE_BLUEPRINT -> "Run BP";
            default -> "Run";
        }));

        updateModeButtons();
    }

    private void updateModeButtons() {
        modeLineButton.setMessage(Text.literal(MODE_LINE.equals(activeMode) ? "[L]" : "LINE"));
        modeSelectionButton.setMessage(Text.literal(MODE_SELECTION.equals(activeMode) ? "[S]" : "SEL"));
        modeBlueprintButton.setMessage(Text.literal(MODE_BLUEPRINT.equals(activeMode) ? "[B]" : "BP"));
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
        String label = selectedSlots[idx] == null ? "-" : shortBlockName(selectedSlots[idx], 3).toUpperCase(Locale.ROOT);
        button.setMessage(Text.literal(prefix + "S" + (idx + 1) + ":" + label));
    }

    private void clearSlot(int idx) {
        selectedSlots[idx] = null;
        statusText = "Cleared slot " + (idx + 1);
        updateSlotButtons();
        updateBlockButtons();
    }

    private void assignVisibleToActiveSlot(int slotIndex) {
        int absolute = pageIndex * GRID_CAPACITY + slotIndex;
        if (absolute >= filteredBlockIds.size()) {
            return;
        }
        String blockId = filteredBlockIds.get(absolute);
        int assigned = activeSlot;
        selectedSlots[assigned] = blockId;
        activeSlot = (activeSlot + 1) % selectedSlots.length;
        updateSlotButtons();
        updateBlockButtons();
        statusText = "S" + (assigned + 1) + " <- " + shortBlockName(blockId, 16);
    }

    private void changePage(int delta) {
        int totalPages = Math.max(1, (int) Math.ceil((double) filteredBlockIds.size() / GRID_CAPACITY));
        pageIndex = Math.max(0, Math.min(totalPages - 1, pageIndex + delta));
        updateBlockButtons();
    }

    private void rebuildFilter() {
        filteredBlockIds.clear();
        String q = searchField == null ? UiState.search : searchField.getText().trim().toLowerCase(Locale.ROOT);
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

    private static void drawBorder(DrawContext context, int x, int y, int w, int h, int color) {
        context.fill(x, y, x + w, y + 1, color);
        context.fill(x, y + h - 1, x + w, y + h, color);
        context.fill(x, y, x + 1, y + h, color);
        context.fill(x + w - 1, y, x + w, y + h, color);
    }

    private String shortBlockName(String blockId, int maxLen) {
        String name = blockId.startsWith("minecraft:") ? blockId.substring("minecraft:".length()) : blockId;
        return name.length() > maxLen ? name.substring(0, maxLen) : name;
    }

    private void stepCount(int delta) {
        Integer count = parseInt(countField.getText());
        if (count == null) {
            count = 20;
        }
        count = Math.max(1, Math.min(4096, count + delta));
        countField.setText(Integer.toString(count));
    }

    private void setAxis(String axis) {
        this.axis = axis;
        updateAxisButtons();
        statusText = "Axis: " + axis.toUpperCase(Locale.ROOT);
    }

    private void updateAxisButtons() {
        axisXButton.setMessage(Text.literal("x".equals(axis) ? "[X]" : "X"));
        axisYButton.setMessage(Text.literal("y".equals(axis) ? "[Y]" : "Y"));
        axisZButton.setMessage(Text.literal("z".equals(axis) ? "[Z]" : "Z"));
    }

    private void toggleCoordsMode() {
        manualCoords = !manualCoords;
        if (!manualCoords) {
            syncCoordsFromPlayer();
        }
        coordsModeButton.setMessage(Text.literal(manualCoords ? "Manual" : "Auto"));
        statusText = manualCoords ? "Coords: manual" : "Coords: auto";
    }

    private void loadCoordFields() {
        if (manualCoords) {
            xField.setText(UiState.x);
            yField.setText(UiState.y);
            zField.setText(UiState.z);
        } else {
            syncCoordsFromPlayer();
        }
        countField.setText(UiState.count);
        heightField.setText(UiState.height);
        coordsModeButton.setMessage(Text.literal(manualCoords ? "Manual" : "Auto"));
    }

    private void syncCoordsFromPlayer() {
        if (this.client == null || this.client.player == null) {
            return;
        }
        xField.setText(Integer.toString(this.client.player.getBlockX()));
        yField.setText(Integer.toString(this.client.player.getBlockY()));
        zField.setText(Integer.toString(this.client.player.getBlockZ()));
    }

    private record Coords(int x, int y, int z) {
    }

    private Coords effectiveCoords() {
        if (!manualCoords) {
            syncCoordsFromPlayer();
        }
        Integer x = parseInt(xField.getText());
        Integer y = parseInt(yField.getText());
        Integer z = parseInt(zField.getText());
        if (x == null || y == null || z == null) {
            return null;
        }
        return new Coords(x, y, z);
    }

    private void runActiveMode() {
        switch (activeMode) {
            case MODE_LINE -> runLineBuild();
            case MODE_SELECTION -> runSelectionBuild();
            case MODE_BLUEPRINT -> buildBlueprint();
            default -> statusText = "Unknown mode";
        }
    }

    private void runLineBuild() {
        Coords c = effectiveCoords();
        Integer count = parseInt(countField.getText());
        if (c == null || count == null) {
            statusText = "Invalid coords or count";
            return;
        }
        if (count < 1 || count > 4096) {
            statusText = "Count must be 1..4096";
            return;
        }
        String blockSpec = selectedBlockSpec();
        if (blockSpec == null) {
            statusText = "Select at least one block";
            return;
        }
        sendCommand(String.format(Locale.ROOT,
            "bladeplace %d %d %d %d %s %s",
            c.x, c.y, c.z, count, axis, blockSpec
        ));
    }

    private void runSelectionBuild() {
        Integer height = parseInt(heightField.getText());
        if (height == null || height < 1 || height > 256) {
            statusText = "Height must be 1..256";
            return;
        }
        String blockSpec = selectedBlockSpec();
        if (blockSpec == null) {
            statusText = "Select at least one block";
            return;
        }
        sendCommand("bladeselect buildh " + height + " " + blockSpec);
    }

    private void markSelection() {
        Coords c = effectiveCoords();
        if (c == null) {
            statusText = "Invalid coords";
            return;
        }
        sendCommand("bladeselect add " + c.x + " " + c.y + " " + c.z);
    }

    private void toggleSmartMove() {
        smartMoveEnabled = !smartMoveEnabled;
        smartMoveButton.setMessage(Text.literal("Smart: " + (smartMoveEnabled ? "ON" : "OFF")));
        sendCommand(smartMoveEnabled ? "blademove on" : "blademove off");
    }

    private void toggleMoveMode() {
        moveMode = switch (moveMode) {
            case "walk" -> "auto";
            case "auto" -> "teleport";
            default -> "walk";
        };
        moveModeButton.setMessage(Text.literal("Mode: " + moveMode.toUpperCase(Locale.ROOT)));
        sendCommand("blademove mode " + moveMode);
    }

    private void togglePreviewMode() {
        previewBeforeBuild = !previewBeforeBuild;
        previewModeButton.setMessage(Text.literal(previewBeforeBuild ? "Prev:ON" : "Prev:OFF"));
        sendCommand("bladesafety preview " + (previewBeforeBuild ? "on" : "off"));
    }

    private void adjustReach(double delta) {
        reachDistance = Math.max(2.0, Math.min(8.0, reachDistance + delta));
        reachButton.setMessage(Text.literal("R:" + String.format(Locale.ROOT, "%.2f", reachDistance)));
        sendCommand("blademove reach " + String.format(Locale.ROOT, "%.2f", reachDistance));
    }

    private void cycleProfile() {
        profileIndex = (profileIndex + 1) % PROFILE_PRESETS.length;
        String profile = PROFILE_PRESETS[profileIndex];
        profileButton.setMessage(Text.literal(profile.substring(0, Math.min(4, profile.length()))));
        sendCommand("bladeprofile load " + profile);
    }

    private void cycleBlueprint(int delta) {
        String current = blueprintField.getText().trim().toLowerCase(Locale.ROOT);
        int idx = 0;
        for (int i = 0; i < BLUEPRINT_PRESETS.length; i++) {
            if (BLUEPRINT_PRESETS[i].equals(current)) {
                idx = i;
                break;
            }
        }
        int next = (idx + delta + BLUEPRINT_PRESETS.length) % BLUEPRINT_PRESETS.length;
        blueprintField.setText(BLUEPRINT_PRESETS[next]);
        statusText = "Blueprint: " + BLUEPRINT_PRESETS[next];
    }

    private void loadBlueprint() {
        String name = blueprintField.getText().trim();
        if (name.isEmpty()) {
            statusText = "Blueprint name required";
            return;
        }
        sendCommand("bladeblueprint load " + name);
    }

    private void buildBlueprint() {
        Coords c = effectiveCoords();
        if (c == null) {
            statusText = "Invalid coords";
            return;
        }

        String name = blueprintField.getText().trim();
        String blockSpec = selectedBlockSpec();

        if (name.isEmpty()) {
            sendCommand(blockSpec == null
                ? "bladeblueprint build " + c.x + " " + c.y + " " + c.z
                : "bladeblueprint build " + c.x + " " + c.y + " " + c.z + " " + blockSpec);
            return;
        }

        sendCommand(blockSpec == null
            ? "bladeblueprint build " + name + " " + c.x + " " + c.y + " " + c.z
            : "bladeblueprint build " + name + " " + c.x + " " + c.y + " " + c.z + " " + blockSpec);
    }

    private void webCatalog() {
        Integer limit = parseInt(catalogLimitField.getText().trim());
        if (limit == null || limit < 1 || limit > 50) {
            statusText = "Limit must be 1..50";
            return;
        }
        sendCommand("bladeweb catalog " + limit);
    }

    private void webImport() {
        String value = webField.getText().trim();
        if (value.isEmpty()) {
            statusText = "Type catalog index or URL";
            return;
        }

        Integer index = parseInt(value);
        if (index != null) {
            if (index < 1 || index > 100) {
                statusText = "Index must be 1..100";
                return;
            }
            String importName = resolveImportName("web_idx_" + index);
            if (importName == null) {
                statusText = "Invalid import name";
                return;
            }
            blueprintField.setText(importName);
            sendCommand("bladeweb importload " + index + " " + importName);
            return;
        }

        String normalizedUrl = normalizeUrlInput(value);
        if (normalizedUrl == null) {
            statusText = "Invalid URL";
            return;
        }

        String importName = resolveImportName(suggestWebNameFromUrl(normalizedUrl));
        if (importName == null) {
            statusText = "Invalid import name";
            return;
        }

        blueprintField.setText(importName);
        sendCommand("bladeweb importloadurl " + importName + " " + normalizedUrl);
    }

    private String resolveImportName(String fallback) {
        String fromField = normalizeCommandName(blueprintField.getText());
        if (fromField != null) {
            return fromField;
        }
        return normalizeCommandName(fallback);
    }

    private String normalizeUrlInput(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.contains(" ")) {
            return null;
        }
        if (value.startsWith("https://") || value.startsWith("http://")) {
            return value;
        }
        return "https://" + value;
    }

    private String suggestWebNameFromUrl(String normalizedUrl) {
        if (normalizedUrl == null) {
            return null;
        }
        String cleaned = normalizedUrl.toLowerCase(Locale.ROOT)
            .replace("https://", "")
            .replace("http://", "")
            .replaceAll("[^a-z0-9]+", "_");
        if (cleaned.length() > 28) {
            cleaned = cleaned.substring(0, 28);
        }
        if (cleaned.isBlank()) {
            return null;
        }
        return "web_" + cleaned;
    }

    private String normalizeCommandName(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_");
        if (cleaned.isBlank()) {
            return null;
        }
        if (cleaned.length() > 32) {
            cleaned = cleaned.substring(0, 32);
        }
        return cleaned;
    }

    private String selectedBlockSpec() {
        List<String> blocks = new ArrayList<>();
        for (String slot : selectedSlots) {
            if (slot != null && !slot.isBlank()) {
                blocks.add(slot);
            }
        }
        if (blocks.isEmpty()) {
            return null;
        }
        return String.join(",", blocks);
    }

    private void refreshButtonLabels() {
        updateModeButtons();
        updateAxisButtons();
        previewModeButton.setMessage(Text.literal(previewBeforeBuild ? "Prev:ON" : "Prev:OFF"));
        moveModeButton.setMessage(Text.literal("Mode: " + moveMode.toUpperCase(Locale.ROOT)));
        smartMoveButton.setMessage(Text.literal("Smart: " + (smartMoveEnabled ? "ON" : "OFF")));
        reachButton.setMessage(Text.literal("R:" + String.format(Locale.ROOT, "%.2f", reachDistance)));
        coordsModeButton.setMessage(Text.literal(manualCoords ? "Manual" : "Auto"));
        String profile = PROFILE_PRESETS[Math.max(0, Math.min(PROFILE_PRESETS.length - 1, profileIndex))];
        profileButton.setMessage(Text.literal(profile.substring(0, Math.min(4, profile.length()))));
    }

    private void sendCommand(String command) {
        if (this.client == null || this.client.player == null || this.client.player.networkHandler == null) {
            statusText = "No player/network";
            return;
        }
        this.client.player.networkHandler.sendChatCommand(command);
        statusText = commandStatus(command);
    }

    private String commandStatus(String command) {
        if (command.startsWith("bladeweb catalog")) {
            return "Syncing web catalog...";
        }
        if (command.startsWith("bladeweb importloadurl")) {
            return "Importing URL and loading...";
        }
        if (command.startsWith("bladeweb importload")) {
            return "Importing item and loading...";
        }
        if (command.startsWith("bladeblueprint load")) {
            return "Loading blueprint...";
        }
        if (command.startsWith("bladeblueprint build")) {
            return "Queueing blueprint...";
        }
        if (command.startsWith("bladeplace")) {
            return "Queueing line build...";
        }
        if (command.startsWith("bladeselect buildh")) {
            return "Queueing selection columns...";
        }
        if (command.startsWith("bladeselect add")) {
            return "Selection updated";
        }
        if (command.startsWith("blademove") || command.startsWith("bladesafety") || command.startsWith("bladeprofile")) {
            return "Runtime updated";
        }
        return "Ran: /" + command;
    }

    private Integer parseInt(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void saveUiState() {
        UiState.mode = activeMode;
        UiState.axis = axis;
        UiState.manualCoords = manualCoords;

        UiState.smart = smartMoveEnabled;
        UiState.preview = previewBeforeBuild;
        UiState.moveMode = moveMode;
        UiState.reach = reachDistance;
        UiState.profileIndex = profileIndex;

        UiState.pageIndex = pageIndex;
        UiState.activeSlot = activeSlot;
        System.arraycopy(selectedSlots, 0, UiState.selectedSlots, 0, selectedSlots.length);

        if (searchField != null) {
            UiState.search = searchField.getText();
        }
        if (xField != null) {
            UiState.x = xField.getText();
        }
        if (yField != null) {
            UiState.y = yField.getText();
        }
        if (zField != null) {
            UiState.z = zField.getText();
        }
        if (countField != null) {
            UiState.count = countField.getText();
        }
        if (heightField != null) {
            UiState.height = heightField.getText();
        }
        if (blueprintField != null) {
            UiState.blueprint = blueprintField.getText();
        }
        if (webField != null) {
            UiState.web = webField.getText();
        }
        if (catalogLimitField != null) {
            UiState.limit = catalogLimitField.getText();
        }
    }

    @Override
    public void close() {
        saveUiState();
        super.close();
    }

    @Override
    public void removed() {
        saveUiState();
        super.removed();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
