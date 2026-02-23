package com.bladelow.client.ui;

import com.bladelow.client.BladelowHudTelemetry;
import com.bladelow.client.BladelowSelectionOverlay;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

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
    private static final int QUICK_CAPACITY = 4;
    private static final int SLOT_COUNT = 3;
    private static final int MAX_FAVORITES = 4;
    private static final int MAX_RECENT = 4;

    private static final String MODE_SELECTION = "selection";
    private static final String MODE_BLUEPRINT = "blueprint";
    private static final String FLOW_AREA = "area";
    private static final String FLOW_BLOCKS = "blocks";
    private static final String FLOW_SOURCE = "source";
    private static final String FLOW_RUN = "run";

    private static final String[] BLUEPRINT_PRESETS = {"line20", "wall5x5", "square9", "ring9"};
    private static final String[] PROFILE_PRESETS = {"builder", "safe", "fast"};
    private static final String[] SCALE_LABELS = {"S", "M", "L"};
    private static final double[] SCALE_VALUES = {0.90, 1.00, 1.12};
    private static final int PANEL_BASE_WIDTH = 880;
    private static final int PANEL_BASE_HEIGHT = 500;
    private static final int PANEL_MIN_WIDTH = 760;
    private static final int PANEL_MIN_HEIGHT = 430;

    private static final Path HUD_STATE_PATH = Path.of("config", "bladelow", "hud-state.properties");
    private static final Properties HUD_STORE = new Properties();
    private static boolean hudStoreLoaded;

    private static final class UiState {
        private static String mode = MODE_SELECTION;
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
        private static final String[] selectedSlots = {null, null, null};

        private static String favorites = "minecraft:stone|minecraft:cobblestone|minecraft:oak_planks|minecraft:glass";
        private static String recent = "";
        private static int scaleIndex = 1;
        private static boolean slotMiniIcons = true;
        private static String markerA = "";
        private static String markerB = "";
        private static String flow = FLOW_AREA;
    }

    private final String profileKey;

    private final List<String> allBlockIds = new ArrayList<>();
    private final List<String> filteredBlockIds = new ArrayList<>();

    private final List<ButtonWidget> favoriteButtons = new ArrayList<>();
    private final List<ButtonWidget> recentButtons = new ArrayList<>();
    private final List<ButtonWidget> blockButtons = new ArrayList<>();
    private final List<ButtonWidget> slotClearButtons = new ArrayList<>();

    private final List<String> favoriteBlockIds = new ArrayList<>();
    private final Deque<String> recentBlockIds = new ArrayDeque<>();
    private final String[] selectedSlots = new String[SLOT_COUNT];

    private final ButtonWidget[] slotButtons = new ButtonWidget[SLOT_COUNT];

    private TextFieldWidget searchField;
    private TextFieldWidget xField;
    private TextFieldWidget yField;
    private TextFieldWidget zField;
    private TextFieldWidget countField;
    private TextFieldWidget heightField;
    private TextFieldWidget blueprintField;
    private TextFieldWidget webField;
    private TextFieldWidget catalogLimitField;

    private ButtonWidget flowAreaButton;
    private ButtonWidget flowBlocksButton;
    private ButtonWidget flowSourceButton;
    private ButtonWidget flowRunButton;

    private ButtonWidget modeSelectionButton;
    private ButtonWidget modeBlueprintButton;
    private ButtonWidget scaleButton;
    private ButtonWidget slotMiniButton;

    private ButtonWidget addFavoriteButton;
    private ButtonWidget removeFavoriteButton;

    private ButtonWidget coordsModeButton;
    private ButtonWidget axisXButton;
    private ButtonWidget axisYButton;
    private ButtonWidget axisZButton;

    private ButtonWidget countMinusButton;
    private ButtonWidget countPlusButton;
    private ButtonWidget markButton;

    private ButtonWidget runButton;
    private ButtonWidget previewModeButton;
    private ButtonWidget confirmButton;
    private ButtonWidget cancelButton;

    private ButtonWidget moveModeButton;
    private ButtonWidget smartMoveButton;
    private ButtonWidget reachMinusButton;
    private ButtonWidget reachButton;
    private ButtonWidget reachPlusButton;
    private ButtonWidget profileButton;
    private ButtonWidget statusDetailButton;

    private ButtonWidget presetLineXButton;
    private ButtonWidget presetLineZButton;
    private ButtonWidget presetSelButton;
    private ButtonWidget presetBpButton;

    private ButtonWidget bpPrevButton;
    private ButtonWidget bpNextButton;
    private ButtonWidget bpLoadButton;
    private ButtonWidget bpBuildButton;
    private ButtonWidget webCatalogButton;
    private ButtonWidget webImportButton;
    private ButtonWidget pagePrevButton;
    private ButtonWidget pageNextButton;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    private int leftX;
    private int leftW;
    private int rightX;
    private int rightW;

    private int searchY;
    private int flowY;
    private int favoriteY;
    private int recentY;
    private int gridY;
    private int slotsY;
    private int coordsY;
    private int valueY;
    private int actionsY;
    private int statusY;

    private int tileW;
    private int tileH;
    private int rowGap;
    private int buttonH;

    private int pageIndex;
    private int activeSlot;
    private int profileIndex;
    private int uiScaleIndex;

    private String activeMode;
    private String activeFlow;
    private String axis;
    private boolean manualCoords;
    private boolean smartMoveEnabled;
    private boolean previewBeforeBuild;
    private boolean slotMiniIconsEnabled;
    private String moveMode;
    private double reachDistance;
    private BlockPos markerA;
    private BlockPos markerB;

    private double uiScale = 1.0;

    private String statusText = "Ready";
    private String validationText = "";
    private String hoveredBlockId;
    private boolean suppressFieldCallbacks;

    public BladelowHudScreen() {
        super(Text.literal("Bladelow Builder"));
        ensureBlockCatalog();

        this.profileKey = resolveProfileKey(MinecraftClient.getInstance());
        loadUiStateForProfile(profileKey);

        this.activeMode = MODE_SELECTION;
        this.activeFlow = normalizeFlow(UiState.flow);
        this.axis = UiState.axis;
        this.manualCoords = UiState.manualCoords;
        this.smartMoveEnabled = UiState.smart;
        this.previewBeforeBuild = UiState.preview;
        this.slotMiniIconsEnabled = UiState.slotMiniIcons;
        this.moveMode = UiState.moveMode;
        this.reachDistance = UiState.reach;
        this.profileIndex = UiState.profileIndex;
        this.markerA = decodePos(UiState.markerA);
        this.markerB = decodePos(UiState.markerB);
        Integer savedHeight = parseInt(UiState.height);
        BladelowSelectionOverlay.setDraftMarkers(markerA, markerB, savedHeight == null ? 1 : savedHeight);

        this.pageIndex = UiState.pageIndex;
        this.activeSlot = UiState.activeSlot;
        this.uiScaleIndex = clamp(UiState.scaleIndex, 0, SCALE_VALUES.length - 1);

        System.arraycopy(UiState.selectedSlots, 0, this.selectedSlots, 0, SLOT_COUNT);

        restoreFavorites(UiState.favorites);
        restoreRecent(UiState.recent);

        rebuildFilter();
    }

    @Override
    protected void init() {
        favoriteButtons.clear();
        recentButtons.clear();
        blockButtons.clear();
        slotClearButtons.clear();

        computeLayout();

        int flowGap = rowGap;
        int flowButtonW = (panelW - sx(16) - flowGap * 3) / 4;
        int flowX = panelX + sx(8);
        this.flowAreaButton = addDrawableChild(ButtonWidget.builder(Text.literal("AREA"), b -> setFlow(FLOW_AREA))
            .dimensions(flowX, flowY, flowButtonW, buttonH)
            .build());
        this.flowBlocksButton = addDrawableChild(ButtonWidget.builder(Text.literal("BLOCKS"), b -> setFlow(FLOW_BLOCKS))
            .dimensions(flowX + (flowButtonW + flowGap), flowY, flowButtonW, buttonH)
            .build());
        this.flowSourceButton = addDrawableChild(ButtonWidget.builder(Text.literal("SOURCE"), b -> setFlow(FLOW_SOURCE))
            .dimensions(flowX + (flowButtonW + flowGap) * 2, flowY, flowButtonW, buttonH)
            .build());
        this.flowRunButton = addDrawableChild(ButtonWidget.builder(Text.literal("RUN"), b -> setFlow(FLOW_RUN))
            .dimensions(flowX + (flowButtonW + flowGap) * 3, flowY, flowButtonW, buttonH)
            .build());

        int controlW = sx(24);
        int searchControls = controlW * 2 + rowGap;
        int searchW = Math.max(sx(100), leftW - searchControls - rowGap);

        this.searchField = new TextFieldWidget(this.textRenderer, leftX, searchY, searchW, buttonH, Text.literal("Search"));
        this.searchField.setPlaceholder(Text.literal("Search blocks (e.g. stone)"));
        this.searchField.setText(UiState.search);
        this.searchField.setChangedListener(value -> {
            pageIndex = 0;
            rebuildFilter();
            updateBlockButtons();
            updateRunGuard();
        });
        addDrawableChild(this.searchField);

        int controlX = leftX + searchW + rowGap;
        this.pagePrevButton = addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> changePage(-1))
            .dimensions(controlX, searchY, controlW, buttonH)
            .build());
        this.pageNextButton = addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> changePage(1))
            .dimensions(controlX + controlW + rowGap, searchY, controlW, buttonH)
            .build());

        this.addFavoriteButton = addDrawableChild(ButtonWidget.builder(Text.literal("+F"), b -> addFavoriteFromActiveSlot())
            .dimensions(controlX + (controlW + rowGap) * 2, searchY, controlW, buttonH)
            .build());
        this.removeFavoriteButton = addDrawableChild(ButtonWidget.builder(Text.literal("-F"), b -> removeFavoriteFromActiveSlot())
            .dimensions(controlX + (controlW + rowGap) * 3, searchY, controlW, buttonH)
            .build());

        int quickW = (leftW - rowGap * (QUICK_CAPACITY - 1)) / QUICK_CAPACITY;
        for (int i = 0; i < QUICK_CAPACITY; i++) {
            int x = leftX + i * (quickW + rowGap);
            final int idx = i;
            favoriteButtons.add(addDrawableChild(ButtonWidget.builder(Text.literal(""), b -> assignFavorite(idx))
                .dimensions(x, favoriteY, quickW, buttonH)
                .build()));
            recentButtons.add(addDrawableChild(ButtonWidget.builder(Text.literal(""), b -> assignRecent(idx))
                .dimensions(x, recentY, quickW, buttonH)
                .build()));
        }

        this.tileW = quickW;
        this.tileH = sx(30);
        for (int i = 0; i < GRID_CAPACITY; i++) {
            int col = i % GRID_COLS;
            int row = i / GRID_COLS;
            int x = leftX + col * (tileW + rowGap);
            int y = gridY + row * (tileH + rowGap);
            final int idx = i;
            blockButtons.add(addDrawableChild(ButtonWidget.builder(Text.literal(""), b -> assignVisibleToActiveSlot(idx))
                .dimensions(x, y, tileW, tileH)
                .build()));
        }

        int clearW = sx(16);
        int miniToggleW = 0;
        int slotAreaW = leftW;
        int slotButtonW = Math.max(sx(44), (slotAreaW - SLOT_COUNT * clearW - (SLOT_COUNT * 2 - 1) * rowGap) / SLOT_COUNT);
        int slotX = leftX;
        for (int i = 0; i < SLOT_COUNT; i++) {
            final int idx = i;
            slotButtons[i] = addDrawableChild(ButtonWidget.builder(Text.literal("S" + (idx + 1)), b -> setActiveSlot(idx))
                .dimensions(slotX, slotsY, slotButtonW, buttonH)
                .build());
            slotClearButtons.add(addDrawableChild(ButtonWidget.builder(Text.literal("x"), b -> clearSlot(idx))
                .dimensions(slotX + slotButtonW + rowGap, slotsY, clearW, buttonH)
                .build()));
            slotX += slotButtonW + clearW + rowGap * 2;
        }
        this.slotMiniButton = addDrawableChild(ButtonWidget.builder(Text.literal("I"), b -> toggleSlotMiniIcons())
            .dimensions(panelX + panelW + sx(16), panelY + panelH + sx(16), sx(1), sx(1))
            .build());
        this.slotMiniButton.visible = false;
        this.slotMiniButton.active = false;

        int coordW = (rightW - rowGap * 2) / 3;
        int xyzY = searchY + buttonH + rowGap;
        this.xField = new TextFieldWidget(this.textRenderer, rightX, xyzY, coordW, buttonH, Text.literal("X"));
        this.yField = new TextFieldWidget(this.textRenderer, rightX + coordW + rowGap, xyzY, coordW, buttonH, Text.literal("Y"));
        this.zField = new TextFieldWidget(this.textRenderer, rightX + (coordW + rowGap) * 2, xyzY, coordW, buttonH, Text.literal("Z"));

        this.coordsModeButton = addDrawableChild(ButtonWidget.builder(Text.literal("Auto"), b -> toggleCoordsMode())
            .dimensions(rightX, xyzY + buttonH + rowGap, rightW, buttonH)
            .build());

        addDrawableChild(this.xField);
        addDrawableChild(this.yField);
        addDrawableChild(this.zField);

        this.xField.setChangedListener(v -> onCoordFieldChanged());
        this.yField.setChangedListener(v -> onCoordFieldChanged());
        this.zField.setChangedListener(v -> onCoordFieldChanged());

        loadCoordFields();

        int axisW = sx(22);
        int valueFieldW = sx(60);
        int markW = Math.max(sx(52), leftW - valueFieldW - rowGap * 6 - axisW * 3 - sx(32));

        this.countField = new TextFieldWidget(this.textRenderer, leftX, valueY, valueFieldW, buttonH, Text.literal("Count"));
        this.countField.setText(UiState.count);
        this.countField.setChangedListener(v -> updateRunGuard());
        addDrawableChild(this.countField);

        this.countMinusButton = addDrawableChild(ButtonWidget.builder(Text.literal("-"), b -> stepCount(-1))
            .dimensions(leftX + valueFieldW + rowGap, valueY, sx(14), buttonH)
            .build());
        this.countPlusButton = addDrawableChild(ButtonWidget.builder(Text.literal("+"), b -> stepCount(1))
            .dimensions(leftX + valueFieldW + rowGap + sx(14) + rowGap, valueY, sx(14), buttonH)
            .build());

        this.heightField = new TextFieldWidget(this.textRenderer, leftX, valueY, valueFieldW, buttonH, Text.literal("Height"));
        this.heightField.setText(UiState.height);
        this.heightField.setChangedListener(v -> {
            updateRunGuard();
            syncOverlayDraft();
        });
        addDrawableChild(this.heightField);

        int axisBaseX = leftX + valueFieldW + rowGap + sx(14) + rowGap + sx(14) + rowGap;
        this.axisXButton = addDrawableChild(ButtonWidget.builder(Text.literal("X"), b -> setAxis("x"))
            .dimensions(axisBaseX, valueY, axisW, buttonH)
            .build());
        this.axisYButton = addDrawableChild(ButtonWidget.builder(Text.literal("Y"), b -> setAxis("y"))
            .dimensions(axisBaseX + axisW + rowGap, valueY, axisW, buttonH)
            .build());
        this.axisZButton = addDrawableChild(ButtonWidget.builder(Text.literal("Z"), b -> setAxis("z"))
            .dimensions(axisBaseX + (axisW + rowGap) * 2, valueY, axisW, buttonH)
            .build());

        this.markButton = addDrawableChild(ButtonWidget.builder(Text.literal("Mark"), b -> markSelection())
            .dimensions(leftX + leftW - markW, valueY, markW, buttonH)
            .build());

        int actionW = (leftW - rowGap * 3) / 4;
        this.runButton = addDrawableChild(ButtonWidget.builder(Text.literal("Start Build"), b -> runActiveMode())
            .dimensions(leftX, actionsY, actionW, buttonH)
            .build());
        this.cancelButton = addDrawableChild(ButtonWidget.builder(Text.literal("Stop"), b -> sendCommand("bladepause"))
            .dimensions(leftX + actionW + rowGap, actionsY, actionW, buttonH)
            .build());
        this.confirmButton = addDrawableChild(ButtonWidget.builder(Text.literal("Continue Build"), b -> sendCommand("bladecontinue"))
            .dimensions(leftX + (actionW + rowGap) * 2, actionsY, actionW, buttonH)
            .build());
        this.previewModeButton = addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> sendCommand("bladecancel"))
            .dimensions(leftX + (actionW + rowGap) * 3, actionsY, actionW, buttonH)
            .build());

        int modeW = (rightW - rowGap) / 2;
        this.modeSelectionButton = addDrawableChild(ButtonWidget.builder(Text.literal("SEL"), b -> setMode(MODE_SELECTION))
            .dimensions(rightX, searchY, modeW, buttonH)
            .build());
        this.modeBlueprintButton = addDrawableChild(ButtonWidget.builder(Text.literal("BP"), b -> setMode(MODE_BLUEPRINT))
            .dimensions(rightX + modeW + rowGap, searchY, modeW, buttonH)
            .build());

        int rightRowY = searchY + (buttonH + rowGap) * 3;
        this.scaleButton = addDrawableChild(ButtonWidget.builder(Text.literal("Scale"), b -> cycleScale())
            .dimensions(rightX, rightRowY, rightW, buttonH)
            .build());

        rightRowY += buttonH + rowGap;
        this.moveModeButton = addDrawableChild(ButtonWidget.builder(Text.literal("Mode"), b -> toggleMoveMode())
            .dimensions(rightX, rightRowY, rightW, buttonH)
            .build());

        rightRowY += buttonH + rowGap;
        this.webField = new TextFieldWidget(this.textRenderer, rightX, rightRowY, rightW, buttonH, Text.literal("BuildIt URL"));
        this.webField.setPlaceholder(Text.literal("builditapp url or index"));
        this.webField.setText(UiState.web);
        addDrawableChild(this.webField);

        rightRowY += buttonH + rowGap;
        this.bpLoadButton = addDrawableChild(ButtonWidget.builder(Text.literal("Import URL"), b -> webImport())
            .dimensions(rightX, rightRowY, rightW, buttonH)
            .build());

        rightRowY += buttonH + rowGap;
        int centerW = sx(18);
        int sideW = (rightW - centerW - rowGap * 2) / 2;
        this.presetLineXButton = addDrawableChild(ButtonWidget.builder(Text.literal("Set A"), b -> captureMarkerA())
            .dimensions(rightX, rightRowY, sideW, buttonH)
            .build());
        this.smartMoveButton = addDrawableChild(ButtonWidget.builder(Text.literal("S"), b -> toggleSmartMove())
            .dimensions(rightX + sideW + rowGap, rightRowY, centerW, buttonH)
            .build());
        this.presetLineZButton = addDrawableChild(ButtonWidget.builder(Text.literal("Set B"), b -> captureMarkerB())
            .dimensions(rightX + sideW + rowGap + centerW + rowGap, rightRowY, sideW, buttonH)
            .build());

        rightRowY += buttonH + rowGap;
        this.presetSelButton = addDrawableChild(ButtonWidget.builder(Text.literal("Mark Box"), b -> applyMarkerBox())
            .dimensions(rightX, rightRowY, sideW, buttonH)
            .build());
        this.profileButton = addDrawableChild(ButtonWidget.builder(Text.literal("P"), b -> cycleProfile())
            .dimensions(rightX + sideW + rowGap, rightRowY, centerW, buttonH)
            .build());
        this.presetBpButton = addDrawableChild(ButtonWidget.builder(Text.literal("Clr Mk"), b -> clearMarkers())
            .dimensions(rightX + sideW + rowGap + centerW + rowGap, rightRowY, sideW, buttonH)
            .build());

        rightRowY += buttonH + rowGap;
        int bottomW = (rightW - rowGap) / 2;
        this.bpBuildButton = addDrawableChild(ButtonWidget.builder(Text.literal("Build"), b -> buildBlueprint())
            .dimensions(rightX, rightRowY, bottomW, buttonH)
            .build());
        this.statusDetailButton = addDrawableChild(ButtonWidget.builder(Text.literal("Stat"), b -> sendCommand("bladestatus detail"))
            .dimensions(rightX + bottomW + rowGap, rightRowY, bottomW, buttonH)
            .build());

        int hiddenY = panelY + panelH + sx(12);
        this.reachMinusButton = addDrawableChild(ButtonWidget.builder(Text.literal("-"), b -> adjustReach(-0.25))
            .dimensions(rightX, hiddenY, sx(1), buttonH)
            .build());
        this.reachButton = addDrawableChild(ButtonWidget.builder(Text.literal("R"), b -> {
        })
            .dimensions(rightX, hiddenY, sx(1), buttonH)
            .build());
        this.reachPlusButton = addDrawableChild(ButtonWidget.builder(Text.literal("+"), b -> adjustReach(0.25))
            .dimensions(rightX, hiddenY, sx(1), buttonH)
            .build());

        this.blueprintField = new TextFieldWidget(this.textRenderer, rightX, hiddenY, rightW, buttonH, Text.literal("Blueprint"));
        this.blueprintField.setText(UiState.blueprint);
        this.blueprintField.setChangedListener(v -> updateRunGuard());
        addDrawableChild(this.blueprintField);

        this.bpPrevButton = addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> cycleBlueprint(-1))
            .dimensions(rightX, hiddenY, sx(1), buttonH)
            .build());
        this.bpNextButton = addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> cycleBlueprint(1))
            .dimensions(rightX, hiddenY, sx(1), buttonH)
            .build());

        this.catalogLimitField = new TextFieldWidget(this.textRenderer, rightX, hiddenY, sx(24), buttonH, Text.literal("Limit"));
        this.catalogLimitField.setText(UiState.limit);
        addDrawableChild(this.catalogLimitField);

        this.webCatalogButton = addDrawableChild(ButtonWidget.builder(Text.literal("Cat"), b -> webCatalog())
            .dimensions(rightX, hiddenY, sx(1), buttonH)
            .build());
        this.webImportButton = addDrawableChild(ButtonWidget.builder(Text.literal("Imp"), b -> webImport())
            .dimensions(rightX, hiddenY, sx(1), buttonH)
            .build());

        refreshButtonLabels();
        updateModeUi();
        updateFlowUi();
        updateSlotButtons();
        updateQuickButtons();
        updateBlockButtons();
        updateAxisButtons();
        updateRunGuard();
    }

    private void computeLayout() {
        double requested = SCALE_VALUES[clamp(uiScaleIndex, 0, SCALE_VALUES.length - 1)];
        double fitW = (this.width - 8.0) / PANEL_BASE_WIDTH;
        double fitH = (this.height - 8.0) / PANEL_BASE_HEIGHT;
        double fit = Math.min(fitW, fitH);
        this.uiScale = Math.max(0.68, Math.min(requested, fit));

        int desiredPanelW = sx(PANEL_BASE_WIDTH);
        int desiredPanelH = sx(PANEL_BASE_HEIGHT);
        int maxPanelW = Math.max(sx(PANEL_MIN_WIDTH), this.width - sx(8));
        int maxPanelH = Math.max(sx(PANEL_MIN_HEIGHT), this.height - sx(8));
        this.panelW = Math.max(sx(PANEL_MIN_WIDTH), Math.min(desiredPanelW, maxPanelW));
        this.panelH = Math.max(sx(PANEL_MIN_HEIGHT), Math.min(desiredPanelH, maxPanelH));
        this.panelX = Math.max(4, this.width / 2 - panelW / 2);
        this.panelY = Math.max(4, this.height / 2 - panelH / 2);

        this.rowGap = sx(6);
        this.buttonH = sx(22);

        int minRightW = sx(220);
        int maxRightW = Math.max(minRightW, panelW - sx(340));
        int targetRightW = Math.max(minRightW, panelW / 3);
        this.rightW = Math.min(maxRightW, targetRightW);

        this.leftX = panelX + sx(8);
        this.rightX = panelX + panelW - rightW - sx(8);
        this.leftW = rightX - leftX - sx(8);

        this.flowY = panelY + sx(24);
        this.searchY = flowY + buttonH + rowGap;
        // Favorites/recent rows are intentionally hidden in the simplified layout.
        this.favoriteY = panelY + panelH + sx(48);
        this.recentY = favoriteY + buttonH + rowGap;
        this.gridY = searchY + buttonH + rowGap;
        this.slotsY = gridY + (sx(30) + rowGap) * GRID_ROWS + rowGap;
        this.coordsY = slotsY + buttonH + rowGap;
        this.valueY = coordsY + buttonH + rowGap;
        this.actionsY = valueY + buttonH + rowGap;
        this.statusY = actionsY + sx(28) + rowGap;
    }

    private int sx(int base) {
        return Math.max(1, (int) Math.round(base * uiScale));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        drawPanelBackground(context);

        super.render(context, mouseX, mouseY, delta);

        hoveredBlockId = null;
        drawBlockGrid(context, mouseX, mouseY);
        drawSlotCards(context);
        drawStatusPanel(context);

        if (hoveredBlockId != null) {
            drawBlockTooltip(context, mouseX, mouseY, hoveredBlockId);
        }
    }

    private void drawPanelBackground(DrawContext context) {
        int headerY = panelY + sx(22);
        int contentBottom = actionsY + sx(20) + rowGap;

        context.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xD0111111);
        context.fill(panelX + 1, panelY + 1, panelX + panelW - 1, panelY + sx(20), 0xAA1F2430);
        context.fill(leftX - 2, headerY + rowGap, leftX + leftW + 2, contentBottom, 0x66222A38);
        context.fill(rightX - 2, headerY + rowGap, rightX + rightW + 2, contentBottom, 0x66202935);
        context.fill(panelX + sx(6), statusY, panelX + panelW - sx(6), panelY + panelH - sx(6), 0x55303A4D);
        drawBorder(context, panelX, panelY, panelW, panelH, 0xFFFFFFFF);

    }

    private void drawQuickRows(DrawContext context, int mouseX, int mouseY) {
        hoveredBlockId = null;
        drawButtonBlockRow(context, favoriteButtons, favoriteBlockIds, mouseX, mouseY, 0xFF78B5FF, false);
    }

    private void drawBlockGrid(DrawContext context, int mouseX, int mouseY) {
        for (int i = 0; i < blockButtons.size(); i++) {
            int absolute = pageIndex * GRID_CAPACITY + i;
            if (absolute >= filteredBlockIds.size()) {
                continue;
            }

            String blockIdText = filteredBlockIds.get(absolute);
            ButtonWidget btn = blockButtons.get(i);
            if (!btn.visible) {
                continue;
            }
            renderBlockCard(context, btn, blockIdText, isBlockInAnySlot(blockIdText), mouseX, mouseY, 0xFFE2B85C);
        }
    }

    private void drawButtonBlockRow(DrawContext context, List<ButtonWidget> buttons, List<String> blockIds, int mouseX, int mouseY, int selectedColor, boolean selectedAware) {
        for (int i = 0; i < buttons.size(); i++) {
            ButtonWidget btn = buttons.get(i);
            if (i >= blockIds.size()) {
                continue;
            }
            String blockId = blockIds.get(i);
            boolean selected = selectedAware && isBlockInAnySlot(blockId);
            renderBlockCard(context, btn, blockId, selected, mouseX, mouseY, selectedColor);
        }
    }

    private void renderBlockCard(DrawContext context, ButtonWidget btn, String blockIdText, boolean selected, int mouseX, int mouseY, int selectedColor) {
        Identifier id = Identifier.tryParse(blockIdText);
        if (id == null || !Registries.BLOCK.containsId(id)) {
            return;
        }

        Block block = Registries.BLOCK.get(id);
        ItemStack stack = new ItemStack(block.asItem());

        boolean hoveredTile = mouseX >= btn.getX() && mouseX <= btn.getX() + btn.getWidth() && mouseY >= btn.getY() && mouseY <= btn.getY() + btn.getHeight();
        if (hoveredTile) {
            hoveredBlockId = blockIdText;
        }

        context.fill(btn.getX(), btn.getY(), btn.getX() + btn.getWidth(), btn.getY() + btn.getHeight(), hoveredTile ? 0x883A4F6D : 0x552A313F);
        drawBorder(context, btn.getX(), btn.getY(), btn.getWidth(), btn.getHeight(), selected ? selectedColor : (hoveredTile ? 0xFF9FCBFF : 0xFF596273));

        int iconX = btn.getX() + (btn.getWidth() - 16) / 2;
        int iconY = btn.getY() + (btn.getHeight() - 16) / 2;
        context.drawItem(stack, iconX, iconY);
        if (selected) {
            context.fill(btn.getX() + btn.getWidth() - sx(7), btn.getY() + sx(2), btn.getX() + btn.getWidth() - sx(2), btn.getY() + sx(7), selectedColor);
        }
    }

    private void drawBlockTooltip(DrawContext context, int mouseX, int mouseY, String blockId) {
        Identifier id = Identifier.tryParse(blockId);
        if (id == null || !Registries.BLOCK.containsId(id)) {
            return;
        }
        ItemStack stack = new ItemStack(Registries.BLOCK.get(id).asItem());
        int tipW = Math.min(sx(220), this.textRenderer.getWidth(blockId) + sx(40));
        int tipX = Math.min(mouseX + sx(10), this.width - tipW - sx(4));
        int tipY = Math.max(sx(4), mouseY - sx(20));

        context.fill(tipX, tipY, tipX + tipW, tipY + sx(28), 0xE0222630);
        drawBorder(context, tipX, tipY, tipW, sx(28), 0xFF8BAAD0);
        context.fill(tipX + sx(4), tipY + sx(4), tipX + sx(24), tipY + sx(24), 0x663A4150);
        drawBorder(context, tipX + sx(4), tipY + sx(4), sx(20), sx(20), 0xFF7888A3);
        context.drawItem(stack, tipX + sx(6), tipY + sx(6));
        context.drawText(this.textRenderer, Text.literal(blockId), tipX + sx(28), tipY + sx(10), 0xFFE6EEFA, false);
    }

    private void drawSlotCards(DrawContext context) {
        for (int i = 0; i < SLOT_COUNT; i++) {
            ButtonWidget slotButton = slotButtons[i];
            if (slotButton == null || !slotButton.visible) {
                continue;
            }

            String idText = selectedSlots[i];
            String rawLabel = slotDisplayLabel(i, idText);
            int textX = slotButton.getX() + sx(6);
            if (slotMiniIconsEnabled) {
                ItemStack slotStack = stackForBlock(idText);
                if (slotStack != null) {
                    context.drawItem(slotStack, slotButton.getX() + sx(4), slotButton.getY() + (slotButton.getHeight() - 16) / 2);
                    textX += sx(18);
                }
            }
            int textMax = Math.max(sx(18), slotButton.getWidth() - (textX - slotButton.getX()) - sx(8));
            String clippedLabel = this.textRenderer.trimToWidth(rawLabel, textMax);
            int textY = slotButton.getY() + (slotButton.getHeight() - this.textRenderer.fontHeight) / 2;
            context.drawText(this.textRenderer, Text.literal(clippedLabel), textX, textY, activeSlot == i ? 0xFFE9F6FF : 0xFFCFD8E9, false);

            boolean ready = isSlotReady(idText);
            int dotColor = idText == null ? 0xFF808080 : (ready ? 0xFF75D77F : 0xFFE07171);
            int dotSize = sx(5);
            int dotX = slotButton.getX() + slotButton.getWidth() - dotSize - sx(4);
            int dotY = slotButton.getY() + (slotButton.getHeight() - dotSize) / 2;
            context.fill(dotX, dotY, dotX + dotSize, dotY + dotSize, dotColor);

            if (activeSlot == i) {
                drawBorder(context, slotButton.getX(), slotButton.getY(), slotButton.getWidth(), slotButton.getHeight(), 0xFF77B5FF);
            }
        }
    }

    private String slotDisplayLabel(int slotIndex, String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return "S" + (slotIndex + 1) + ": empty";
        }
        return "S" + (slotIndex + 1) + ": " + shortBlockName(blockId, 12);
    }

    private ItemStack stackForBlock(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return null;
        }
        Identifier id = Identifier.tryParse(blockId);
        if (id == null || !Registries.BLOCK.containsId(id)) {
            return null;
        }
        Item item = Registries.BLOCK.get(id).asItem();
        if (item == Items.AIR) {
            return null;
        }
        return new ItemStack(item);
    }

    private void drawStatusPanel(DrawContext context) {
        int barX = panelX + sx(8);
        int barY = statusY;
        int barW = panelW - sx(16);
        int barH = sx(24);

        boolean hasValidation = !validationText.isEmpty();
        boolean isWarning = hasValidation || statusLooksError(statusText);
        int bgColor = isWarning ? 0xAA311C1C : 0xAA16202C;
        int borderColor = isWarning ? 0xFFBE6C6C : 0xFF5D6E88;
        int textColor = isWarning ? 0xFFFFCECE : 0xFFD7E6FA;

        context.fill(barX, barY, barX + barW, barY + barH, bgColor);
        drawBorder(context, barX, barY, barW, barH, borderColor);

        String primary = hasValidation ? "Need: " + validationText : statusText;
        String secondary = hasValidation ? "Fix required fields to run." : modeHintText();
        String clippedPrimary = this.textRenderer.trimToWidth(primary, barW - sx(10));
        String clippedSecondary = this.textRenderer.trimToWidth(secondary, barW - sx(10));
        context.drawText(this.textRenderer, Text.literal(clippedPrimary), barX + sx(4), barY + sx(3), textColor, false);
        context.drawText(this.textRenderer, Text.literal(clippedSecondary), barX + sx(4), barY + sx(13), 0xFFB7C7DF, false);
    }

    private boolean statusLooksError(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.startsWith("invalid")
            || lower.startsWith("no ")
            || lower.startsWith("need")
            || lower.contains("required")
            || lower.contains("must");
    }

    private String modeHintText() {
        return switch (activeFlow) {
            case FLOW_AREA -> "Area: set XYZ/A/B, height, then Mark Box.";
            case FLOW_BLOCKS -> "Blocks: search and assign up to 3 slot blocks.";
            case FLOW_SOURCE -> "Source: choose SEL/BP and optional BuildIt import.";
            case FLOW_RUN -> "Run: Start, Stop, Continue, Confirm, and status.";
            default -> "Ready";
        };
    }

    private void setFlow(String flow) {
        this.activeFlow = normalizeFlow(flow);
        updateFlowUi();
        updateRunGuard();
        statusText = "Step: " + activeFlow.toUpperCase(Locale.ROOT);
    }

    private String normalizeFlow(String flow) {
        if (FLOW_BLOCKS.equals(flow) || FLOW_SOURCE.equals(flow) || FLOW_RUN.equals(flow)) {
            return flow;
        }
        return FLOW_AREA;
    }

    private void updateFlowUi() {
        boolean area = FLOW_AREA.equals(activeFlow);
        boolean blocks = FLOW_BLOCKS.equals(activeFlow);
        boolean source = FLOW_SOURCE.equals(activeFlow);
        boolean run = FLOW_RUN.equals(activeFlow);

        setVisible(searchField, blocks);
        setVisible(pagePrevButton, blocks);
        setVisible(pageNextButton, blocks);
        setVisible(addFavoriteButton, blocks);
        setVisible(removeFavoriteButton, blocks);
        for (ButtonWidget b : blockButtons) {
            setVisible(b, blocks);
        }
        for (ButtonWidget b : slotButtons) {
            setVisible(b, blocks);
        }
        for (ButtonWidget b : slotClearButtons) {
            setVisible(b, blocks);
        }
        if (slotMiniButton != null) {
            slotMiniButton.visible = false;
            slotMiniButton.active = false;
        }

        setVisible(xField, area);
        setVisible(yField, area);
        setVisible(zField, area);
        setVisible(coordsModeButton, area);
        setVisible(heightField, area);
        setVisible(axisXButton, area);
        setVisible(axisYButton, area);
        setVisible(axisZButton, area);
        setVisible(markButton, area);
        setVisible(presetLineXButton, area);
        setVisible(presetLineZButton, area);
        setVisible(presetSelButton, area);
        setVisible(presetBpButton, area);
        setVisible(countField, false);
        setVisible(countMinusButton, false);
        setVisible(countPlusButton, false);

        setVisible(modeSelectionButton, source);
        setVisible(modeBlueprintButton, source);
        setVisible(webField, source);
        setVisible(bpLoadButton, source);

        boolean blueprintSource = source && MODE_BLUEPRINT.equals(activeMode);
        setVisible(blueprintField, blueprintSource);
        setVisible(bpPrevButton, false);
        setVisible(bpNextButton, false);
        setVisible(webCatalogButton, false);
        setVisible(webImportButton, false);
        setVisible(catalogLimitField, false);

        setVisible(runButton, run);
        setVisible(cancelButton, run);
        setVisible(confirmButton, run);
        setVisible(previewModeButton, run);
        setVisible(moveModeButton, run);
        setVisible(smartMoveButton, run);
        setVisible(profileButton, run);
        setVisible(statusDetailButton, run);
        setVisible(bpBuildButton, run && MODE_BLUEPRINT.equals(activeMode));
        setVisible(scaleButton, run);

        setVisible(reachMinusButton, false);
        setVisible(reachButton, false);
        setVisible(reachPlusButton, false);

        updateFlowButtons();
        updateBlockButtons();
        updateSlotButtons();
    }

    private void updateFlowButtons() {
        if (flowAreaButton == null) {
            return;
        }
        flowAreaButton.setMessage(Text.literal(FLOW_AREA.equals(activeFlow) ? "AREA*" : "AREA"));
        flowBlocksButton.setMessage(Text.literal(FLOW_BLOCKS.equals(activeFlow) ? "BLOCKS*" : "BLOCKS"));
        flowSourceButton.setMessage(Text.literal(FLOW_SOURCE.equals(activeFlow) ? "SOURCE*" : "SOURCE"));
        flowRunButton.setMessage(Text.literal(FLOW_RUN.equals(activeFlow) ? "RUN*" : "RUN"));
    }

    private void setVisible(ButtonWidget widget, boolean visible) {
        if (widget == null) {
            return;
        }
        widget.visible = visible;
        widget.active = visible;
    }

    private void setVisible(TextFieldWidget widget, boolean visible) {
        if (widget == null) {
            return;
        }
        widget.visible = visible;
        widget.active = visible;
    }

    private void setMode(String mode) {
        this.activeMode = mode;
        updateModeUi();
        updateRunGuard();
        statusText = "Mode: " + this.activeMode.toUpperCase(Locale.ROOT);
    }

    private void updateModeUi() {
        runButton.setMessage(Text.literal("Start Build"));
        cancelButton.setMessage(Text.literal("Stop"));
        confirmButton.setMessage(Text.literal("Continue Build"));
        previewModeButton.setMessage(Text.literal("Cancel"));

        updateModeButtons();
        updateMarkerButtonLabels();
        updateFlowUi();
    }

    private void updateModeButtons() {
        modeSelectionButton.setMessage(Text.literal(MODE_SELECTION.equals(activeMode) ? "AREA*" : "AREA"));
        modeBlueprintButton.setMessage(Text.literal(MODE_BLUEPRINT.equals(activeMode) ? "BP*" : "BP"));
    }

    private void updateQuickButtons() {
        for (int i = 0; i < favoriteButtons.size(); i++) {
            ButtonWidget b = favoriteButtons.get(i);
            b.visible = false;
            b.active = false;
            b.setMessage(Text.literal(""));
        }
        for (int i = 0; i < recentButtons.size(); i++) {
            ButtonWidget b = recentButtons.get(i);
            b.visible = false;
            b.active = false;
            b.setMessage(Text.literal(""));
        }
        if (addFavoriteButton != null) {
            addFavoriteButton.visible = false;
            addFavoriteButton.active = false;
        }
        if (removeFavoriteButton != null) {
            removeFavoriteButton.visible = false;
            removeFavoriteButton.active = false;
        }
    }

    private void updateBlockButtons() {
        boolean show = FLOW_BLOCKS.equals(activeFlow);
        for (int i = 0; i < blockButtons.size(); i++) {
            int absolute = pageIndex * GRID_CAPACITY + i;
            ButtonWidget btn = blockButtons.get(i);
            boolean visible = show && absolute < filteredBlockIds.size();
            btn.visible = visible;
            btn.active = visible;
            btn.setMessage(Text.literal(""));
        }
    }

    private void setActiveSlot(int slot) {
        activeSlot = clamp(slot, 0, SLOT_COUNT - 1);
        updateSlotButtons();
        updateQuickButtons();
        statusText = "Active slot " + (activeSlot + 1);
    }

    private void updateSlotButtons() {
        for (int i = 0; i < SLOT_COUNT; i++) {
            ButtonWidget button = slotButtons[i];
            if (button == null) {
                continue;
            }
            button.setMessage(Text.literal(""));
        }
    }

    private void clearSlot(int idx) {
        selectedSlots[idx] = null;
        updateSlotButtons();
        updateQuickButtons();
        updateRunGuard();
        statusText = "Cleared slot " + (idx + 1);
    }

    private void assignFavorite(int idx) {
        if (idx >= favoriteBlockIds.size()) {
            return;
        }
        assignBlockToActiveSlot(favoriteBlockIds.get(idx));
    }

    private void assignRecent(int idx) {
        if (idx >= recentBlockIds.size()) {
            return;
        }
        assignBlockToActiveSlot(new ArrayList<>(recentBlockIds).get(idx));
    }

    private void assignVisibleToActiveSlot(int buttonIndex) {
        int absolute = pageIndex * GRID_CAPACITY + buttonIndex;
        if (absolute >= filteredBlockIds.size()) {
            return;
        }
        assignBlockToActiveSlot(filteredBlockIds.get(absolute));
    }

    private void assignBlockToActiveSlot(String blockId) {
        int assigned = activeSlot;
        selectedSlots[assigned] = blockId;
        activeSlot = (activeSlot + 1) % SLOT_COUNT;

        addRecent(blockId);

        updateSlotButtons();
        updateQuickButtons();
        updateRunGuard();
        statusText = "S" + (assigned + 1) + " <- " + shortBlockName(blockId, 16);
    }

    private void addFavoriteFromActiveSlot() {
        String slotBlock = selectedSlots[activeSlot];
        if (slotBlock == null || slotBlock.isBlank()) {
            statusText = "Select a block in active slot first";
            return;
        }
        favoriteBlockIds.remove(slotBlock);
        favoriteBlockIds.add(0, slotBlock);
        trimFront(favoriteBlockIds, MAX_FAVORITES);
        updateQuickButtons();
        statusText = "Added favorite: " + shortBlockName(slotBlock, 20);
    }

    private void removeFavoriteFromActiveSlot() {
        String slotBlock = selectedSlots[activeSlot];
        if (slotBlock == null || slotBlock.isBlank()) {
            statusText = "No block in active slot";
            return;
        }
        boolean removed = favoriteBlockIds.remove(slotBlock);
        updateQuickButtons();
        statusText = removed ? "Removed favorite" : "Active slot block not in favorites";
    }

    private void addRecent(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return;
        }
        recentBlockIds.remove(blockId);
        recentBlockIds.addFirst(blockId);
        while (recentBlockIds.size() > MAX_RECENT) {
            recentBlockIds.removeLast();
        }
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
        updateRunGuard();
    }

    private void setAxis(String axis) {
        this.axis = axis;
        updateAxisButtons();
        updateRunGuard();
        statusText = "Axis: " + axis.toUpperCase(Locale.ROOT);
    }

    private void updateAxisButtons() {
        axisXButton.setMessage(Text.literal("x".equals(axis) ? "X*" : "X"));
        axisYButton.setMessage(Text.literal("y".equals(axis) ? "Y*" : "Y"));
        axisZButton.setMessage(Text.literal("z".equals(axis) ? "Z*" : "Z"));
    }

    private void toggleCoordsMode() {
        manualCoords = !manualCoords;
        if (!manualCoords) {
            syncCoordsFromPlayer();
        }
        coordsModeButton.setMessage(Text.literal(manualCoords ? "Manual" : "Auto"));
        updateRunGuard();
        statusText = manualCoords ? "Coords: manual" : "Coords: auto";
    }

    private void loadCoordFields() {
        if (manualCoords) {
            withSuppressedFieldCallbacks(() -> {
                xField.setText(UiState.x);
                yField.setText(UiState.y);
                zField.setText(UiState.z);
            });
        } else {
            syncCoordsFromPlayer();
        }
        coordsModeButton.setMessage(Text.literal(manualCoords ? "Manual" : "Auto"));
    }

    private void syncCoordsFromPlayer() {
        if (this.client == null || this.client.player == null || xField == null || yField == null || zField == null) {
            return;
        }
        withSuppressedFieldCallbacks(() -> {
            xField.setText(Integer.toString(this.client.player.getBlockX()));
            yField.setText(Integer.toString(this.client.player.getBlockY()));
            zField.setText(Integer.toString(this.client.player.getBlockZ()));
        });
    }

    private void withSuppressedFieldCallbacks(Runnable action) {
        boolean wasSuppressed = suppressFieldCallbacks;
        suppressFieldCallbacks = true;
        try {
            action.run();
        } finally {
            suppressFieldCallbacks = wasSuppressed;
        }
    }

    private void onCoordFieldChanged() {
        if (suppressFieldCallbacks) {
            return;
        }
        updateRunGuard();
    }

    private record Coords(int x, int y, int z) {
    }

    private Coords effectiveCoords() {
        if (xField == null || yField == null || zField == null) {
            return null;
        }
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
        updateRunGuard();
        if (!validationText.isEmpty()) {
            statusText = validationText;
            return;
        }

        switch (activeMode) {
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
        if (markerA == null || markerB == null) {
            statusText = "Set marker A and marker B first";
            return;
        }
        String blockSpec = selectedBlockSpec();
        if (blockSpec == null) {
            statusText = "Select at least one block";
            return;
        }
        sendCommand(String.format(Locale.ROOT,
            "bladeselect markerbox %d %d %d %d %d %d %d solid",
            markerA.getX(), markerA.getY(), markerA.getZ(),
            markerB.getX(), markerB.getY(), markerB.getZ(),
            height
        ));
        sendCommand("bladeselect buildh " + height + " " + blockSpec);
    }

    private void markSelection() {
        Coords c = effectiveCoords();
        if (c == null) {
            statusText = "Invalid coords";
            return;
        }
        BlockPos marker = new BlockPos(c.x, c.y, c.z);
        if (markerA == null) {
            markerA = marker;
            updateMarkerButtonLabels();
            syncOverlayDraft();
            statusText = "Marker A set: " + shortTarget(markerA);
            return;
        }
        markerB = marker;
        updateMarkerButtonLabels();
        syncOverlayDraft();
        statusText = "Marker B set: " + shortTarget(markerB);
        applyMarkerBox();
    }

    private void captureMarkerA() {
        Coords c = effectiveCoords();
        if (c == null) {
            statusText = "Invalid coords for marker A";
            return;
        }
        markerA = new BlockPos(c.x, c.y, c.z);
        updateMarkerButtonLabels();
        syncOverlayDraft();
        statusText = "Marker A set: " + shortTarget(markerA);
    }

    private void captureMarkerB() {
        Coords c = effectiveCoords();
        if (c == null) {
            statusText = "Invalid coords for marker B";
            return;
        }
        markerB = new BlockPos(c.x, c.y, c.z);
        updateMarkerButtonLabels();
        syncOverlayDraft();
        statusText = "Marker B set: " + shortTarget(markerB);
    }

    private void applyMarkerBox() {
        if (markerA == null || markerB == null) {
            statusText = "Set marker A and marker B first";
            return;
        }
        Integer height = parseInt(heightField.getText());
        if (height == null || height < 1 || height > 256) {
            statusText = "Height must be 1..256";
            return;
        }
        sendCommand(String.format(Locale.ROOT,
            "bladeselect markerbox %d %d %d %d %d %d %d solid",
            markerA.getX(), markerA.getY(), markerA.getZ(),
            markerB.getX(), markerB.getY(), markerB.getZ(),
            height
        ));
        BladelowSelectionOverlay.setMarkers(markerA, markerB, height);
    }

    private void clearMarkers() {
        markerA = null;
        markerB = null;
        updateMarkerButtonLabels();
        BladelowSelectionOverlay.clear();
        sendCommand("bladeselect clear");
        statusText = "Markers cleared";
    }

    private void syncOverlayDraft() {
        Integer height = parseInt(heightField == null ? null : heightField.getText());
        BladelowSelectionOverlay.setDraftMarkers(markerA, markerB, height == null ? 1 : height);
    }

    private String shortTarget(BlockPos pos) {
        if (pos == null) {
            return "-";
        }
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private void updateMarkerButtonLabels() {
        if (markButton != null) {
            String markLabel = markerA == null ? "Mark A" : (markerB == null ? "Mark B" : "Re-Mark B");
            markButton.setMessage(Text.literal(markLabel));
        }
        if (presetLineXButton != null) {
            presetLineXButton.setMessage(Text.literal(markerA == null ? "Set A" : "Set A*"));
        }
        if (presetLineZButton != null) {
            presetLineZButton.setMessage(Text.literal(markerB == null ? "Set B" : "Set B*"));
        }
        if (presetSelButton != null) {
            presetSelButton.setMessage(Text.literal("Mark Box"));
        }
        if (presetBpButton != null) {
            presetBpButton.setMessage(Text.literal("Clear Mk"));
        }
    }

    private void toggleSmartMove() {
        smartMoveEnabled = !smartMoveEnabled;
        smartMoveButton.setMessage(Text.literal(smartMoveEnabled ? "S*" : "S"));
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

    private void toggleSlotMiniIcons() {
        slotMiniIconsEnabled = !slotMiniIconsEnabled;
        if (slotMiniButton != null) {
            slotMiniButton.setMessage(Text.literal(slotMiniIconsEnabled ? "I*" : "I"));
        }
        statusText = slotMiniIconsEnabled ? "Slot icons enabled" : "Slot icons hidden";
    }

    private void adjustReach(double delta) {
        reachDistance = Math.max(2.0, Math.min(8.0, reachDistance + delta));
        reachButton.setMessage(Text.literal("R:" + String.format(Locale.ROOT, "%.2f", reachDistance)));
        sendCommand("blademove reach " + String.format(Locale.ROOT, "%.2f", reachDistance));
    }

    private void cycleProfile() {
        profileIndex = (profileIndex + 1) % PROFILE_PRESETS.length;
        String profile = PROFILE_PRESETS[profileIndex];
        profileButton.setMessage(Text.literal("P" + (profileIndex + 1)));
        sendCommand("bladeprofile load " + profile);
    }

    private void applyPreset(String preset) {
        switch (preset) {
            case "line20x", "line20z" -> {
                setMode(MODE_SELECTION);
                heightField.setText("6");
            }
            case "sel6" -> {
                setMode(MODE_SELECTION);
                heightField.setText("6");
            }
            case "bp20" -> {
                setMode(MODE_BLUEPRINT);
                blueprintField.setText("line20");
            }
            default -> {
            }
        }
        updateRunGuard();
        statusText = "Preset: " + preset;
    }

    private void cycleScale() {
        uiScaleIndex = (uiScaleIndex + 1) % SCALE_VALUES.length;
        statusText = "Scale: " + SCALE_LABELS[uiScaleIndex];
        saveUiState();
        if (this.client != null) {
            this.client.setScreen(new BladelowHudScreen());
        }
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
        updateRunGuard();
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
        Coords c;
        if (markerA != null) {
            c = new Coords(markerA.getX(), markerA.getY(), markerA.getZ());
        } else {
            c = effectiveCoords();
        }
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
        updateFlowButtons();
        updateAxisButtons();
        previewModeButton.setMessage(Text.literal("Cancel"));
        confirmButton.setMessage(Text.literal("Continue Build"));
        cancelButton.setMessage(Text.literal("Stop"));
        runButton.setMessage(Text.literal("Start Build"));
        moveModeButton.setMessage(Text.literal("Mode: " + moveMode.toUpperCase(Locale.ROOT)));
        smartMoveButton.setMessage(Text.literal(smartMoveEnabled ? "S*" : "S"));
        reachButton.setMessage(Text.literal("R:" + String.format(Locale.ROOT, "%.2f", reachDistance)));
        coordsModeButton.setMessage(Text.literal(manualCoords ? "Manual" : "Auto"));
        if (slotMiniButton != null) {
            slotMiniButton.setMessage(Text.literal(slotMiniIconsEnabled ? "I*" : "I"));
        }
        updateMarkerButtonLabels();

        profileButton.setMessage(Text.literal("P" + (clamp(profileIndex, 0, PROFILE_PRESETS.length - 1) + 1)));
        scaleButton.setMessage(Text.literal("Scale: " + SCALE_LABELS[clamp(uiScaleIndex, 0, SCALE_LABELS.length - 1)]));
    }

    private void sendCommand(String command) {
        if (this.client == null || this.client.player == null || this.client.player.networkHandler == null) {
            statusText = "No player/network";
            return;
        }
        BladelowSelectionOverlay.handleCommand(command);
        this.client.player.networkHandler.sendChatCommand(command);
        BladelowHudTelemetry.recordLocalMessage("/" + command);
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
        if (command.startsWith("bladeselect buildh")) {
            return "Queueing selection columns...";
        }
        if (command.startsWith("bladeselect add")) {
            return "Selection updated";
        }
        if (command.startsWith("bladeselect markerbox")) {
            return "Area markers updated";
        }
        if (command.startsWith("bladepause")) {
            return "Build paused";
        }
        if (command.startsWith("bladecontinue")) {
            return "Continuing build...";
        }
        if (command.startsWith("blademove") || command.startsWith("bladesafety") || command.startsWith("bladeprofile")) {
            return "Runtime updated";
        }
        return "Ran: /" + command;
    }

    private void updateRunGuard() {
        validationText = validateForRun();
        if (runButton != null) {
            runButton.active = validationText.isEmpty();
        }
    }

    private String validateForRun() {
        if (MODE_SELECTION.equals(activeMode)) {
            if (markerA == null || markerB == null) {
                return "set markers A/B";
            }
            Integer height = parseInt(heightField.getText());
            if (height == null || height < 1 || height > 256) {
                return "height 1..256";
            }
            if (selectedBlockSpec() == null) {
                return "at least one slot block";
            }
            return "";
        }

        if (MODE_BLUEPRINT.equals(activeMode)) {
            if (markerA == null && effectiveCoords() == null) {
                return "valid XYZ or marker A";
            }
            return "";
        }

        return "";
    }

    private boolean isBlockInAnySlot(String blockId) {
        for (String slot : selectedSlots) {
            if (blockId.equals(slot)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSlotReady(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return false;
        }
        if (this.client == null || this.client.player == null) {
            return false;
        }
        if (this.client.player.getAbilities().creativeMode) {
            return true;
        }

        Identifier id = Identifier.tryParse(blockId);
        if (id == null || !Registries.BLOCK.containsId(id)) {
            return false;
        }

        Item target = Registries.BLOCK.get(id).asItem();
        if (target == Items.AIR) {
            return false;
        }

        PlayerInventory inventory = this.client.player.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && stack.isOf(target)) {
                return true;
            }
        }
        return false;
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

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void trimFront(List<String> list, int maxSize) {
        while (list.size() > maxSize) {
            list.remove(list.size() - 1);
        }
    }

    private void restoreFavorites(String encoded) {
        favoriteBlockIds.clear();
        for (String token : splitPipe(encoded)) {
            if (!token.isBlank()) {
                favoriteBlockIds.add(token);
            }
        }
        trimFront(favoriteBlockIds, MAX_FAVORITES);
        if (favoriteBlockIds.isEmpty()) {
            favoriteBlockIds.add("minecraft:stone");
            favoriteBlockIds.add("minecraft:cobblestone");
        }
    }

    private void restoreRecent(String encoded) {
        recentBlockIds.clear();
        for (String token : splitPipe(encoded)) {
            if (!token.isBlank()) {
                recentBlockIds.addLast(token);
            }
        }
    }

    private static String[] splitPipe(String value) {
        if (value == null || value.isBlank()) {
            return new String[0];
        }
        return value.split("\\|");
    }

    private static String joinPipe(List<String> values) {
        if (values.isEmpty()) {
            return "";
        }
        return String.join("|", values);
    }

    private static String joinSlots(String[] slots) {
        String[] normalized = new String[SLOT_COUNT];
        for (int i = 0; i < SLOT_COUNT; i++) {
            normalized[i] = slots[i] == null ? "" : slots[i];
        }
        return String.join("|", normalized);
    }

    private static void parseSlots(String encoded, String[] target) {
        Arrays.fill(target, null);
        String[] parts = splitPipe(encoded);
        for (int i = 0; i < Math.min(SLOT_COUNT, parts.length); i++) {
            target[i] = parts[i].isBlank() ? null : parts[i];
        }
    }

    private static String encodePos(BlockPos pos) {
        if (pos == null) {
            return "";
        }
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static BlockPos decodePos(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        String[] parts = encoded.split(",");
        if (parts.length != 3) {
            return null;
        }
        try {
            int x = Integer.parseInt(parts[0].trim());
            int y = Integer.parseInt(parts[1].trim());
            int z = Integer.parseInt(parts[2].trim());
            return new BlockPos(x, y, z);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String resolveProfileKey(MinecraftClient client) {
        if (client == null) {
            return "default";
        }
        if (client.getCurrentServerEntry() != null && client.getCurrentServerEntry().address != null) {
            return "mp:" + client.getCurrentServerEntry().address;
        }
        if (client.getServer() != null && client.getServer().getSaveProperties() != null) {
            String levelName = client.getServer().getSaveProperties().getLevelName();
            if (levelName != null && !levelName.isBlank()) {
                return "sp:" + levelName;
            }
        }
        if (client.world != null) {
            return "dim:" + client.world.getRegistryKey().getValue();
        }
        return "default";
    }

    private static String sanitizeProfileKey(String profile) {
        return profile.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static synchronized void ensureHudStoreLoaded() {
        if (hudStoreLoaded) {
            return;
        }
        hudStoreLoaded = true;
        if (!Files.exists(HUD_STATE_PATH)) {
            return;
        }
        try (InputStream in = Files.newInputStream(HUD_STATE_PATH)) {
            HUD_STORE.load(in);
        } catch (IOException ignored) {
        }
    }

    private static synchronized void flushHudStore() {
        ensureHudStoreLoaded();
        try {
            Path parent = HUD_STATE_PATH.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream out = Files.newOutputStream(HUD_STATE_PATH)) {
                HUD_STORE.store(out, "Bladelow HUD state");
            }
        } catch (IOException ignored) {
        }
    }

    private static String readProfileValue(String profile, String key, String defaultValue) {
        ensureHudStoreLoaded();
        return HUD_STORE.getProperty(sanitizeProfileKey(profile) + "." + key, defaultValue);
    }

    private static void writeProfileValue(String profile, String key, String value) {
        ensureHudStoreLoaded();
        HUD_STORE.setProperty(sanitizeProfileKey(profile) + "." + key, value == null ? "" : value);
    }

    private static boolean readProfileBoolean(String profile, String key, boolean defaultValue) {
        return Boolean.parseBoolean(readProfileValue(profile, key, Boolean.toString(defaultValue)));
    }

    private static int readProfileInt(String profile, String key, int defaultValue) {
        try {
            return Integer.parseInt(readProfileValue(profile, key, Integer.toString(defaultValue)));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static double readProfileDouble(String profile, String key, double defaultValue) {
        try {
            return Double.parseDouble(readProfileValue(profile, key, Double.toString(defaultValue)));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private void loadUiStateForProfile(String profile) {
        UiState.mode = readProfileValue(profile, "mode", MODE_SELECTION);
        if ("line".equals(UiState.mode)) {
            UiState.mode = MODE_SELECTION;
        }
        UiState.flow = readProfileValue(profile, "flow", FLOW_AREA);
        UiState.axis = readProfileValue(profile, "axis", "x");
        UiState.manualCoords = readProfileBoolean(profile, "manualCoords", false);

        UiState.x = readProfileValue(profile, "x", "");
        UiState.y = readProfileValue(profile, "y", "");
        UiState.z = readProfileValue(profile, "z", "");
        UiState.count = readProfileValue(profile, "count", "20");
        UiState.height = readProfileValue(profile, "height", "6");

        UiState.blueprint = readProfileValue(profile, "blueprint", "line20");
        UiState.web = readProfileValue(profile, "web", "");
        UiState.limit = readProfileValue(profile, "limit", "12");
        UiState.search = readProfileValue(profile, "search", "");

        UiState.smart = readProfileBoolean(profile, "smart", true);
        UiState.preview = readProfileBoolean(profile, "preview", false);
        UiState.moveMode = readProfileValue(profile, "moveMode", "walk");
        UiState.reach = readProfileDouble(profile, "reach", 4.5);
        UiState.profileIndex = readProfileInt(profile, "profileIndex", 0);

        UiState.pageIndex = readProfileInt(profile, "pageIndex", 0);
        UiState.activeSlot = readProfileInt(profile, "activeSlot", 0);
        parseSlots(readProfileValue(profile, "slots", "||"), UiState.selectedSlots);

        UiState.favorites = readProfileValue(profile, "favorites", "minecraft:stone|minecraft:cobblestone|minecraft:oak_planks|minecraft:glass");
        UiState.recent = readProfileValue(profile, "recent", "");
        UiState.scaleIndex = readProfileInt(profile, "scaleIndex", 1);
        UiState.slotMiniIcons = readProfileBoolean(profile, "slotMiniIcons", true);
        UiState.markerA = readProfileValue(profile, "markerA", "");
        UiState.markerB = readProfileValue(profile, "markerB", "");
    }

    private void saveUiState() {
        UiState.mode = activeMode;
        UiState.flow = normalizeFlow(activeFlow);
        UiState.axis = axis;
        UiState.manualCoords = manualCoords;

        UiState.smart = smartMoveEnabled;
        UiState.preview = previewBeforeBuild;
        UiState.moveMode = moveMode;
        UiState.reach = reachDistance;
        UiState.profileIndex = profileIndex;

        UiState.pageIndex = pageIndex;
        UiState.activeSlot = activeSlot;
        UiState.scaleIndex = uiScaleIndex;
        UiState.slotMiniIcons = slotMiniIconsEnabled;
        UiState.markerA = encodePos(markerA);
        UiState.markerB = encodePos(markerB);

        System.arraycopy(selectedSlots, 0, UiState.selectedSlots, 0, SLOT_COUNT);

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

        UiState.favorites = joinPipe(favoriteBlockIds);
        UiState.recent = joinPipe(new ArrayList<>(recentBlockIds));

        writeProfileValue(profileKey, "mode", UiState.mode);
        writeProfileValue(profileKey, "flow", UiState.flow);
        writeProfileValue(profileKey, "axis", UiState.axis);
        writeProfileValue(profileKey, "manualCoords", Boolean.toString(UiState.manualCoords));

        writeProfileValue(profileKey, "x", UiState.x);
        writeProfileValue(profileKey, "y", UiState.y);
        writeProfileValue(profileKey, "z", UiState.z);
        writeProfileValue(profileKey, "count", UiState.count);
        writeProfileValue(profileKey, "height", UiState.height);

        writeProfileValue(profileKey, "blueprint", UiState.blueprint);
        writeProfileValue(profileKey, "web", UiState.web);
        writeProfileValue(profileKey, "limit", UiState.limit);
        writeProfileValue(profileKey, "search", UiState.search);

        writeProfileValue(profileKey, "smart", Boolean.toString(UiState.smart));
        writeProfileValue(profileKey, "preview", Boolean.toString(UiState.preview));
        writeProfileValue(profileKey, "moveMode", UiState.moveMode);
        writeProfileValue(profileKey, "reach", Double.toString(UiState.reach));
        writeProfileValue(profileKey, "profileIndex", Integer.toString(UiState.profileIndex));

        writeProfileValue(profileKey, "pageIndex", Integer.toString(UiState.pageIndex));
        writeProfileValue(profileKey, "activeSlot", Integer.toString(UiState.activeSlot));
        writeProfileValue(profileKey, "slots", joinSlots(UiState.selectedSlots));

        writeProfileValue(profileKey, "favorites", UiState.favorites);
        writeProfileValue(profileKey, "recent", UiState.recent);
        writeProfileValue(profileKey, "scaleIndex", Integer.toString(UiState.scaleIndex));
        writeProfileValue(profileKey, "slotMiniIcons", Boolean.toString(UiState.slotMiniIcons));
        writeProfileValue(profileKey, "markerA", UiState.markerA);
        writeProfileValue(profileKey, "markerB", UiState.markerB);

        flushHudStore();
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        int keyCode = input.getKeycode();
        if (keyCode == GLFW.GLFW_KEY_P) {
            close();
            return true;
        }

        boolean typing = this.getFocused() instanceof TextFieldWidget;
        if (!typing) {
            if (keyCode == GLFW.GLFW_KEY_R) {
                runActiveMode();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_M) {
                markSelection();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_C) {
                sendCommand("bladepause");
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_V) {
                sendCommand("bladecontinue");
                return true;
            }
        }

        return super.keyPressed(input);
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
