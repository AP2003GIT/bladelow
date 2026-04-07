package com.bladelow.client.ui;

import com.bladelow.client.BladelowHudTelemetry;
import com.bladelow.client.BladelowModelStatus;
import com.bladelow.client.BladelowSelectionOverlay;
import com.bladelow.network.HudAction;
import com.bladelow.network.HudCommandBridge;
import com.bladelow.network.HudCommandPayload;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Main in-game control surface for Bladelow.
 *
 * The HUD acts as a lightweight mission editor: it lets the player define build
 * areas, pick materials, preview city districts, and launch planner/builder
 * actions without falling back to chat commands for every step.
 */
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
    private static final String MODE_CITY = "city";
    private static final String FLOW_AREA = "area";
    private static final String FLOW_BLOCKS = "blocks";
    private static final String FLOW_SOURCE = "source";
    private static final String FLOW_RUN = "run";

    private static final String[] BLUEPRINT_PRESETS = {
        "town_house_small",
        "town_house_tall",
        "town_market_stall",
        "town_civic_hall"
    };
    private static final String[] PROFILE_PRESETS = {"builder", "safe", "fast"};
    private static final String[] SCALE_LABELS = {"S", "M", "L"};
    private static final double[] SCALE_VALUES = {0.90, 1.00, 1.12};
    private static final String[] CITY_LAYOUT_PRESETS = {"medieval", "balanced", "harbor", "adaptive"};
    private static final int PANEL_BASE_WIDTH = 1120;
    private static final int PANEL_BASE_HEIGHT = 640;
    private static final int PANEL_MIN_WIDTH = 920;
    private static final int PANEL_MIN_HEIGHT = 540;
    private static final int MINIMAP_BASE_RADIUS = 72;
    private static final int MINIMAP_MAX_RADIUS = 196;
    private static final int MAX_SUGGESTED_PLOTS = 6;

    private static final Path HUD_STATE_PATH = Path.of("config", "bladelow", "hud-state.properties");
    private static final Properties HUD_STORE = new Properties();
    private static boolean hudStoreLoaded;

    /**
     * Persisted UI state shared across HUD sessions.
     *
     * Keeping it in one place makes it easy to restore the user's last flow,
     * selected slots, scale, and minimap markers after reopening the HUD.
     */
    private static final class UiState {
        private static String mode = MODE_SELECTION;
        private static String axis = "x";
        private static boolean manualCoords = false;

        private static String x = "";
        private static String y = "";
        private static String z = "";
        private static String count = "20";
        private static String height = "6";

        private static String blueprint = "town_house_small";
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
        private static String districtCounts = "";
        private static String citySummary = "";
        private static String cityPreset = "medieval";
        private static boolean cityPaletteOverride = false;
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

    private ButtonWidget flowAreaButton;
    private ButtonWidget flowBlocksButton;
    private ButtonWidget flowSourceButton;
    private ButtonWidget flowRunButton;

    private ButtonWidget modeSelectionButton;
    private ButtonWidget modeBlueprintButton;
    private ButtonWidget modeCityButton;
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
    private ButtonWidget zoneResidentialButton;
    private ButtonWidget zoneMarketButton;
    private ButtonWidget zoneWorkshopButton;
    private ButtonWidget zoneCivicButton;
    private ButtonWidget zoneMixedButton;
    private ButtonWidget zoneListButton;
    private ButtonWidget zoneClearButton;
    private ButtonWidget cityTownListButton;
    private ButtonWidget cityPreviewButton;
    private ButtonWidget cityTownFillButton;
    private ButtonWidget cityPresetButton;
    private ButtonWidget cityAutoZonesButton;
    private ButtonWidget cityAutoBuildButton;
    private ButtonWidget cityRerollPreviewButton;
    private ButtonWidget cityRejectPreviewButton;
    private ButtonWidget citySaveStyleButton;
    private ButtonWidget cityPaletteOverrideButton;
    private ButtonWidget cityAutoCityButton;
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
    private int planningMapX;
    private int planningMapY;
    private int planningMapW;
    private int planningMapH;

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
    private String cityLayoutPreset;
    private double reachDistance;
    private BlockPos markerA;
    private BlockPos markerB;

    private double uiScale = 1.0;

    private String statusText = "Ready";
    private String validationText = "";
    private String hoveredBlockId;
    private boolean suppressFieldCallbacks;
    private final LinkedHashMap<String, Integer> districtCounts = new LinkedHashMap<>();
    private String citySummary = "none";
    private String districtBrush = "";
    private boolean planningDragActive;
    private BlockPos planningDragOrigin;
    private List<SuggestedPlot> suggestedPlots = List.of();
    private int selectedSuggestedPlot = -1;
    private boolean showModelStatusPage;
    private boolean cityPaletteOverrideEnabled;
    private String lastIntentPaletteKey = "";
    private final String[] lastAutoFilledSlots = new String[SLOT_COUNT];

    public BladelowHudScreen() {
        super(Text.literal("Bladelow Builder"));
        ensureBlockCatalog();

        this.profileKey = resolveProfileKey(MinecraftClient.getInstance());
        loadUiStateForProfile(profileKey);

        this.activeMode = normalizeMode(UiState.mode);
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
        restoreDistrictCounts(UiState.districtCounts);
        this.citySummary = UiState.citySummary == null || UiState.citySummary.isBlank() ? "none" : UiState.citySummary;
        this.cityLayoutPreset = normalizeCityPreset(UiState.cityPreset);
        this.cityPaletteOverrideEnabled = UiState.cityPaletteOverride;

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

        int hiddenY = panelY + panelH + sx(12);
        this.addFavoriteButton = addDrawableChild(ButtonWidget.builder(Text.literal("+F"), b -> addFavoriteFromActiveSlot())
            .dimensions(controlX, hiddenY, sx(1), buttonH)
            .build());
        this.removeFavoriteButton = addDrawableChild(ButtonWidget.builder(Text.literal("-F"), b -> removeFavoriteFromActiveSlot())
            .dimensions(controlX, hiddenY, sx(1), buttonH)
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

        this.tileW = (leftW - rowGap * (GRID_COLS - 1)) / GRID_COLS;
        this.tileH = sx(40);
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

        int clearW = sx(18);
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

        int valueFieldW = sx(72);
        int areaBaseY = xyzY + (buttonH + rowGap) * 2;
        int markerW = (rightW - rowGap) / 2;

        this.countField = new TextFieldWidget(this.textRenderer, rightX, hiddenY, valueFieldW, buttonH, Text.literal("Count"));
        this.countField.setText(UiState.count);
        this.countField.setChangedListener(v -> updateRunGuard());
        addDrawableChild(this.countField);

        this.countMinusButton = addDrawableChild(ButtonWidget.builder(Text.literal("-"), b -> stepCount(-1))
            .dimensions(rightX, hiddenY, sx(1), buttonH)
            .build());
        this.countPlusButton = addDrawableChild(ButtonWidget.builder(Text.literal("+"), b -> stepCount(1))
            .dimensions(rightX, hiddenY, sx(1), buttonH)
            .build());

        this.heightField = new TextFieldWidget(this.textRenderer, rightX, areaBaseY + (buttonH + rowGap) * 2, valueFieldW, buttonH, Text.literal("Height"));
        this.heightField.setText(UiState.height);
        this.heightField.setChangedListener(v -> {
            updateRunGuard();
            syncOverlayDraft();
        });
        addDrawableChild(this.heightField);

        int axisBaseX = rightX + valueFieldW + rowGap;
        this.axisXButton = addDrawableChild(ButtonWidget.builder(Text.literal("X"), b -> setAxis("x"))
            .dimensions(axisBaseX, hiddenY, sx(1), buttonH)
            .build());
        this.axisYButton = addDrawableChild(ButtonWidget.builder(Text.literal("Y"), b -> setAxis("y"))
            .dimensions(axisBaseX, hiddenY, sx(1), buttonH)
            .build());
        this.axisZButton = addDrawableChild(ButtonWidget.builder(Text.literal("Z"), b -> setAxis("z"))
            .dimensions(axisBaseX, hiddenY, sx(1), buttonH)
            .build());

        this.markButton = addDrawableChild(ButtonWidget.builder(Text.literal("Mark"), b -> markSelection())
            .dimensions(rightX, hiddenY, sx(1), buttonH)
            .build());

        int actionW = (panelW - sx(16) - rowGap * 3) / 4;
        int actionX = panelX + sx(8);
        this.runButton = addDrawableChild(ButtonWidget.builder(Text.literal("Start Build"), b -> runActiveMode())
            .dimensions(actionX, actionsY, actionW, buttonH)
            .build());
        this.cancelButton = addDrawableChild(ButtonWidget.builder(Text.literal("Stop"), b -> sendAction(HudAction.PAUSE_BUILD))
            .dimensions(actionX + actionW + rowGap, actionsY, actionW, buttonH)
            .build());
        this.confirmButton = addDrawableChild(ButtonWidget.builder(Text.literal("Continue Build"), b -> sendAction(HudAction.CONTINUE_BUILD))
            .dimensions(actionX + (actionW + rowGap) * 2, actionsY, actionW, buttonH)
            .build());
        this.previewModeButton = addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> sendAction(HudAction.CANCEL_BUILD))
            .dimensions(actionX + (actionW + rowGap) * 3, actionsY, actionW, buttonH)
            .build());

        int modeW = (rightW - rowGap * 2) / 3;
        this.modeSelectionButton = addDrawableChild(ButtonWidget.builder(Text.literal("SEL"), b -> setMode(MODE_SELECTION))
            .dimensions(rightX, searchY, modeW, buttonH)
            .build());
        this.modeBlueprintButton = addDrawableChild(ButtonWidget.builder(Text.literal("BP"), b -> setMode(MODE_BLUEPRINT))
            .dimensions(rightX + modeW + rowGap, searchY, modeW, buttonH)
            .build());
        this.modeCityButton = addDrawableChild(ButtonWidget.builder(Text.literal("CITY"), b -> setMode(MODE_CITY))
            .dimensions(rightX + (modeW + rowGap) * 2, searchY, modeW, buttonH)
            .build());

        int rightRowY = searchY + (buttonH + rowGap) * 4;
        this.scaleButton = addDrawableChild(ButtonWidget.builder(Text.literal("Scale"), b -> cycleScale())
            .dimensions(rightX, rightRowY, rightW, buttonH)
            .build());

        rightRowY += buttonH + rowGap;
        this.moveModeButton = addDrawableChild(ButtonWidget.builder(Text.literal("Mode"), b -> toggleMoveMode())
            .dimensions(rightX, rightRowY, rightW, buttonH)
            .build());

        int sourceFieldY = searchY + (buttonH + rowGap) * 2;
        this.bpLoadButton = addDrawableChild(ButtonWidget.builder(Text.literal("Load Blueprint"), b -> loadBlueprint())
            .dimensions(rightX, sourceFieldY + buttonH + rowGap, rightW, buttonH)
            .build());

        int cityHalfW = (rightW - rowGap) / 2;
        int cityY = searchY + buttonH + rowGap;
        this.zoneResidentialButton = addDrawableChild(ButtonWidget.builder(Text.literal("Residential"), b -> setDistrictBrush("residential"))
            .dimensions(rightX, cityY, cityHalfW, buttonH)
            .build());
        this.zoneMarketButton = addDrawableChild(ButtonWidget.builder(Text.literal("Market"), b -> setDistrictBrush("market"))
            .dimensions(rightX + cityHalfW + rowGap, cityY, cityHalfW, buttonH)
            .build());
        cityY += buttonH + rowGap;
        this.zoneWorkshopButton = addDrawableChild(ButtonWidget.builder(Text.literal("Workshop"), b -> setDistrictBrush("workshop"))
            .dimensions(rightX, cityY, cityHalfW, buttonH)
            .build());
        this.zoneCivicButton = addDrawableChild(ButtonWidget.builder(Text.literal("Civic"), b -> setDistrictBrush("civic"))
            .dimensions(rightX + cityHalfW + rowGap, cityY, cityHalfW, buttonH)
            .build());
        cityY += buttonH + rowGap;
        this.zoneMixedButton = addDrawableChild(ButtonWidget.builder(Text.literal("Mixed"), b -> setDistrictBrush("mixed"))
            .dimensions(rightX, cityY, cityHalfW, buttonH)
            .build());
        this.cityPresetButton = addDrawableChild(ButtonWidget.builder(Text.literal("Preset"), b -> cycleCityPreset())
            .dimensions(rightX + cityHalfW + rowGap, cityY, cityHalfW, buttonH)
            .build());
        cityY += buttonH + rowGap;
        this.zoneListButton = addDrawableChild(ButtonWidget.builder(Text.literal("List Districts"), b -> sendAction(HudAction.ZONE_LIST))
            .dimensions(rightX, cityY, cityHalfW, buttonH)
            .build());
        this.zoneClearButton = addDrawableChild(ButtonWidget.builder(Text.literal("Clear Districts"), b -> sendAction(HudAction.ZONE_CLEAR))
            .dimensions(rightX + cityHalfW + rowGap, cityY, cityHalfW, buttonH)
            .build());
        cityY += buttonH + rowGap;
        this.cityTownListButton = addDrawableChild(ButtonWidget.builder(Text.literal("Director Status"), b -> sendAction(HudAction.CITY_AUTOPLAY_STATUS))
            .dimensions(rightX, cityY, cityHalfW, buttonH)
            .build());
        this.cityPreviewButton = addDrawableChild(ButtonWidget.builder(Text.literal("Director Stop"), b -> sendAction(HudAction.CITY_AUTOPLAY_STOP))
            .dimensions(rightX + cityHalfW + rowGap, cityY, cityHalfW, buttonH)
            .build());
        cityY += buttonH + rowGap;
        this.cityTownFillButton = addDrawableChild(ButtonWidget.builder(Text.literal("Director Continue"), b -> sendAction(HudAction.CITY_AUTOPLAY_CONTINUE))
            .dimensions(rightX, cityY, cityHalfW, buttonH)
            .build());
        this.cityAutoZonesButton = addDrawableChild(ButtonWidget.builder(Text.literal("Auto Zones"), b -> runCityAutoZones())
            .dimensions(rightX + cityHalfW + rowGap, cityY, cityHalfW, buttonH)
            .build());
        cityY += buttonH + rowGap;
        this.cityAutoBuildButton = addDrawableChild(ButtonWidget.builder(Text.literal("Auto Build Here"), b -> runCityAutoBuild())
            .dimensions(rightX, cityY, rightW, buttonH)
            .build());
        cityY += buttonH + rowGap;
        this.cityRerollPreviewButton = addDrawableChild(ButtonWidget.builder(Text.literal("Reroll"), b -> runCityRerollPreview())
            .dimensions(rightX, cityY, cityHalfW, buttonH)
            .build());
        this.cityRejectPreviewButton = addDrawableChild(ButtonWidget.builder(Text.literal("Reject"), b -> runCityRejectPreview())
            .dimensions(rightX + cityHalfW + rowGap, cityY, cityHalfW, buttonH)
            .build());
        cityY += buttonH + rowGap;
        this.cityPaletteOverrideButton = addDrawableChild(ButtonWidget.builder(Text.literal("Palette: AUTO"), b -> toggleCityPaletteOverride())
            .dimensions(rightX, cityY, rightW, buttonH)
            .build());
        cityY += buttonH + rowGap;
        this.citySaveStyleButton = addDrawableChild(ButtonWidget.builder(Text.literal("Save Style Area"), b -> saveStyleExampleArea())
            .dimensions(rightX, cityY, cityHalfW, buttonH)
            .build());
        this.cityAutoCityButton = addDrawableChild(ButtonWidget.builder(Text.literal("Director Start"), b -> runCityAutoCity())
            .dimensions(rightX + cityHalfW + rowGap, cityY, cityHalfW, buttonH)
            .build());

        rightRowY += buttonH + rowGap;
        int sideW = (rightW - rowGap) / 2;
        this.presetLineXButton = addDrawableChild(ButtonWidget.builder(Text.literal("Set A"), b -> captureMarkerA())
            .dimensions(rightX, areaBaseY, markerW, buttonH)
            .build());
        this.smartMoveButton = addDrawableChild(ButtonWidget.builder(Text.literal("S"), b -> toggleSmartMove())
            .dimensions(rightX, rightRowY, rightW, buttonH)
            .build());
        this.presetLineZButton = addDrawableChild(ButtonWidget.builder(Text.literal("Set B"), b -> captureMarkerB())
            .dimensions(rightX + markerW + rowGap, areaBaseY, markerW, buttonH)
            .build());

        this.presetSelButton = addDrawableChild(ButtonWidget.builder(Text.literal("Mark Box"), b -> applyMarkerBox())
            .dimensions(rightX, areaBaseY + buttonH + rowGap, markerW, buttonH)
            .build());
        this.profileButton = addDrawableChild(ButtonWidget.builder(Text.literal("P"), b -> cycleProfile())
            .dimensions(rightX, rightRowY + buttonH + rowGap, sideW, buttonH)
            .build());
        this.presetBpButton = addDrawableChild(ButtonWidget.builder(Text.literal("Clr Mk"), b -> clearMarkers())
            .dimensions(rightX + markerW + rowGap, areaBaseY + buttonH + rowGap, markerW, buttonH)
            .build());

        rightRowY += buttonH + rowGap;
        this.bpBuildButton = addDrawableChild(ButtonWidget.builder(Text.literal("Build"), b -> buildBlueprint())
            .dimensions(rightX, hiddenY, sx(1), buttonH)
            .build());
        this.statusDetailButton = addDrawableChild(ButtonWidget.builder(Text.literal("Model"), b -> toggleModelStatusPage())
            .dimensions(rightX + sideW + rowGap, rightRowY + buttonH + rowGap, sideW, buttonH)
            .build());
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

        this.blueprintField = new TextFieldWidget(this.textRenderer, rightX, searchY + buttonH + rowGap, rightW, buttonH, Text.literal("Blueprint"));
        this.blueprintField.setText(UiState.blueprint);
        this.blueprintField.setChangedListener(v -> updateRunGuard());
        addDrawableChild(this.blueprintField);

        this.bpPrevButton = addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> cycleBlueprint(-1))
            .dimensions(rightX, hiddenY, sx(1), buttonH)
            .build());
        this.bpNextButton = addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> cycleBlueprint(1))
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
        refreshSuggestedPlots();
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
        this.panelW = Math.min(this.width - 8, Math.max(sx(PANEL_MIN_WIDTH), Math.min(desiredPanelW, maxPanelW)));
        this.panelH = Math.min(this.height - 8, Math.max(sx(PANEL_MIN_HEIGHT), Math.min(desiredPanelH, maxPanelH)));
        this.panelX = Math.max(0, Math.min(this.width - panelW, this.width / 2 - panelW / 2));
        this.panelY = Math.max(0, Math.min(this.height - panelH, this.height / 2 - panelH / 2));

        this.rowGap = sx(8);
        this.buttonH = sx(24);

        int minRightW = sx(320);
        int maxRightW = Math.max(minRightW, panelW - sx(520));
        int targetRightW = Math.max(minRightW, sx(360));
        this.rightW = Math.min(maxRightW, targetRightW);

        this.leftX = panelX + sx(8);
        this.rightX = panelX + panelW - rightW - sx(8);
        this.leftW = rightX - leftX - sx(12);

        this.flowY = panelY + sx(24);
        this.searchY = flowY + buttonH + rowGap;
        // Favorites/recent rows are intentionally hidden in the simplified layout.
        this.favoriteY = panelY + panelH + sx(48);
        this.recentY = favoriteY + buttonH + rowGap;
        this.gridY = searchY + buttonH + rowGap;
        this.slotsY = gridY + (sx(40) + rowGap) * GRID_ROWS + rowGap * 2;
        this.coordsY = searchY;
        this.valueY = searchY + (buttonH + rowGap) * 4;
        this.actionsY = panelY + panelH - sx(64);
        this.statusY = actionsY + buttonH + rowGap;
        this.planningMapX = leftX;
        this.planningMapY = searchY;
        this.planningMapW = leftW;
        this.planningMapH = Math.max(sx(180), actionsY - searchY - rowGap);
    }

    private void setButtonBounds(ButtonWidget button, int x, int y, int width, int height) {
        if (button == null) {
            return;
        }
        button.setDimensions(width, height);
        button.setPosition(x, y);
    }

    private void layoutFlowButtons() {
        if (flowAreaButton == null || flowBlocksButton == null || flowSourceButton == null || flowRunButton == null) {
            return;
        }

        boolean compactCityFlow = MODE_CITY.equals(activeMode) && !cityPaletteOverrideEnabled;
        int visibleSteps = compactCityFlow ? 3 : 4;
        int flowGap = rowGap;
        int flowButtonW = (panelW - sx(16) - flowGap * (visibleSteps - 1)) / visibleSteps;
        int flowX = panelX + sx(8);

        setButtonBounds(flowAreaButton, flowX, flowY, flowButtonW, buttonH);
        if (compactCityFlow) {
            // Keep the hidden palette button off the active strip so the
            // visible CITY flow reads as a clean 3-step sequence.
            setButtonBounds(flowBlocksButton, panelX + panelW + sx(12), flowY, flowButtonW, buttonH);
            setButtonBounds(flowSourceButton, flowX + flowButtonW + flowGap, flowY, flowButtonW, buttonH);
            setButtonBounds(flowRunButton, flowX + (flowButtonW + flowGap) * 2, flowY, flowButtonW, buttonH);
            return;
        }

        setButtonBounds(flowBlocksButton, flowX + flowButtonW + flowGap, flowY, flowButtonW, buttonH);
        setButtonBounds(flowSourceButton, flowX + (flowButtonW + flowGap) * 2, flowY, flowButtonW, buttonH);
        setButtonBounds(flowRunButton, flowX + (flowButtonW + flowGap) * 3, flowY, flowButtonW, buttonH);
    }

    private void layoutStatusDetailButton(boolean source, boolean cityMode, boolean run) {
        if (statusDetailButton == null) {
            return;
        }

        int sideW = (rightW - rowGap) / 2;
        if (source && cityMode) {
            // In CITY/SOURCE the right panel is already dense, so park the
            // model button just above the build controls instead of letting it
            // overlap the district action rows.
            setButtonBounds(statusDetailButton, rightX, actionsY - buttonH - rowGap, rightW, buttonH);
            return;
        }

        if (run) {
            setButtonBounds(statusDetailButton, rightX + sideW + rowGap, searchY + (buttonH + rowGap) * 6, sideW, buttonH);
        }
    }

    private int sx(int base) {
        return Math.max(1, (int) Math.round(base * uiScale));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        maybeAutofillSlotsFromIntent();
        refreshCityAutoBuildButtonLabel();
        refreshPreviewActionButtons();
        drawPanelBackground(context);
        if (showPlanningMap()) {
            drawPlanningMap(context, mouseX, mouseY);
        }

        super.render(context, mouseX, mouseY, delta);

        hoveredBlockId = null;
        drawBlockGrid(context, mouseX, mouseY);
        drawSlotCards(context);
        drawStatusPanel(context);
        if (showModelStatusPage) {
            drawModelStatusPage(context);
        }

        if (hoveredBlockId != null) {
            drawBlockTooltip(context, mouseX, mouseY, hoveredBlockId);
        }
    }

    private void drawPanelBackground(DrawContext context) {
        int headerY = panelY + sx(22);
        int leftBottom = Math.max(slotsY + buttonH + rowGap, planningMapY + planningMapH + rowGap);
        int rightBottom = actionsY - rowGap;

        context.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xD0111111);
        context.fill(panelX + 1, panelY + 1, panelX + panelW - 1, panelY + sx(20), 0xAA1F2430);
        context.fill(leftX - 2, headerY + rowGap, leftX + leftW + 2, leftBottom, 0x66222A38);
        context.fill(rightX - 2, headerY + rowGap, rightX + rightW + 2, rightBottom, 0x66202935);
        context.fill(panelX + sx(6), actionsY - sx(6), panelX + panelW - sx(6), actionsY + buttonH + sx(6), 0x55242E3B);
        context.fill(panelX + sx(6), statusY, panelX + panelW - sx(6), panelY + panelH - sx(6), 0x55303A4D);
        drawBorder(context, panelX, panelY, panelW, panelH, 0xFFFFFFFF);
        context.drawText(this.textRenderer, Text.literal("BLADELOW BUILDER"), panelX + sx(8), panelY + sx(7), 0xFFF1F5FC, false);
        context.drawText(this.textRenderer, Text.literal(leftPanelLabel()), leftX, headerY - sx(10), 0xFFD1DBEA, false);
        context.drawText(this.textRenderer, Text.literal(rightPanelLabel()), rightX, headerY - sx(10), 0xFFD1DBEA, false);
        context.drawText(this.textRenderer, Text.literal("BUILD CONTROLS"), panelX + sx(8), actionsY - sx(14), 0xFFD1DBEA, false);

    }

    private String leftPanelLabel() {
        if (FLOW_BLOCKS.equals(activeFlow)) {
            return "BLOCK PICKER";
        }
        if (MODE_CITY.equals(activeMode) && FLOW_SOURCE.equals(activeFlow)) {
            return "CITY MAP";
        }
        return "PLANNING MAP";
    }

    private boolean showPlanningMap() {
        return !FLOW_BLOCKS.equals(activeFlow);
    }

    private void drawPlanningMap(DrawContext context, int mouseX, int mouseY) {
        MinimapView view = currentMinimapView();
        if (view == null || this.client == null || this.client.world == null) {
            return;
        }

        int legendY = view.screenY() + view.screenH() + sx(6);
        context.fill(view.screenX(), view.screenY(), view.screenX() + view.screenW(), view.screenY() + view.screenH(), 0xCC18212B);
        drawBorder(context, view.screenX(), view.screenY(), view.screenW(), view.screenH(), 0xFF8AA4C4);

        // Sample the world into a coarse minimap grid. This is not a chunk map;
        // it is a planning aid that favors clarity and speed over exact detail.
        int samplesX = Math.max(24, Math.min(48, view.screenW() / Math.max(2, sx(6))));
        int samplesZ = Math.max(24, Math.min(48, view.screenH() / Math.max(2, sx(6))));
        double cellW = (double) view.screenW() / samplesX;
        double cellH = (double) view.screenH() / samplesZ;
        for (int sampleZ = 0; sampleZ < samplesZ; sampleZ++) {
            for (int sampleX = 0; sampleX < samplesX; sampleX++) {
                int worldX = view.minX() + (int) Math.round((sampleX + 0.5) * (view.maxX() - view.minX()) / Math.max(1.0, samplesX));
                int worldZ = view.minZ() + (int) Math.round((sampleZ + 0.5) * (view.maxZ() - view.minZ()) / Math.max(1.0, samplesZ));
                MinimapCell cell = sampleMinimapCell(this.client.world, worldX, worldZ, view.baseY());
                int x1 = view.screenX() + (int) Math.floor(sampleX * cellW);
                int y1 = view.screenY() + (int) Math.floor(sampleZ * cellH);
                int x2 = view.screenX() + (int) Math.ceil((sampleX + 1) * cellW);
                int y2 = view.screenY() + (int) Math.ceil((sampleZ + 1) * cellH);
                context.fill(x1, y1, x2, y2, cell.color());
            }
        }

        int gridColor = 0x334D5D73;
        context.fill(view.screenX() + view.screenW() / 2, view.screenY(), view.screenX() + view.screenW() / 2 + 1, view.screenY() + view.screenH(), gridColor);
        context.fill(view.screenX(), view.screenY() + view.screenH() / 2, view.screenX() + view.screenW(), view.screenY() + view.screenH() / 2 + 1, gridColor);

        if (this.client.player != null) {
            int playerX = worldToMapX(view, this.client.player.getBlockX());
            int playerY = worldToMapZ(view, this.client.player.getBlockZ());
            context.fill(playerX - sx(2), playerY - sx(2), playerX + sx(2), playerY + sx(2), 0xFFFFDD6A);
        }

        if (markerA != null && markerB != null) {
            int zoneColor = districtBrushColor();
            drawMapSelectionRect(context, view, markerA, markerB, zoneColor);
        }
        drawSuggestedPlots(context, view);
        drawGeneratedPreview(context, view);
        if (markerA != null) {
            drawMapMarker(context, view, markerA, 0xFFF5F5F5);
        }
        if (markerB != null) {
            drawMapMarker(context, view, markerB, 0xFF4CB8FF);
        }

        boolean hovering = isInside(mouseX, mouseY, view.screenX(), view.screenY(), view.screenW(), view.screenH());
        if (hovering) {
            BlockPos hovered = mapScreenToWorld(view, mouseX, mouseY);
            if (hovered != null) {
                MinimapCell hoveredCell = sampleMinimapCell(this.client.world, hovered.getX(), hovered.getZ(), view.baseY());
                int hoverX = worldToMapX(view, hovered.getX());
                int hoverY = worldToMapZ(view, hovered.getZ());
                context.fill(hoverX - 1, hoverY - 1, hoverX + 2, hoverY + 2, 0xFFFFFFFF);
                String hoverLabel = hoveredCell.label() + " | " + hovered.getX() + ", " + hovered.getZ();
                context.drawText(this.textRenderer, Text.literal(hoverLabel), view.screenX() + sx(6), view.screenY() + sx(6), 0xFFF4F7FB, false);
            }
        }

        String title = planningMapTitle();
        String subtitle = planningMapSubtitle();
        context.drawText(this.textRenderer, Text.literal(title), view.screenX(), legendY, 0xFFDCE7F7, false);
        context.drawText(this.textRenderer, Text.literal(this.textRenderer.trimToWidth(subtitle, view.screenW())), view.screenX(), legendY + sx(10), 0xFFB6C5D8, false);
        drawPlanningMapLegend(context, view, legendY + sx(20));
    }

    private String planningMapTitle() {
        if (MODE_CITY.equals(activeMode) && FLOW_SOURCE.equals(activeFlow)) {
            return "City layout map: drag to paint a district area";
        }
        return "Layout map: drag to place the build area";
    }

    private String planningMapSubtitle() {
        String brush = districtBrush.isBlank() ? "-" : districtBrush.toUpperCase(Locale.ROOT);
        BladelowHudTelemetry.PreviewSnapshot preview = matchingPreview();
        if (preview != null) {
            int width = Math.abs(preview.maxX() - preview.minX()) + 1;
            int depth = Math.abs(preview.maxZ() - preview.minZ()) + 1;
            String variantText = preview.variant() > 0 ? " v" + preview.variant() : "";
            return "Preview " + preview.label() + variantText + " " + width + "x" + depth + " | Build, reroll, or reject";
        }
        if (markerA != null && markerB != null) {
            int width = Math.abs(markerA.getX() - markerB.getX()) + 1;
            int depth = Math.abs(markerA.getZ() - markerB.getZ()) + 1;
            return "Area " + width + "x" + depth + " | Brush " + brush + " | Click a suggested plot to snap selection";
        }
        return "Brush " + brush + " | Layout view with roads, water, builds, and terrain";
    }

    /**
     * Sample one minimap column and classify it into a layout-friendly category
     * so the HUD reads like a planning map instead of a raw coordinate grid.
     */
    private MinimapCell sampleMinimapCell(ClientWorld world, int worldX, int worldZ, int referenceY) {
        int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ) - 1;
        if (topY < world.getBottomY()) {
            return new MinimapCell(MapCellType.TERRAIN, 0xFF101720, "void", world.getBottomY());
        }
        BlockPos pos = new BlockPos(worldX, topY, worldZ);
        BlockState state = world.getBlockState(pos);
        String path = Registries.BLOCK.getId(state.getBlock()).getPath();
        MapCellType type = classifyMinimapCell(path, !state.getFluidState().isEmpty(), topY, referenceY);
        int base = colorForSurface(type);
        int shade = clamp((topY - referenceY) * 3, -22, 22);
        return new MinimapCell(type, shadeColor(base, shade), minimapLabel(type), topY);
    }

    private MapCellType classifyMinimapCell(String path, boolean fluid, int topY, int referenceY) {
        String lowered = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (fluid || lowered.contains("water") || lowered.contains("kelp") || lowered.contains("seagrass")) {
            return MapCellType.WATER;
        }
        if (lowered.contains("path") || lowered.contains("gravel")
            || (Math.abs(topY - referenceY) <= 3 && (lowered.contains("road") || lowered.contains("farmland")))) {
            return MapCellType.ROAD;
        }
        if (lowered.contains("leaf") || lowered.contains("vine") || lowered.contains("crop")
            || lowered.contains("grass") || lowered.contains("moss") || lowered.contains("bush")
            || lowered.contains("flower") || lowered.contains("sapling")) {
            return MapCellType.VEGETATION;
        }
        if (looksLikeStructureSurface(lowered)) {
            return MapCellType.BUILDING;
        }
        if (lowered.contains("sand") || lowered.contains("clay") || lowered.contains("dirt") || lowered.contains("mud")) {
            return MapCellType.OPEN_GROUND;
        }
        return MapCellType.TERRAIN;
    }

    private boolean looksLikeStructureSurface(String path) {
        return path.contains("planks")
            || path.contains("brick")
            || path.contains("stone_bricks")
            || path.contains("cobble")
            || path.contains("quartz")
            || path.contains("glass")
            || path.contains("terracotta")
            || path.contains("concrete")
            || path.contains("wool")
            || path.contains("log")
            || path.contains("wood")
            || path.contains("fence")
            || path.contains("wall")
            || path.contains("door")
            || path.contains("trapdoor")
            || path.contains("stairs")
            || path.contains("slab")
            || path.contains("copper")
            || path.contains("deepslate_tiles")
            || path.contains("polished")
            || path.contains("cut_");
    }

    private int colorForSurface(MapCellType type) {
        return switch (type) {
            case WATER -> 0xFF2E5F93;
            case ROAD -> 0xFFB88D5E;
            case BUILDING -> 0xFF8A6B61;
            case VEGETATION -> 0xFF4B7C48;
            case OPEN_GROUND -> 0xFFA18B67;
            case TERRAIN -> 0xFF727A84;
        };
    }

    private String minimapLabel(MapCellType type) {
        return switch (type) {
            case WATER -> "Water";
            case ROAD -> "Road";
            case BUILDING -> "Structure";
            case VEGETATION -> "Vegetation";
            case OPEN_GROUND -> "Open ground";
            case TERRAIN -> "Terrain";
        };
    }

    private void drawPlanningMapLegend(DrawContext context, MinimapView view, int y) {
        int x = view.screenX();
        x = drawLegendSwatch(context, x, y, colorForSurface(MapCellType.WATER), "Water");
        x = drawLegendSwatch(context, x, y, colorForSurface(MapCellType.ROAD), "Road");
        x = drawLegendSwatch(context, x, y, colorForSurface(MapCellType.BUILDING), "Build");
        x = drawLegendSwatch(context, x, y, colorForSurface(MapCellType.VEGETATION), "Green");
        drawLegendSwatch(context, x, y, colorForSurface(MapCellType.OPEN_GROUND), "Open");
    }

    private int drawLegendSwatch(DrawContext context, int x, int y, int color, String label) {
        int size = sx(7);
        context.fill(x, y + sx(2), x + size, y + sx(2) + size, color);
        drawBorder(context, x, y + sx(2), size, size, 0xFFCED7E4);
        context.drawText(this.textRenderer, Text.literal(label), x + size + sx(4), y, 0xFFB6C5D8, false);
        return x + size + sx(4) + this.textRenderer.getWidth(label) + sx(12);
    }

    private void drawSuggestedPlots(DrawContext context, MinimapView view) {
        if (suggestedPlots.isEmpty()) {
            return;
        }
        for (int i = 0; i < suggestedPlots.size(); i++) {
            SuggestedPlot plot = suggestedPlots.get(i);
            int color = i == selectedSuggestedPlot ? 0xFF7DFFA0 : 0xFFC9E17A;
            int fillColor = i == selectedSuggestedPlot ? 0x337DFFA0 : 0x22C9E17A;
            int left = Math.min(worldToMapX(view, plot.minX()), worldToMapX(view, plot.maxX()));
            int right = Math.max(worldToMapX(view, plot.minX()), worldToMapX(view, plot.maxX()));
            int top = Math.min(worldToMapZ(view, plot.minZ()), worldToMapZ(view, plot.maxZ()));
            int bottom = Math.max(worldToMapZ(view, plot.minZ()), worldToMapZ(view, plot.maxZ()));
            context.fill(left, top, right + 1, bottom + 1, fillColor);
            drawBorder(context, left, top, Math.max(1, right - left + 1), Math.max(1, bottom - top + 1), color);
            drawSuggestedPlotLabel(context, plot, left, top, right, bottom, i == selectedSuggestedPlot);
        }
    }

    private void drawSuggestedPlotLabel(DrawContext context, SuggestedPlot plot, int left, int top, int right, int bottom, boolean selected) {
        String label = plot.label();
        if (label == null || label.isBlank()) {
            return;
        }

        int width = Math.max(1, right - left + 1);
        int height = Math.max(1, bottom - top + 1);
        int textWidth = this.textRenderer.getWidth(label);
        if (width < textWidth + sx(8) || height < this.textRenderer.fontHeight + sx(6)) {
            return;
        }

        int boxW = textWidth + sx(6);
        int boxH = this.textRenderer.fontHeight + sx(4);
        int boxX = left + (width - boxW) / 2;
        int boxY = top + (height - boxH) / 2;
        context.fill(boxX, boxY, boxX + boxW, boxY + boxH, selected ? 0xCC243A2A : 0xB8222A20);
        drawBorder(context, boxX, boxY, boxW, boxH, selected ? 0xFF91F9B0 : 0xFFD7E989);
        context.drawText(this.textRenderer, Text.literal(label), boxX + sx(3), boxY + sx(2), 0xFFF2F7E7, false);
    }

    private void drawGeneratedPreview(DrawContext context, MinimapView view) {
        BladelowHudTelemetry.PreviewSnapshot preview = matchingPreview();
        if (preview == null) {
            return;
        }
        int left = Math.min(worldToMapX(view, preview.minX()), worldToMapX(view, preview.maxX()));
        int right = Math.max(worldToMapX(view, preview.minX()), worldToMapX(view, preview.maxX()));
        int top = Math.min(worldToMapZ(view, preview.minZ()), worldToMapZ(view, preview.maxZ()));
        int bottom = Math.max(worldToMapZ(view, preview.minZ()), worldToMapZ(view, preview.maxZ()));

        context.fill(left, top, right + 1, bottom + 1, 0x3344B8FF);
        drawBorder(context, left, top, Math.max(1, right - left + 1), Math.max(1, bottom - top + 1), 0xFF67D6FF);

        int doorX = worldToMapX(view, preview.doorX());
        int doorY = worldToMapZ(view, preview.doorZ());
        context.fill(doorX - sx(2), doorY - sx(2), doorX + sx(2), doorY + sx(2), 0xFFFFC86A);

        String label = preview.summary() == null || preview.summary().isBlank()
            ? "Preview: " + preview.label()
            : "Preview: " + preview.summary();
        int boxW = Math.min(view.screenW() - sx(8), this.textRenderer.getWidth(label) + sx(8));
        int boxX = left + sx(2);
        int boxY = Math.max(view.screenY() + sx(2), top + sx(2));
        context.fill(boxX, boxY, boxX + boxW, boxY + this.textRenderer.fontHeight + sx(4), 0xC0182734);
        drawBorder(context, boxX, boxY, boxW, this.textRenderer.fontHeight + sx(4), 0xFF67D6FF);
        context.drawText(this.textRenderer, Text.literal(this.textRenderer.trimToWidth(label, boxW - sx(6))), boxX + sx(3), boxY + sx(2), 0xFFE7F8FF, false);
    }

    private int shadeColor(int argb, int amount) {
        int a = (argb >>> 24) & 0xFF;
        int r = clamp(((argb >>> 16) & 0xFF) + amount, 0, 255);
        int g = clamp(((argb >>> 8) & 0xFF) + amount, 0, 255);
        int b = clamp((argb & 0xFF) + amount, 0, 255);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private void drawMapSelectionRect(DrawContext context, MinimapView view, BlockPos a, BlockPos b, int color) {
        int left = Math.min(worldToMapX(view, a.getX()), worldToMapX(view, b.getX()));
        int right = Math.max(worldToMapX(view, a.getX()), worldToMapX(view, b.getX()));
        int top = Math.min(worldToMapZ(view, a.getZ()), worldToMapZ(view, b.getZ()));
        int bottom = Math.max(worldToMapZ(view, a.getZ()), worldToMapZ(view, b.getZ()));
        context.fill(left, top, right + 1, bottom + 1, (color & 0x00FFFFFF) | 0x33000000);
        drawBorder(context, left, top, Math.max(1, right - left + 1), Math.max(1, bottom - top + 1), color);
    }

    private void drawMapMarker(DrawContext context, MinimapView view, BlockPos pos, int color) {
        int px = worldToMapX(view, pos.getX());
        int py = worldToMapZ(view, pos.getZ());
        context.fill(px - sx(2), py - sx(2), px + sx(2), py + sx(2), color);
    }

    private int worldToMapX(MinimapView view, int worldX) {
        double t = (worldX - view.minX()) / (double) Math.max(1, view.maxX() - view.minX());
        t = Math.max(0.0, Math.min(1.0, t));
        return view.screenX() + (int) Math.round(t * (view.screenW() - 1));
    }

    private int worldToMapZ(MinimapView view, int worldZ) {
        double t = (worldZ - view.minZ()) / (double) Math.max(1, view.maxZ() - view.minZ());
        t = Math.max(0.0, Math.min(1.0, t));
        return view.screenY() + (int) Math.round(t * (view.screenH() - 1));
    }

    private BlockPos mapScreenToWorld(MinimapView view, double mouseX, double mouseY) {
        if (!isInside(mouseX, mouseY, view.screenX(), view.screenY(), view.screenW(), view.screenH())) {
            return null;
        }
        double tx = (mouseX - view.screenX()) / Math.max(1.0, view.screenW() - 1.0);
        double tz = (mouseY - view.screenY()) / Math.max(1.0, view.screenH() - 1.0);
        int worldX = view.minX() + (int) Math.round(tx * (view.maxX() - view.minX()));
        int worldZ = view.minZ() + (int) Math.round(tz * (view.maxZ() - view.minZ()));
        return new BlockPos(worldX, selectionBaseY(), worldZ);
    }

    private int selectionBaseY() {
        if (markerA != null) {
            return markerA.getY();
        }
        Coords coords = effectiveCoords();
        if (coords != null) {
            return coords.y;
        }
        if (this.client != null && this.client.player != null) {
            return this.client.player.getBlockY();
        }
        return 64;
    }

    private MinimapView currentMinimapView() {
        if (planningMapW <= 0 || planningMapH <= 0) {
            return null;
        }

        int centerX;
        int centerZ;
        if (markerA != null && markerB != null) {
            centerX = (markerA.getX() + markerB.getX()) / 2;
            centerZ = (markerA.getZ() + markerB.getZ()) / 2;
        } else if (markerA != null) {
            centerX = markerA.getX();
            centerZ = markerA.getZ();
        } else if (this.client != null && this.client.player != null) {
            centerX = this.client.player.getBlockX();
            centerZ = this.client.player.getBlockZ();
        } else {
            centerX = 0;
            centerZ = 0;
        }

        int radius = MINIMAP_BASE_RADIUS;
        if (markerA != null && markerB != null) {
            radius = Math.max(radius, Math.max(Math.abs(markerA.getX() - markerB.getX()), Math.abs(markerA.getZ() - markerB.getZ())) / 2 + 12);
        }
        radius = Math.min(MINIMAP_MAX_RADIUS, radius);
        int mapX = planningMapX + sx(8);
        int mapY = planningMapY + sx(8);
        int mapW = Math.max(sx(120), planningMapW - sx(16));
        // Reserve enough height for the title, subtitle, and legend so the
        // map footer stays aligned instead of drifting into the build controls.
        int mapH = Math.max(sx(120), planningMapH - sx(48));
        return new MinimapView(mapX, mapY, mapW, mapH, centerX - radius, centerX + radius, centerZ - radius, centerZ + radius, selectionBaseY());
    }

    private int districtBrushColor() {
        return switch (districtBrush) {
            case "residential" -> 0xFFE0A15B;
            case "market" -> 0xFF57B7FF;
            case "workshop" -> 0xFFC377FF;
            case "civic" -> 0xFF5DDA7A;
            case "mixed" -> 0xFFE8DE72;
            default -> 0xFFFF6A6A;
        };
    }

    private boolean isInside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private boolean isShiftPressed() {
        if (this.client == null || this.client.getWindow() == null) {
            return false;
        }
        long handle = this.client.getWindow().getHandle();
        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
            || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
    }

    private record MinimapView(int screenX, int screenY, int screenW, int screenH, int minX, int maxX, int minZ, int maxZ, int baseY) {
    }

    private enum MapCellType {
        WATER,
        ROAD,
        BUILDING,
        VEGETATION,
        OPEN_GROUND,
        TERRAIN
    }

    private record MinimapCell(MapCellType type, int color, String label, int topY) {
    }

    private record PlotEvaluation(boolean accepted, double score, String label) {
        private static PlotEvaluation reject() {
            return new PlotEvaluation(false, Double.NEGATIVE_INFINITY, "");
        }
    }

    private record SuggestedPlot(int minX, int minZ, int maxX, int maxZ, double score, String label) {
        private boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        private boolean overlaps(SuggestedPlot other, int padding) {
            return minX - padding <= other.maxX
                && maxX + padding >= other.minX
                && minZ - padding <= other.maxZ
                && maxZ + padding >= other.minZ;
        }
    }

    private record IntentPalette(String archetype, String paletteProfile, String signature) {
    }

    private String rightPanelLabel() {
        return switch (activeFlow) {
            case FLOW_AREA -> "AREA SETUP";
            case FLOW_BLOCKS -> MODE_CITY.equals(activeMode) ? "PALETTE OVERRIDE" : "BLOCK PICKER";
            case FLOW_SOURCE -> MODE_CITY.equals(activeMode) ? "CITY PLANNER" : "SOURCE";
            case FLOW_RUN -> MODE_CITY.equals(activeMode) ? "CITY RUNNER" : "RUNTIME";
            default -> "SETUP";
        };
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
        String role = slotRoleName(slotIndex);
        if (blockId == null || blockId.isBlank()) {
            if (MODE_CITY.equals(activeMode) && !cityPaletteOverrideEnabled) {
                return role + ": auto";
            }
            return role + ": empty";
        }
        return role + ": " + shortBlockName(blockId, 8);
    }

    private String slotRoleName(int slotIndex) {
        return switch (slotIndex) {
            case 0 -> "Primary";
            case 1 -> "Trim";
            case 2 -> "Roof";
            default -> "Slot " + (slotIndex + 1);
        };
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
        boolean cityStatus = MODE_CITY.equals(activeMode);
        int barH = cityStatus ? sx(76) : sx(24);

        boolean hasValidation = !validationText.isEmpty();
        boolean isWarning = hasValidation || statusLooksError(statusText);
        int bgColor = isWarning ? 0xAA311C1C : 0xAA16202C;
        int borderColor = isWarning ? 0xFFBE6C6C : 0xFF5D6E88;
        int textColor = isWarning ? 0xFFFFCECE : 0xFFD7E6FA;

        context.fill(barX, barY, barX + barW, barY + barH, bgColor);
        drawBorder(context, barX, barY, barW, barH, borderColor);

        String primary = hasValidation ? "Need: " + validationText : statusText;
        String secondary = flowProgressText() + " | " + modeHintText();
        String clippedPrimary = this.textRenderer.trimToWidth(primary, barW - sx(10));
        String clippedSecondary = this.textRenderer.trimToWidth(secondary, barW - sx(10));
        context.drawText(this.textRenderer, Text.literal(clippedPrimary), barX + sx(4), barY + sx(3), textColor, false);
        context.drawText(this.textRenderer, Text.literal(clippedSecondary), barX + sx(4), barY + sx(13), 0xFFB7C7DF, false);
        if (cityStatus) {
            drawIntentCard(context, barX + sx(4), barY + sx(23), barW - sx(8), sx(48));
        }
    }

    private void drawModelStatusPage(DrawContext context) {
        BladelowModelStatus.Snapshot snapshot = BladelowModelStatus.snapshot();
        int width = Math.min(panelW - sx(64), sx(560));
        int height = Math.min(panelH - sx(80), sx(170));
        int x = panelX + (panelW - width) / 2;
        int y = panelY + sx(44);

        context.fill(x, y, x + width, y + height, 0xEA121923);
        drawBorder(context, x, y, width, height, 0xFF7FA2CF);
        context.fill(x, y, x + width, y + sx(20), 0xCC223246);
        context.drawText(this.textRenderer, Text.literal("MODEL STATUS"), x + sx(8), y + sx(6), 0xFFF2F6FC, false);

        int lineY = y + sx(30);
        for (String line : snapshot.lines()) {
            String clipped = this.textRenderer.trimToWidth(line, width - sx(16));
            context.drawText(this.textRenderer, Text.literal(clipped), x + sx(8), lineY, 0xFFD8E6F8, false);
            lineY += sx(14);
            if (lineY > y + height - sx(18)) {
                break;
            }
        }
    }

    private void drawIntentCard(DrawContext context, int x, int y, int width, int height) {
        context.fill(x, y, x + width, y + height, 0x6633472B);
        drawBorder(context, x, y, width, height, 0xFF6D8B73);

        String[] intentParts = intentCardLines();
        String intentLine = this.textRenderer.trimToWidth("Intent: " + intentParts[0], width - sx(8));
        String contextLine = this.textRenderer.trimToWidth("Context: " + intentParts[1], width - sx(8));
        String plotLine = this.textRenderer.trimToWidth("Plot: " + selectedPlotSummary(), width - sx(8));
        String paletteLine = this.textRenderer.trimToWidth("Palette: " + paletteStatusLine(), width - sx(8));

        context.drawText(this.textRenderer, Text.literal(intentLine), x + sx(4), y + sx(3), 0xFFE0F2E2, false);
        context.drawText(this.textRenderer, Text.literal(contextLine), x + sx(4), y + sx(13), 0xFFC7DFC9, false);
        context.drawText(this.textRenderer, Text.literal(plotLine), x + sx(4), y + sx(23), 0xFFAED2B4, false);
        context.drawText(this.textRenderer, Text.literal(paletteLine), x + sx(4), y + sx(33), 0xFF9FD0AB, false);
    }

    private String[] intentCardLines() {
        String latestIntent = BladelowHudTelemetry.latestIntent();
        if (latestIntent == null || latestIntent.isBlank()) {
            return new String[]{"waiting for scan", "select or snap a plot in CITY mode"};
        }
        String[] parts = latestIntent.split("\\s+\\|\\s+", 2);
        String summary = parts.length > 0 && !parts[0].isBlank() ? parts[0] : latestIntent;
        String context = parts.length > 1 && !parts[1].isBlank() ? parts[1] : "no planner context";
        return new String[]{summary, context};
    }

    private String paletteStatusLine() {
        if (!MODE_CITY.equals(activeMode)) {
            return selectedBlockSpec() == null ? "manual slots empty" : "manual slots set";
        }
        if (!cityPaletteOverrideEnabled) {
            String joined = String.join(" / ",
                shortBlockName(selectedSlots[0], 10),
                shortBlockName(selectedSlots[1], 10),
                shortBlockName(selectedSlots[2], 10)
            );
            if ("- / - / -".equals(joined)) {
                return "auto from intent";
            }
            return "auto from intent -> " + joined;
        }
        return "override -> "
            + shortBlockName(selectedSlots[0], 10) + " / "
            + shortBlockName(selectedSlots[1], 10) + " / "
            + shortBlockName(selectedSlots[2], 10);
    }

    private String selectedPlotSummary() {
        SuggestedPlot plot = preferredSuggestedPlot();
        if (plot == null) {
            if (markerA != null && markerB != null) {
                int width = Math.abs(markerA.getX() - markerB.getX()) + 1;
                int depth = Math.abs(markerA.getZ() - markerB.getZ()) + 1;
                return width + "x" + depth + " manual";
            }
            return "none";
        }
        int width = Math.abs(plot.maxX() - plot.minX()) + 1;
        int depth = Math.abs(plot.maxZ() - plot.minZ()) + 1;
        return plot.label() + " " + width + "x" + depth + " score=" + String.format(Locale.ROOT, "%.1f", plot.score());
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
            case FLOW_AREA -> MODE_CITY.equals(activeMode) ? "City Area" : "Area";
            case FLOW_BLOCKS -> MODE_CITY.equals(activeMode) ? "Palette Override" : "Blocks";
            case FLOW_SOURCE -> MODE_CITY.equals(activeMode) ? "City Source" : "Source";
            case FLOW_RUN -> MODE_CITY.equals(activeMode) ? "City Run" : "Run";
            default -> "Ready";
        };
    }

    private void setFlow(String flow) {
        String normalized = normalizeFlow(flow);
        if (MODE_CITY.equals(activeMode) && !cityPaletteOverrideEnabled && FLOW_BLOCKS.equals(normalized)) {
            normalized = FLOW_SOURCE;
        }
        this.activeFlow = normalized;
        updateFlowUi();
        updateRunGuard();
        statusText = "Step " + flowStep(activeFlow) + "/" + totalFlowSteps() + ": " + flowTitle(activeFlow);
    }

    private String normalizeFlow(String flow) {
        if (FLOW_BLOCKS.equals(flow) || FLOW_SOURCE.equals(flow) || FLOW_RUN.equals(flow)) {
            return flow;
        }
        return FLOW_AREA;
    }

    private String normalizeMode(String mode) {
        if (MODE_BLUEPRINT.equals(mode) || MODE_CITY.equals(mode)) {
            return mode;
        }
        return MODE_SELECTION;
    }

    private void updateFlowUi() {
        if (MODE_CITY.equals(activeMode) && !cityPaletteOverrideEnabled && FLOW_BLOCKS.equals(activeFlow)) {
            activeFlow = FLOW_SOURCE;
        }
        boolean area = FLOW_AREA.equals(activeFlow);
        boolean blocks = FLOW_BLOCKS.equals(activeFlow);
        boolean source = FLOW_SOURCE.equals(activeFlow);
        boolean run = FLOW_RUN.equals(activeFlow);
        boolean blueprintMode = MODE_BLUEPRINT.equals(activeMode);
        boolean cityMode = MODE_CITY.equals(activeMode);

        setVisible(searchField, blocks);
        setVisible(pagePrevButton, blocks);
        setVisible(pageNextButton, blocks);
        setVisible(addFavoriteButton, false);
        setVisible(removeFavoriteButton, false);
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
        setVisible(markButton, false);
        setVisible(presetLineXButton, area);
        setVisible(presetLineZButton, area);
        setVisible(presetSelButton, area);
        setVisible(presetBpButton, area);
        setVisible(countField, false);
        setVisible(countMinusButton, false);
        setVisible(countPlusButton, false);
        setVisible(axisXButton, false);
        setVisible(axisYButton, false);
        setVisible(axisZButton, false);

        setVisible(modeSelectionButton, true);
        setVisible(modeBlueprintButton, true);
        setVisible(modeCityButton, true);
        setVisible(flowAreaButton, true);
        setVisible(flowBlocksButton, !cityMode || cityPaletteOverrideEnabled);
        setVisible(flowSourceButton, true);
        setVisible(flowRunButton, true);

        boolean blueprintSource = source && blueprintMode;
        setVisible(blueprintField, blueprintSource);
        setVisible(bpLoadButton, blueprintSource);
        setVisible(bpPrevButton, false);
        setVisible(bpNextButton, false);
        setVisible(zoneResidentialButton, source && cityMode);
        setVisible(zoneMarketButton, source && cityMode);
        setVisible(zoneWorkshopButton, source && cityMode);
        setVisible(zoneCivicButton, source && cityMode);
        setVisible(zoneMixedButton, source && cityMode);
        setVisible(cityPresetButton, source && cityMode);
        setVisible(zoneListButton, source && cityMode);
        setVisible(zoneClearButton, source && cityMode);
        setVisible(cityTownListButton, source && cityMode);
        setVisible(cityPreviewButton, source && cityMode);
        setVisible(cityTownFillButton, source && cityMode);
        setVisible(cityAutoZonesButton, source && cityMode);
        setVisible(cityAutoBuildButton, source && cityMode);
        setVisible(cityRerollPreviewButton, source && cityMode);
        setVisible(cityRejectPreviewButton, source && cityMode);
        setVisible(cityPaletteOverrideButton, source && cityMode);
        setVisible(citySaveStyleButton, source && cityMode);
        setVisible(cityAutoCityButton, source && cityMode);

        setVisible(runButton, run);
        setVisible(cancelButton, run);
        setVisible(confirmButton, run);
        setVisible(previewModeButton, run);
        setVisible(moveModeButton, run);
        setVisible(smartMoveButton, run);
        setVisible(profileButton, run);
        setVisible(statusDetailButton, run || (source && cityMode));
        setVisible(bpBuildButton, run && blueprintMode);
        setVisible(scaleButton, run);

        setVisible(reachMinusButton, false);
        setVisible(reachButton, false);
        setVisible(reachPlusButton, false);

        layoutFlowButtons();
        layoutStatusDetailButton(source, cityMode, run);
        updateFlowButtons();
        updateBlockButtons();
        updateSlotButtons();
        refreshPreviewActionButtons();
    }

    private void updateFlowButtons() {
        if (flowAreaButton == null) {
            return;
        }
        flowAreaButton.setMessage(Text.literal(FLOW_AREA.equals(activeFlow) ? "AREA*" : "AREA"));
        String blocksLabel = MODE_CITY.equals(activeMode) ? "PALETTE" : "BLOCKS";
        flowBlocksButton.setMessage(Text.literal(FLOW_BLOCKS.equals(activeFlow) ? blocksLabel + "*" : blocksLabel));
        flowSourceButton.setMessage(Text.literal(FLOW_SOURCE.equals(activeFlow) ? "SOURCE*" : "SOURCE"));
        flowRunButton.setMessage(Text.literal(FLOW_RUN.equals(activeFlow) ? "RUN*" : "RUN"));
    }

    private void refreshPreviewActionButtons() {
        boolean citySource = MODE_CITY.equals(activeMode) && FLOW_SOURCE.equals(activeFlow);
        boolean previewReady = hasMatchingPreview();
        if (cityRerollPreviewButton != null) {
            cityRerollPreviewButton.visible = citySource;
            cityRerollPreviewButton.active = citySource && previewReady;
            cityRerollPreviewButton.setMessage(Text.literal("Reroll"));
        }
        if (cityRejectPreviewButton != null) {
            cityRejectPreviewButton.visible = citySource;
            cityRejectPreviewButton.active = citySource && previewReady;
            cityRejectPreviewButton.setMessage(Text.literal("Reject"));
        }
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
        this.activeMode = normalizeMode(mode);
        if (MODE_CITY.equals(this.activeMode) && !cityPaletteOverrideEnabled && FLOW_BLOCKS.equals(activeFlow)) {
            this.activeFlow = FLOW_SOURCE;
        }
        updateModeUi();
        updateRunGuard();
        statusText = "Mode: " + this.activeMode.toUpperCase(Locale.ROOT);
    }

    private void updateModeUi() {
        runButton.setMessage(Text.literal("Start Build"));
        cancelButton.setMessage(Text.literal("Pause"));
        confirmButton.setMessage(Text.literal("Resume"));
        previewModeButton.setMessage(Text.literal("Stop Build"));

        updateModeButtons();
        updateMarkerButtonLabels();
        updateFlowUi();
    }

    private void updateModeButtons() {
        modeSelectionButton.setMessage(Text.literal(MODE_SELECTION.equals(activeMode) ? "SELECTION*" : "SELECTION"));
        modeBlueprintButton.setMessage(Text.literal(MODE_BLUEPRINT.equals(activeMode) ? "BLUEPRINT*" : "BLUEPRINT"));
        modeCityButton.setMessage(Text.literal(MODE_CITY.equals(activeMode) ? "CITY*" : "CITY"));
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
        statusText = "Editing " + slotRoleName(activeSlot);
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
        statusText = "Cleared " + slotRoleName(idx);
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
        statusText = slotRoleName(assigned) + " <- " + shortBlockName(blockId, 16);
    }

    private void maybeAutofillSlotsFromIntent() {
        if (!MODE_CITY.equals(activeMode)) {
            return;
        }

        IntentPalette palette = intentPaletteFromTelemetry();
        if (palette == null || palette.signature().isBlank() || "none".equals(palette.signature())) {
            return;
        }
        if (palette.signature().equals(lastIntentPaletteKey)) {
            return;
        }

        boolean allEmpty = true;
        for (String slot : selectedSlots) {
            if (slot != null && !slot.isBlank()) {
                allEmpty = false;
                break;
            }
        }
        boolean stillAutoFilled = Arrays.equals(selectedSlots, lastAutoFilledSlots);
        if (!allEmpty && !stillAutoFilled) {
            return;
        }

        String[] mapped = mapIntentPaletteToSlots(palette);
        boolean changed = false;
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (!Objects.equals(selectedSlots[i], mapped[i])) {
                selectedSlots[i] = mapped[i];
                changed = true;
            }
        }
        System.arraycopy(selectedSlots, 0, lastAutoFilledSlots, 0, SLOT_COUNT);
        lastIntentPaletteKey = palette.signature();
        if (!changed) {
            return;
        }

        addRecent(selectedSlots[0]);
        addRecent(selectedSlots[1]);
        addRecent(selectedSlots[2]);
        updateSlotButtons();
        updateQuickButtons();
        updateRunGuard();
        statusText = "Auto palette from inferred intent";
    }

    private IntentPalette intentPaletteFromTelemetry() {
        String latestIntent = BladelowHudTelemetry.latestIntent();
        if (latestIntent == null || latestIntent.isBlank()) {
            return null;
        }
        String[] parts = latestIntent.split("\\s+\\|\\s+", 2);
        String summary = parts.length > 0 ? parts[0].trim().toLowerCase(Locale.ROOT) : latestIntent.trim().toLowerCase(Locale.ROOT);
        if (summary.isBlank() || "none".equals(summary)) {
            return null;
        }

        String[] tokens = summary.split("\\s+");
        if (tokens.length == 0) {
            return null;
        }

        String archetype = tokens[0];
        String paletteProfile = "";
        for (String token : tokens) {
            if (token.endsWith("f") || token.startsWith("conf=") || token.startsWith("mem=")) {
                continue;
            }
            if ("small".equals(token) || "medium".equals(token) || "large".equals(token)) {
                continue;
            }
            if (token.contains("_")
                || "stone".equals(token)
                || "oak".equals(token)
                || "market".equals(token)
                || "plaster".equals(token)) {
                paletteProfile = token;
            }
        }

        return new IntentPalette(archetype, paletteProfile, summary);
    }

    private String[] mapIntentPaletteToSlots(IntentPalette palette) {
        String archetype = palette.archetype();
        List<String> paletteParts = new ArrayList<>();
        if (!palette.paletteProfile().isBlank()) {
            paletteParts.addAll(Arrays.asList(palette.paletteProfile().split("_")));
        }

        String slot1 = primaryBlockForPalette(paletteParts, archetype);
        String slot2 = trimBlockForPalette(paletteParts, archetype, slot1);
        String slot3 = roofBlockForPalette(paletteParts, archetype, slot1, slot2);
        return new String[]{slot1, slot2, slot3};
    }

    private String primaryBlockForPalette(List<String> paletteParts, String archetype) {
        if (paletteParts.contains("plaster")) {
            return "minecraft:calcite";
        }
        if (paletteParts.contains("stone")) {
            return "minecraft:stone_bricks";
        }
        if (paletteParts.contains("market")) {
            return "minecraft:terracotta";
        }
        if (paletteParts.contains("oak")) {
            return "minecraft:oak_planks";
        }
        return switch (archetype) {
            case "civic" -> "minecraft:stone_bricks";
            case "market" -> "minecraft:terracotta";
            case "workshop" -> "minecraft:cobblestone";
            default -> "minecraft:oak_planks";
        };
    }

    private String trimBlockForPalette(List<String> paletteParts, String archetype, String primary) {
        if (paletteParts.contains("oak") && !"minecraft:oak_planks".equals(primary)) {
            return "minecraft:oak_planks";
        }
        if (paletteParts.contains("stone") && !"minecraft:stone_bricks".equals(primary)) {
            return "minecraft:stone_bricks";
        }
        if (paletteParts.contains("plaster") && !"minecraft:calcite".equals(primary)) {
            return "minecraft:calcite";
        }
        if (paletteParts.contains("market") && !"minecraft:terracotta".equals(primary)) {
            return "minecraft:terracotta";
        }
        return switch (archetype) {
            case "civic" -> "minecraft:calcite";
            case "market" -> "minecraft:oak_planks";
            case "workshop" -> "minecraft:oak_planks";
            default -> "minecraft:cobblestone";
        };
    }

    private String roofBlockForPalette(List<String> paletteParts, String archetype, String primary, String trim) {
        if (paletteParts.contains("market")) {
            return "minecraft:copper_block";
        }
        if (paletteParts.contains("oak")) {
            return "minecraft:spruce_planks";
        }
        if (paletteParts.contains("stone")) {
            return "minecraft:deepslate_tiles";
        }
        if (paletteParts.contains("plaster")) {
            return "minecraft:gray_concrete";
        }
        String candidate = switch (archetype) {
            case "civic" -> "minecraft:deepslate_tiles";
            case "market" -> "minecraft:terracotta";
            case "workshop" -> "minecraft:deepslate_tiles";
            default -> "minecraft:spruce_planks";
        };
        if (candidate.equals(primary) || candidate.equals(trim)) {
            return "minecraft:gray_concrete";
        }
        return candidate;
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
        if (blockId == null || blockId.isBlank()) {
            return "-";
        }
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
            case MODE_CITY -> runCityBuild();
            default -> statusText = "Unknown mode";
        }
    }

    private void runCityBuild() {
        if (!ensureMarkerSelection()) {
            return;
        }
        citySummary = "fill requested";
        sendAction(HudAction.TOWN_FILL_SELECTION);
    }

    private void runCityPreview() {
        if (!ensureMarkerSelection()) {
            return;
        }
        citySummary = "preview requested";
        sendAction(HudAction.TOWN_PREVIEW_SELECTION);
    }

    private void runCityAutoZones() {
        if (!ensureMarkerSelection()) {
            return;
        }
        citySummary = "auto zones";
        sendAction(HudAction.ZONE_AUTO_LAYOUT, cityLayoutPreset);
    }

    private void runCityAutoCity() {
        if (!ensureMarkerSelection()) {
            return;
        }
        citySummary = "director start";
        sendAction(HudAction.CITY_AUTOPLAY_START, cityLayoutPreset);
    }

    private void runCityAutoBuild() {
        SuggestedPlot plot = preferredSuggestedPlot();
        if (plot != null && !selectionMatchesPlot(plot)) {
            snapToSuggestedPlot(plot);
        } else if (markerA == null || markerB == null) {
            statusText = "Select an area or suggested plot first";
            return;
        }
        String label = plot == null || plot.label() == null || plot.label().isBlank() ? "auto" : plot.label();
        if (hasMatchingPreview()) {
            citySummary = "build preview commit";
            sendAction(HudAction.CITY_BUILD_COMMIT);
            return;
        }
        citySummary = plot == null ? "preview generating" : "preview " + plot.label();
        sendAction(
            HudAction.CITY_BUILD_PREVIEW,
            label,
            slotOverrideArg(0),
            slotOverrideArg(1),
            slotOverrideArg(2)
        );
    }

    private void runCityRerollPreview() {
        if (!ensureMarkerSelection()) {
            return;
        }
        SuggestedPlot plot = preferredSuggestedPlot();
        String label = plot == null || plot.label() == null || plot.label().isBlank() ? "auto" : plot.label();
        citySummary = "preview reroll";
        sendAction(
            HudAction.CITY_BUILD_REROLL,
            label,
            slotOverrideArg(0),
            slotOverrideArg(1),
            slotOverrideArg(2)
        );
    }

    private void runCityRejectPreview() {
        if (!hasMatchingPreview()) {
            statusText = "No preview to reject";
            return;
        }
        citySummary = "preview rejected";
        sendAction(HudAction.CITY_BUILD_REJECT);
    }

    private void saveStyleExampleArea() {
        if (!ensureMarkerSelection()) {
            return;
        }
        String label = styleExampleLabel();
        citySummary = "style example " + label;
        sendAction(HudAction.MODEL_SAVE_STYLE_EXAMPLE, label);
    }

    private String styleExampleLabel() {
        if (!districtBrush.isBlank()) {
            return districtBrush;
        }
        SuggestedPlot plot = preferredSuggestedPlot();
        if (plot != null && plot.label() != null && !plot.label().isBlank()) {
            return plot.label();
        }
        IntentPalette palette = intentPaletteFromTelemetry();
        if (palette != null && !palette.archetype().isBlank()) {
            return palette.archetype();
        }
        return MODE_CITY.equals(activeMode) ? "city" : "example";
    }

    private String slotOverrideArg(int index) {
        if (MODE_CITY.equals(activeMode) && !cityPaletteOverrideEnabled) {
            return "-";
        }
        if (index < 0 || index >= selectedSlots.length) {
            return "-";
        }
        String slot = selectedSlots[index];
        return slot == null || slot.isBlank() ? "-" : slot;
    }

    private void toggleCityPaletteOverride() {
        cityPaletteOverrideEnabled = !cityPaletteOverrideEnabled;
        if (!cityPaletteOverrideEnabled && MODE_CITY.equals(activeMode) && FLOW_BLOCKS.equals(activeFlow)) {
            activeFlow = FLOW_SOURCE;
        }
        if (!cityPaletteOverrideEnabled) {
            maybeAutofillSlotsFromIntent();
        }
        updateFlowUi();
        refreshButtonLabels();
        updateRunGuard();
        statusText = cityPaletteOverrideEnabled
            ? "Palette override enabled"
            : "Palette override disabled; using inferred intent palette";
    }

    private void toggleModelStatusPage() {
        showModelStatusPage = !showModelStatusPage;
        refreshButtonLabels();
        statusText = showModelStatusPage ? "Opened model status page" : "Closed model status page";
    }

    private void cycleCityPreset() {
        int idx = 0;
        for (int i = 0; i < CITY_LAYOUT_PRESETS.length; i++) {
            if (CITY_LAYOUT_PRESETS[i].equals(cityLayoutPreset)) {
                idx = i;
                break;
            }
        }
        cityLayoutPreset = CITY_LAYOUT_PRESETS[(idx + 1) % CITY_LAYOUT_PRESETS.length];
        if (cityPresetButton != null) {
            cityPresetButton.setMessage(Text.literal("Preset: " + cityLayoutPreset.toUpperCase(Locale.ROOT)));
        }
        citySummary = "preset=" + cityLayoutPreset;
    }

    private String normalizeCityPreset(String value) {
        if (value == null) {
            return CITY_LAYOUT_PRESETS[0];
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (String preset : CITY_LAYOUT_PRESETS) {
            if (preset.equals(normalized)) {
                return preset;
            }
        }
        return CITY_LAYOUT_PRESETS[0];
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
        sendAction(HudAction.SELECTION_MARKER_BOX,
            Integer.toString(markerA.getX()),
            Integer.toString(markerA.getY()),
            Integer.toString(markerA.getZ()),
            Integer.toString(markerB.getX()),
            Integer.toString(markerB.getY()),
            Integer.toString(markerB.getZ()),
            Integer.toString(height),
            "solid"
        );
        sendAction(HudAction.SELECTION_BUILD_HEIGHT, Integer.toString(height), blockSpec);
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
        sendAction(HudAction.SELECTION_MARKER_BOX,
            Integer.toString(markerA.getX()),
            Integer.toString(markerA.getY()),
            Integer.toString(markerA.getZ()),
            Integer.toString(markerB.getX()),
            Integer.toString(markerB.getY()),
            Integer.toString(markerB.getZ()),
            Integer.toString(height),
            "solid"
        );
        refreshSuggestedPlots();
        if (MODE_CITY.equals(activeMode)) {
            citySummary = "intent scan";
            sendAction(HudAction.MODEL_SCAN_INTENT);
        }
        BladelowSelectionOverlay.setMarkers(markerA, markerB, height);
    }

    private boolean ensureMarkerSelection() {
        if (markerA == null || markerB == null) {
            statusText = "Set marker A and marker B first";
            return false;
        }
        Integer height = parseInt(heightField == null ? null : heightField.getText());
        if (height == null || height < 1 || height > 256) {
            statusText = "Height must be 1..256";
            return false;
        }
        applyMarkerBox();
        return true;
    }

    private void saveZoneFromMarkers(String type) {
        if (!ensureMarkerSelection()) {
            return;
        }
        bumpDistrictCount(type);
        citySummary = "district saved";
        sendAction(HudAction.ZONE_SET, type);
    }

    private void setDistrictBrush(String type) {
        String normalized = normalizeDistrictType(type);
        if (normalized.isBlank()) {
            return;
        }
        districtBrush = normalized.equals(districtBrush) ? "" : normalized;
        updateDistrictBrushButtons();
        citySummary = districtBrush.isBlank() ? "brush cleared" : "brush=" + districtBrush;
        statusText = districtBrush.isBlank()
            ? "District brush cleared"
            : "District brush: " + districtBrush.toUpperCase(Locale.ROOT) + " | drag on the city map";
    }

    private void clearMarkers() {
        markerA = null;
        markerB = null;
        suggestedPlots = List.of();
        selectedSuggestedPlot = -1;
        updateMarkerButtonLabels();
        BladelowSelectionOverlay.clear();
        sendAction(HudAction.SELECTION_CLEAR);
        statusText = "Markers cleared";
    }

    private void bumpDistrictCount(String type) {
        String normalized = normalizeDistrictType(type);
        if (normalized.isBlank()) {
            return;
        }
        districtCounts.put(normalized, districtCounts.getOrDefault(normalized, 0) + 1);
    }

    private void clearDistrictCounts() {
        districtCounts.clear();
        districtCounts.put("residential", 0);
        districtCounts.put("market", 0);
        districtCounts.put("workshop", 0);
        districtCounts.put("civic", 0);
        districtCounts.put("mixed", 0);
    }

    private String districtSummaryText() {
        return "Districts R:" + districtCounts.getOrDefault("residential", 0)
            + " M:" + districtCounts.getOrDefault("market", 0)
            + " W:" + districtCounts.getOrDefault("workshop", 0)
            + " C:" + districtCounts.getOrDefault("civic", 0)
            + " X:" + districtCounts.getOrDefault("mixed", 0);
    }

    private String normalizeDistrictType(String raw) {
        if (raw == null) {
            return "";
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "residential", "market", "workshop", "civic", "mixed" -> raw.trim().toLowerCase(Locale.ROOT);
            default -> "";
        };
    }

    private void syncOverlayDraft() {
        Integer height = parseInt(heightField == null ? null : heightField.getText());
        BladelowSelectionOverlay.setDraftMarkers(markerA, markerB, height == null ? 1 : height);
    }

    /**
     * Refresh the locally inferred plot list inside the selected area.
     *
     * This runs entirely on the client HUD using the visible world state so the
     * player gets immediate build suggestions before handing off to the server
     * planner.
     */
    private void refreshSuggestedPlots() {
        if (this.client == null || this.client.world == null || markerA == null || markerB == null) {
            suggestedPlots = List.of();
            selectedSuggestedPlot = -1;
            return;
        }

        int minX = Math.min(markerA.getX(), markerB.getX());
        int maxX = Math.max(markerA.getX(), markerB.getX());
        int minZ = Math.min(markerA.getZ(), markerB.getZ());
        int maxZ = Math.max(markerA.getZ(), markerB.getZ());
        if (maxX - minX + 1 < 7 || maxZ - minZ + 1 < 7) {
            suggestedPlots = List.of();
            selectedSuggestedPlot = -1;
            return;
        }

        ClientWorld world = this.client.world;
        int[][] sizePresets = {
            {7, 7},
            {9, 7},
            {9, 9},
            {11, 9},
            {11, 11},
            {13, 11}
        };
        List<SuggestedPlot> candidates = new ArrayList<>();
        for (int[] size : sizePresets) {
            int plotW = size[0];
            int plotD = size[1];
            if (plotW > maxX - minX + 1 || plotD > maxZ - minZ + 1) {
                continue;
            }
            for (int startX = minX; startX + plotW - 1 <= maxX; startX += 2) {
                for (int startZ = minZ; startZ + plotD - 1 <= maxZ; startZ += 2) {
                    PlotEvaluation evaluation = evaluateSuggestedPlot(world, startX, startZ, plotW, plotD, selectionBaseY(), minX, maxX, minZ, maxZ);
                    if (!evaluation.accepted()) {
                        continue;
                    }
                    candidates.add(new SuggestedPlot(
                        startX,
                        startZ,
                        startX + plotW - 1,
                        startZ + plotD - 1,
                        evaluation.score(),
                        evaluation.label()
                    ));
                }
            }
        }

        candidates.sort(Comparator.comparingDouble(SuggestedPlot::score).reversed());
        List<SuggestedPlot> chosen = new ArrayList<>();
        for (SuggestedPlot candidate : candidates) {
            boolean overlaps = chosen.stream().anyMatch(existing -> existing.overlaps(candidate, 1));
            if (overlaps) {
                continue;
            }
            chosen.add(candidate);
            if (chosen.size() >= MAX_SUGGESTED_PLOTS) {
                break;
            }
        }
        suggestedPlots = List.copyOf(chosen);
        if (suggestedPlots.isEmpty()) {
            selectedSuggestedPlot = -1;
        } else {
            selectedSuggestedPlot = clamp(selectedSuggestedPlot, 0, suggestedPlots.size() - 1);
        }
    }

    private PlotEvaluation evaluateSuggestedPlot(
        ClientWorld world,
        int startX,
        int startZ,
        int plotW,
        int plotD,
        int referenceY,
        int areaMinX,
        int areaMaxX,
        int areaMinZ,
        int areaMaxZ
    ) {
        int blocked = 0;
        int roadInside = 0;
        int open = 0;
        int vegetation = 0;
        int terrain = 0;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int area = plotW * plotD;

        for (int x = startX; x < startX + plotW; x++) {
            for (int z = startZ; z < startZ + plotD; z++) {
                MinimapCell cell = sampleMinimapCell(world, x, z, referenceY);
                minY = Math.min(minY, cell.topY());
                maxY = Math.max(maxY, cell.topY());
                switch (cell.type()) {
                    case WATER, BUILDING -> blocked++;
                    case ROAD -> roadInside++;
                    case VEGETATION -> vegetation++;
                    case OPEN_GROUND -> open++;
                    case TERRAIN -> terrain++;
                }
            }
        }

        if (blocked > 0) {
            return PlotEvaluation.reject();
        }
        if (maxY - minY > 4) {
            return PlotEvaluation.reject();
        }
        if (roadInside > Math.max(1, area / 10)) {
            return PlotEvaluation.reject();
        }

        int roadAdjacent = 0;
        int roadChecks = 0;
        for (int x = startX - 1; x <= startX + plotW; x++) {
            if (x >= areaMinX && x <= areaMaxX) {
                if (startZ - 1 >= areaMinZ) {
                    roadChecks++;
                    if (sampleMinimapCell(world, x, startZ - 1, referenceY).type() == MapCellType.ROAD) {
                        roadAdjacent++;
                    }
                }
                if (startZ + plotD <= areaMaxZ) {
                    roadChecks++;
                    if (sampleMinimapCell(world, x, startZ + plotD, referenceY).type() == MapCellType.ROAD) {
                        roadAdjacent++;
                    }
                }
            }
        }
        for (int z = startZ; z < startZ + plotD; z++) {
            if (startX - 1 >= areaMinX) {
                roadChecks++;
                if (sampleMinimapCell(world, startX - 1, z, referenceY).type() == MapCellType.ROAD) {
                    roadAdjacent++;
                }
            }
            if (startX + plotW <= areaMaxX) {
                roadChecks++;
                if (sampleMinimapCell(world, startX + plotW, z, referenceY).type() == MapCellType.ROAD) {
                    roadAdjacent++;
                }
            }
        }

        double openness = (open + terrain * 0.85 + vegetation * 0.55) / Math.max(1.0, area);
        if (openness < 0.72) {
            return PlotEvaluation.reject();
        }
        double roadScore = roadChecks <= 0 ? 0.0 : roadAdjacent / (double) roadChecks;
        double centerX = (markerA.getX() + markerB.getX()) / 2.0;
        double centerZ = (markerA.getZ() + markerB.getZ()) / 2.0;
        double plotCenterX = startX + plotW / 2.0;
        double plotCenterZ = startZ + plotD / 2.0;
        double maxDistance = Math.max(1.0, Math.hypot((areaMaxX - areaMinX) / 2.0, (areaMaxZ - areaMinZ) / 2.0));
        double centerScore = 1.0 - Math.min(1.0, Math.hypot(plotCenterX - centerX, plotCenterZ - centerZ) / maxDistance);
        double score = openness * 5.0
            + roadScore * 4.5
            + centerScore * 1.5
            - (maxY - minY) * 0.6
            - roadInside * 0.15;
        return new PlotEvaluation(true, score, classifySuggestedPlotLabel(plotW, plotD, roadScore, centerScore));
    }

    private String classifySuggestedPlotLabel(int plotW, int plotD, double roadScore, double centerScore) {
        int area = plotW * plotD;
        if (area >= 100 && centerScore >= 0.35) {
            return "civic";
        }
        if (roadScore >= 0.18 && area <= 99) {
            return "shop";
        }
        return "house";
    }

    private SuggestedPlot suggestedPlotAt(BlockPos pos) {
        if (pos == null) {
            return null;
        }
        for (SuggestedPlot plot : suggestedPlots) {
            if (plot.contains(pos.getX(), pos.getZ())) {
                return plot;
            }
        }
        return null;
    }

    private SuggestedPlot preferredSuggestedPlot() {
        if (suggestedPlots.isEmpty()) {
            return null;
        }
        if (selectedSuggestedPlot >= 0 && selectedSuggestedPlot < suggestedPlots.size()) {
            return suggestedPlots.get(selectedSuggestedPlot);
        }
        return suggestedPlots.get(0);
    }

    private boolean selectionMatchesPlot(SuggestedPlot plot) {
        if (plot == null || markerA == null || markerB == null) {
            return false;
        }
        int minX = Math.min(markerA.getX(), markerB.getX());
        int maxX = Math.max(markerA.getX(), markerB.getX());
        int minZ = Math.min(markerA.getZ(), markerB.getZ());
        int maxZ = Math.max(markerA.getZ(), markerB.getZ());
        return minX == plot.minX()
            && maxX == plot.maxX()
            && minZ == plot.minZ()
            && maxZ == plot.maxZ();
    }

    private BladelowHudTelemetry.PreviewSnapshot matchingPreview() {
        if (markerA == null || markerB == null) {
            return null;
        }
        BladelowHudTelemetry.PreviewSnapshot preview = BladelowHudTelemetry.latestPreview();
        if (preview == null) {
            return null;
        }
        int minX = Math.min(markerA.getX(), markerB.getX());
        int maxX = Math.max(markerA.getX(), markerB.getX());
        int minZ = Math.min(markerA.getZ(), markerB.getZ());
        int maxZ = Math.max(markerA.getZ(), markerB.getZ());
        return preview.matchesSelection(minX, minZ, maxX, maxZ) ? preview : null;
    }

    private boolean hasMatchingPreview() {
        return matchingPreview() != null;
    }

    private void snapToSuggestedPlot(SuggestedPlot plot) {
        if (plot == null) {
            return;
        }
        markerA = new BlockPos(plot.minX(), selectionBaseY(), plot.minZ());
        markerB = new BlockPos(plot.maxX(), selectionBaseY(), plot.maxZ());
        selectedSuggestedPlot = suggestedPlots.indexOf(plot);
        updateMarkerButtonLabels();
        syncOverlayDraft();
        updateRunGuard();
        applyMarkerBox();
        statusText = "Snapped to suggested plot " + plot.label();
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
            presetSelButton.setMessage(Text.literal("Apply Area"));
        }
        if (presetBpButton != null) {
            presetBpButton.setMessage(Text.literal("Clear Area"));
        }
    }

    private void updateDistrictBrushButtons() {
        if (zoneResidentialButton != null) {
            zoneResidentialButton.setMessage(Text.literal("residential".equals(districtBrush) ? "Residential*" : "Residential"));
        }
        if (zoneMarketButton != null) {
            zoneMarketButton.setMessage(Text.literal("market".equals(districtBrush) ? "Market*" : "Market"));
        }
        if (zoneWorkshopButton != null) {
            zoneWorkshopButton.setMessage(Text.literal("workshop".equals(districtBrush) ? "Workshop*" : "Workshop"));
        }
        if (zoneCivicButton != null) {
            zoneCivicButton.setMessage(Text.literal("civic".equals(districtBrush) ? "Civic*" : "Civic"));
        }
        if (zoneMixedButton != null) {
            zoneMixedButton.setMessage(Text.literal("mixed".equals(districtBrush) ? "Mixed*" : "Mixed"));
        }
    }

    private void toggleSmartMove() {
        smartMoveEnabled = !smartMoveEnabled;
        smartMoveButton.setMessage(Text.literal(smartMoveEnabled ? "S*" : "S"));
        sendAction(smartMoveEnabled ? HudAction.MOVE_SMART_ENABLE : HudAction.MOVE_SMART_DISABLE);
    }

    private void toggleMoveMode() {
        moveMode = switch (moveMode) {
            case "walk" -> "auto";
            case "auto" -> "teleport";
            default -> "walk";
        };
        moveModeButton.setMessage(Text.literal("Mode: " + moveMode.toUpperCase(Locale.ROOT)));
        sendAction(HudAction.MOVE_SET_MODE, moveMode);
    }

    private void togglePreviewMode() {
        previewBeforeBuild = !previewBeforeBuild;
        previewModeButton.setMessage(Text.literal(previewBeforeBuild ? "Prev:ON" : "Prev:OFF"));
        sendAction(HudAction.SAFETY_SET_PREVIEW, previewBeforeBuild ? "on" : "off");
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
        sendAction(HudAction.MOVE_SET_REACH, String.format(Locale.ROOT, "%.2f", reachDistance));
    }

    private void cycleProfile() {
        profileIndex = (profileIndex + 1) % PROFILE_PRESETS.length;
        String profile = PROFILE_PRESETS[profileIndex];
        profileButton.setMessage(Text.literal("P" + (profileIndex + 1)));
        sendAction(HudAction.PROFILE_LOAD, profile);
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
        sendAction(HudAction.BLUEPRINT_LOAD, name);
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
            dispatchAction(blockSpec == null
                ? HudCommandPayload.of(HudAction.BLUEPRINT_BUILD,
                    Integer.toString(c.x),
                    Integer.toString(c.y),
                    Integer.toString(c.z))
                : HudCommandPayload.of(HudAction.BLUEPRINT_BUILD,
                    Integer.toString(c.x),
                    Integer.toString(c.y),
                    Integer.toString(c.z),
                    blockSpec));
            return;
        }

        dispatchAction(blockSpec == null
            ? HudCommandPayload.of(HudAction.BLUEPRINT_BUILD,
                name,
                Integer.toString(c.x),
                Integer.toString(c.y),
                Integer.toString(c.z))
            : HudCommandPayload.of(HudAction.BLUEPRINT_BUILD,
                name,
                Integer.toString(c.x),
                Integer.toString(c.y),
                Integer.toString(c.z),
                blockSpec));
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
        previewModeButton.setMessage(Text.literal("Stop Build"));
        confirmButton.setMessage(Text.literal("Resume"));
        cancelButton.setMessage(Text.literal("Pause"));
        runButton.setMessage(Text.literal(MODE_CITY.equals(activeMode) ? "Run City Build" : "Start Build"));
        moveModeButton.setMessage(Text.literal("Move Mode: " + moveMode.toUpperCase(Locale.ROOT)));
        smartMoveButton.setMessage(Text.literal(smartMoveEnabled ? "Smart: ON" : "Smart: OFF"));
        reachButton.setMessage(Text.literal("Reach " + String.format(Locale.ROOT, "%.2f", reachDistance)));
        coordsModeButton.setMessage(Text.literal(manualCoords ? "Coords: MANUAL" : "Coords: AUTO"));
        if (slotMiniButton != null) {
            slotMiniButton.setMessage(Text.literal(slotMiniIconsEnabled ? "I*" : "I"));
        }
        updateMarkerButtonLabels();

        profileButton.setMessage(Text.literal("Profile: " + PROFILE_PRESETS[clamp(profileIndex, 0, PROFILE_PRESETS.length - 1)]));
        scaleButton.setMessage(Text.literal("HUD Scale: " + SCALE_LABELS[clamp(uiScaleIndex, 0, SCALE_LABELS.length - 1)]));
        statusDetailButton.setMessage(Text.literal(showModelStatusPage ? "Hide Model" : "Model"));
        if (cityTownFillButton != null) {
            cityTownFillButton.setMessage(Text.literal("Director Continue"));
        }
        if (cityPreviewButton != null) {
            cityPreviewButton.setMessage(Text.literal("Director Stop"));
        }
        if (cityPresetButton != null) {
            cityPresetButton.setMessage(Text.literal("Preset: " + cityLayoutPreset.toUpperCase(Locale.ROOT)));
        }
        if (cityAutoZonesButton != null) {
            cityAutoZonesButton.setMessage(Text.literal("Auto Zones"));
        }
        refreshCityAutoBuildButtonLabel();
        if (cityPaletteOverrideButton != null) {
            cityPaletteOverrideButton.setMessage(Text.literal(cityPaletteOverrideEnabled ? "Palette: OVERRIDE" : "Palette: AUTO"));
        }
        if (cityRerollPreviewButton != null) {
            cityRerollPreviewButton.setMessage(Text.literal("Reroll"));
        }
        if (cityRejectPreviewButton != null) {
            cityRejectPreviewButton.setMessage(Text.literal("Reject"));
        }
        if (citySaveStyleButton != null) {
            citySaveStyleButton.setMessage(Text.literal("Save Style Area"));
        }
        if (cityAutoCityButton != null) {
            cityAutoCityButton.setMessage(Text.literal("Director Start"));
        }
        if (cityTownListButton != null) {
            cityTownListButton.setMessage(Text.literal("Director Status"));
        }
        updateDistrictBrushButtons();
    }

    private void refreshCityAutoBuildButtonLabel() {
        if (cityAutoBuildButton == null) {
            return;
        }
        cityAutoBuildButton.setMessage(Text.literal(hasMatchingPreview() ? "Build Preview" : "Preview Here"));
    }

    private void sendAction(HudAction action) {
        dispatchAction(HudCommandPayload.of(action));
    }

    private void sendAction(HudAction action, String... args) {
        dispatchAction(HudCommandPayload.of(action, args));
    }

    private void dispatchAction(HudCommandPayload payload) {
        if (this.client == null || this.client.player == null || this.client.player.networkHandler == null) {
            statusText = "No player/network";
            return;
        }
        BladelowSelectionOverlay.applyHudAction(payload);
        if (!HudCommandBridge.sendClientPayload(payload)) {
            BladelowHudTelemetry.recordLocalMessage("[bridge-unavailable] " + payload.describe());
            statusText = "Bladelow HUD bridge unavailable";
            return;
        }
        BladelowHudTelemetry.recordLocalMessage("[hud] " + payload.describe());
        statusText = actionStatus(payload);
    }

    private String actionStatus(HudCommandPayload payload) {
        return switch (payload.action()) {
            case BLUEPRINT_LOAD -> "Loading blueprint...";
            case BLUEPRINT_BUILD -> "Queueing blueprint...";
            case CITY_BUILD_PREVIEW -> {
                citySummary = "preview requested";
                yield "Generating build preview for the selected plot...";
            }
            case CITY_BUILD_REROLL -> {
                citySummary = "preview rerolled";
                yield "Generating another preview variant for the selected plot...";
            }
            case CITY_BUILD_REJECT -> {
                citySummary = "preview rejected";
                yield "Rejecting the cached preview...";
            }
            case CITY_BUILD_COMMIT -> {
                citySummary = "preview committed";
                yield "Queueing the cached preview build...";
            }
            case CITY_BUILD_AUTO -> {
                citySummary = "auto build queued";
                yield "Generating one building for the selected plot...";
            }
            case CITY_AUTOPLAY_START -> {
                    citySummary = "director queued";
                yield "Starting city director autoplay...";
            }
            case CITY_AUTOPLAY_STATUS -> "Checking city director status...";
            case CITY_AUTOPLAY_STOP -> {
                    citySummary = "director paused";
                yield "Pausing city director...";
            }
            case CITY_AUTOPLAY_CONTINUE -> {
                    citySummary = "director resumed";
                yield "Resuming city director...";
            }
            case CITY_AUTOPLAY_CANCEL -> {
                    citySummary = "director canceled";
                yield "Canceling city director...";
            }
            case TOWN_FILL_SELECTION -> {
                citySummary = "fill queued";
                yield "Queueing town layout...";
            }
            case TOWN_PREVIEW_SELECTION -> {
                citySummary = "preview requested";
                yield "Planning city preview...";
            }
            case SELECTION_BUILD_HEIGHT -> "Queueing selection columns...";
            case SELECTION_MARKER_BOX -> "Area markers updated";
            case SELECTION_CLEAR -> "Markers cleared";
            case ZONE_SET -> {
                citySummary = "district saved";
                yield "Saving district type from current area...";
            }
            case ZONE_AUTO_LAYOUT -> {
                citySummary = "districts auto-zoned";
                yield "Generating district layout...";
            }
            case ZONE_LIST -> "Listing districts...";
            case ZONE_CLEAR -> {
                clearDistrictCounts();
                citySummary = "districts cleared";
                yield "Clearing saved districts...";
            }
            case MODEL_SCAN_INTENT -> {
                citySummary = "intent scan";
                yield "Analyzing selected build intent...";
            }
            case MODEL_SAVE_STYLE_EXAMPLE -> {
                citySummary = "style example saved";
                yield "Saving selected area as a style example...";
            }
            case STATUS, STATUS_DETAIL -> "Checking build status...";
            case PAUSE_BUILD -> "Build paused";
            case CONTINUE_BUILD -> "Continuing build...";
            case MOVE_SMART_ENABLE, MOVE_SMART_DISABLE, MOVE_SET_MODE, MOVE_SET_REACH,
                 SAFETY_SET_PREVIEW, PROFILE_LOAD -> "Runtime updated";
            default -> "Ran: " + payload.describe();
        };
    }

    private void updateRunGuard() {
        validationText = validateForRun();
        if (runButton != null) {
            runButton.active = validationText.isEmpty();
        }
        if (validationText.isEmpty() && FLOW_RUN.equals(activeFlow)) {
            statusText = "Run ready";
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

        if (MODE_CITY.equals(activeMode)) {
            if (markerA == null || markerB == null) {
                return "set district markers";
            }
            Integer height = parseInt(heightField.getText());
            if (height == null || height < 1 || height > 256) {
                return "height 1..256";
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

    private int flowStep(String flow) {
        boolean compactCityFlow = MODE_CITY.equals(activeMode) && !cityPaletteOverrideEnabled;
        return switch (normalizeFlow(flow)) {
            case FLOW_AREA -> 1;
            case FLOW_BLOCKS -> 2;
            case FLOW_SOURCE -> compactCityFlow ? 2 : 3;
            case FLOW_RUN -> compactCityFlow ? 3 : 4;
            default -> 1;
        };
    }

    private int totalFlowSteps() {
        return MODE_CITY.equals(activeMode) && !cityPaletteOverrideEnabled ? 3 : 4;
    }

    private String flowTitle(String flow) {
        return switch (normalizeFlow(flow)) {
            case FLOW_AREA -> "AREA";
            case FLOW_BLOCKS -> MODE_CITY.equals(activeMode) ? "PALETTE" : "BLOCKS";
            case FLOW_SOURCE -> "SOURCE";
            case FLOW_RUN -> "RUN";
            default -> "AREA";
        };
    }

    private String flowProgressText() {
        String area = (markerA != null && markerB != null) ? "A:OK" : "A:--";
        String blocks;
        if (MODE_CITY.equals(activeMode) && !cityPaletteOverrideEnabled) {
            blocks = "P:AUTO";
        } else {
            boolean blocksReady = MODE_CITY.equals(activeMode) || selectedBlockSpec() != null;
            blocks = blocksReady ? "P:OK" : "P:--";
        }
        boolean sourceReady = !MODE_BLUEPRINT.equals(activeMode)
            || (blueprintField != null && !blueprintField.getText().trim().isEmpty());
        String source = sourceReady ? "S:OK" : "S:--";
        String run = validationText.isEmpty() ? "R:OK" : "R:--";
        return area + " " + blocks + " " + source + " " + run;
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

    private void restoreDistrictCounts(String encoded) {
        clearDistrictCounts();
        if (encoded == null || encoded.isBlank()) {
            return;
        }
        String[] entries = encoded.split("\\|");
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String[] parts = entry.split(":", 2);
            if (parts.length != 2) {
                continue;
            }
            String type = normalizeDistrictType(parts[0]);
            if (type.isBlank()) {
                continue;
            }
            Integer count = parseInt(parts[1]);
            districtCounts.put(type, count == null ? 0 : Math.max(0, count));
        }
    }

    private String encodeDistrictCounts() {
        return String.join("|",
            "residential:" + districtCounts.getOrDefault("residential", 0),
            "market:" + districtCounts.getOrDefault("market", 0),
            "workshop:" + districtCounts.getOrDefault("workshop", 0),
            "civic:" + districtCounts.getOrDefault("civic", 0),
            "mixed:" + districtCounts.getOrDefault("mixed", 0)
        );
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

        UiState.blueprint = readProfileValue(profile, "blueprint", "town_house_small");
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
        UiState.districtCounts = readProfileValue(profile, "districtCounts", "");
        UiState.citySummary = readProfileValue(profile, "citySummary", "none");
        UiState.cityPreset = readProfileValue(profile, "cityPreset", "medieval");
        UiState.cityPaletteOverride = readProfileBoolean(profile, "cityPaletteOverride", false);
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
        UiState.favorites = joinPipe(favoriteBlockIds);
        UiState.recent = joinPipe(new ArrayList<>(recentBlockIds));
        UiState.districtCounts = encodeDistrictCounts();
        UiState.citySummary = citySummary == null ? "none" : citySummary;
        UiState.cityPreset = normalizeCityPreset(cityLayoutPreset);
        UiState.cityPaletteOverride = cityPaletteOverrideEnabled;

        writeProfileValue(profileKey, "mode", UiState.mode);
        writeProfileValue(profileKey, "flow", UiState.flow);
        writeProfileValue(profileKey, "axis", UiState.axis);
        writeProfileValue(profileKey, "manualCoords", Boolean.toString(UiState.manualCoords));

        writeProfileValue(profileKey, "x", UiState.x);
        writeProfileValue(profileKey, "y", UiState.y);
        writeProfileValue(profileKey, "z", UiState.z);
        writeProfileValue(profileKey, "count", UiState.count);
        writeProfileValue(profileKey, "height", UiState.height);

        writeProfileValue(profileKey, "blueprint", UiState.blueprint);        writeProfileValue(profileKey, "search", UiState.search);

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
        writeProfileValue(profileKey, "districtCounts", UiState.districtCounts);
        writeProfileValue(profileKey, "citySummary", UiState.citySummary);
        writeProfileValue(profileKey, "cityPreset", UiState.cityPreset);
        writeProfileValue(profileKey, "cityPaletteOverride", Boolean.toString(UiState.cityPaletteOverride));

        flushHudStore();
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubleClick) {
        // The planning map gets first chance at mouse input so dragging/selecting
        // does not get swallowed by the normal widget tree.
        if (handlePlanningMapClick(click.x(), click.y(), click.button())) {
            return true;
        }
        return super.mouseClicked(click, doubleClick);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.gui.Click click, double deltaX, double deltaY) {
        if (planningDragActive && click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            MinimapView view = currentMinimapView();
            BlockPos dragPos = view == null ? null : mapScreenToWorld(view, click.x(), click.y());
            if (dragPos != null) {
                // Keep the live draft in sync with the overlay so the player can
                // see the exact footprint before committing the selection.
                markerA = planningDragOrigin;
                markerB = dragPos;
                syncOverlayDraft();
                updateMarkerButtonLabels();
                updateRunGuard();
                statusText = "Area draft: " + shortTarget(markerA) + " -> " + shortTarget(markerB);
            }
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.gui.Click click) {
        if (planningDragActive && click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            planningDragActive = false;
            MinimapView view = currentMinimapView();
            BlockPos endPos = view == null ? null : mapScreenToWorld(view, click.x(), click.y());
            if (endPos != null) {
                markerA = planningDragOrigin;
                markerB = endPos;
            }
            finalizePlanningMapSelection();
            return true;
        }
        return super.mouseReleased(click);
    }

    private boolean handlePlanningMapClick(double mouseX, double mouseY, int button) {
        if (!showPlanningMap()) {
            return false;
        }
        MinimapView view = currentMinimapView();
        if (view == null || !isInside(mouseX, mouseY, view.screenX(), view.screenY(), view.screenW(), view.screenH())) {
            return false;
        }

        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            clearMarkers();
            return true;
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }

        BlockPos clicked = mapScreenToWorld(view, mouseX, mouseY);
        if (clicked == null) {
            return false;
        }
        boolean cityPlotSelection = MODE_CITY.equals(activeMode) && FLOW_SOURCE.equals(activeFlow) && districtBrush.isBlank();
        if (cityPlotSelection || (isShiftPressed() && districtBrush.isBlank())) {
            SuggestedPlot snappedPlot = suggestedPlotAt(clicked);
            if (snappedPlot != null) {
                snapToSuggestedPlot(snappedPlot);
                return true;
            }
        }
        planningDragActive = true;
        planningDragOrigin = clicked;
        markerA = clicked;
        markerB = clicked;
        syncOverlayDraft();
        updateMarkerButtonLabels();
        updateRunGuard();
        statusText = MODE_CITY.equals(activeMode) && FLOW_SOURCE.equals(activeFlow)
            ? "City map drag started"
            : "Map selection started";
        return true;
    }

    private void finalizePlanningMapSelection() {
        planningDragOrigin = null;
        updateMarkerButtonLabels();
        syncOverlayDraft();
        updateRunGuard();
        if (markerA == null || markerB == null) {
            return;
        }
        applyMarkerBox();
        if (MODE_CITY.equals(activeMode) && FLOW_SOURCE.equals(activeFlow) && !districtBrush.isBlank()) {
            saveZoneFromMarkers(districtBrush);
            statusText = "Saved " + districtBrush + " district from map selection";
            return;
        }
        statusText = "Selected area on planning map";
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
                sendAction(HudAction.PAUSE_BUILD);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_V) {
                sendAction(HudAction.CONTINUE_BUILD);
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
