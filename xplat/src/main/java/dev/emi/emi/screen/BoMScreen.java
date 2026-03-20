package dev.emi.emi.screen;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.Comparator;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.lwjgl.glfw.GLFW;

import com.google.common.collect.Lists;
import com.google.gson.JsonElement;
import com.mojang.blaze3d.systems.RenderSystem;

import dev.emi.emi.EmiPort;
import dev.emi.emi.EmiRenderHelper;
import dev.emi.emi.EmiUtil;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.recipe.EmiResolutionRecipe;
import dev.emi.emi.api.render.EmiTooltipComponents;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.serializer.EmiIngredientSerializer;
import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.bom.BoM;
import dev.emi.emi.bom.ChanceMaterialCost;
import dev.emi.emi.bom.ChanceState;
import dev.emi.emi.bom.FlatMaterialCost;
import dev.emi.emi.bom.FoldState;
import dev.emi.emi.bom.MaterialNode;
import dev.emi.emi.bom.MaterialTree;
import dev.emi.emi.bom.ProgressState;
import dev.emi.emi.bom.PureRefProject;
import dev.emi.emi.bom.SavedRecipeTree;
import dev.emi.emi.bom.TreeCost;
import dev.emi.emi.config.EmiConfig;
import dev.emi.emi.data.EmiRecipeCategoryProperties;
import dev.emi.emi.input.EmiBind;
import dev.emi.emi.input.EmiInput;
import dev.emi.emi.registry.EmiStackList;
import dev.emi.emi.runtime.EmiDrawContext;
import dev.emi.emi.runtime.EmiFavorites;
import dev.emi.emi.runtime.EmiHistory;
import dev.emi.emi.screen.MicroTextRenderer;
import dev.emi.emi.screen.tooltip.EmiTooltip;
import dev.emi.emi.screen.tooltip.RecipeTooltipComponent;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.tooltip.TooltipComponent;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;

public class BoMScreen extends Screen {
	private static final int NODE_WIDTH = 30;
	private static final int NODE_HORIZONTAL_SPACING = 8;
	private static final int NODE_VERTICAL_SPACING = 20;
	private static final int COMPARISON_HORIZONTAL_SPACING = 28;
	private static final int COST_HORIZONTAL_SPACING = 8;
	private static final int PURE_REF_NOTE_WRAP_WIDTH = 260;
	private static final int BOARD_TREE_HEADER_HEIGHT = 18;
	private static final int BOARD_TREE_FOOTER_HEIGHT = 22;
	private static final int[] BOARD_CONTEXT_COLORS = new int[] {
		0xFFFFFFFF, 0xFFF0D992, 0xFF89B8E8, 0xFFB8E6A1, 0xFFF2A6A6, 0xFFE0B7FF, 0xFFFFD37A, 0xFF9FD3C7
	};
	private static int zoom = 0;
	private Bounds batches = new Bounds(-24, -50, 48, 26);
	private Bounds mode = new Bounds(-24, -50, 16, 16);
	private Bounds help = new Bounds(0, 0, 16, 16);
	private double offX, offY;
	private List<Node> nodes = Lists.newArrayList();
	private List<Cost> costs = Lists.newArrayList();
	private ButtonWidget modeToggleButton;
	private ButtonWidget libraryButton;
	private ButtonWidget projectButton;
	private ButtonWidget insertButton;
	private ButtonWidget backToBoardButton;
	private EmiPlayerInventory playerInv;
	private boolean hasRemainders = false;;
	public HandledScreen<?> old;
	private int nodeWidth = 0;
	private int nodeHeight = 0;
	private int lastMouseX, lastMouseY;
	private double scrollAcc = 0;
	private Node draggedNode;
	private boolean draggingBranch = false;
	private boolean draggedNodeMoved = false;
	private boolean panningView = false;
	private int dragLastTreeX;
	private int dragLastTreeY;
	private boolean loadWarning = false;
	private boolean libraryOpen = false;
	private float libraryScroll = 0;
	private float libraryScrollTarget = 0;
	private int selectedLibrarySlot = -1;
	private boolean compactLibrary = false;
	private int renamingSlot = -1;
	private TextFieldWidget renameField;
	private long lastLibraryClickTime = 0;
	private int lastLibraryClickSlot = -1;
	private long lastNodeClickTime = 0;
	private String lastNodeClickPath = null;
	private String contextMenuNodePath = null;
	private int contextMenuX = 0;
	private int contextMenuY = 0;
	private ViewMode viewMode = ViewMode.TREE;
	private PureRefTool pureRefTool = PureRefTool.SELECT;
	private boolean projectLibraryOpen = false;
	private boolean insertOverlayOpen = false;
	private float projectScroll = 0;
	private float projectScrollTarget = 0;
	private int selectedProjectSlot = -1;
	private int renamingProjectSlot = -1;
	private TextFieldWidget projectRenameField;
	private TextFieldWidget checklistTitleField;
	private TextFieldWidget checklistLabelField;
	private TextFieldWidget checklistCurrentField;
	private TextFieldWidget checklistTargetField;
	private PureRefProject.Object selectedPureRefObject = null;
	private PureRefProject.NoteObject editingNote = null;
	private PureRefProject.CheckListObject editingCheckList = null;
	private List<String> editingNoteLines = Lists.newArrayList();
	private int editingNoteCursorLine = 0;
	private int editingNoteCursorColumn = 0;
	private int editingCheckListRow = -1;
	private int editingCheckListScroll = 0;
	private boolean pureRefPanning = false;
	private boolean pureRefDraggingObject = false;
	private boolean pureRefResizingNote = false;
	private boolean pureRefResizingTree = false;
	private boolean pureRefResizingCheckList = false;
	private boolean pureRefCreatingShape = false;
	private boolean pureRefObjectMoved = false;
	private int pureRefDragLastX = 0;
	private int pureRefDragLastY = 0;
	private long lastPureRefClickTime = 0;
	private String lastPureRefClickId = null;
	private PureRefProject.ShapeObject activeShapeDraft = null;
	private PureRefProject.ShapeObject editingShape = null;
	private PureRefProject.TreeObject pendingTreeInsert = null;
	private PureRefProject.NoteObject pendingNoteDelete = null;
	private PureRefProject.Object boardContextMenuObject = null;
	private int boardContextMenuX = 0;
	private int boardContextMenuY = 0;
	private String lastSelectedBoardTreeId = null;
	private ShapeHandle activeShapeHandle = ShapeHandle.NONE;
	private PureRefProject.TreeObject focusedPureRefTree = null;
	private MaterialTree previousFocusedTree = null;
	private boolean previousFocusedCraftingMode = false;
	private double pureRefBoardOffX = 0;
	private double pureRefBoardOffY = 0;
	private int pureRefBoardZoom = 0;
	private double storedTreeOffX = 0;
	private double storedTreeOffY = 0;
	private int storedTreeZoom = 0;

	public BoMScreen(HandledScreen<?> old) {
		super(EmiPort.translatable("screen.emi.recipe_tree"));
		this.old = old;
	}

	public void init() {
		this.clearChildren();
		modeToggleButton = EmiPort.newButton(8, 8, 92, 20, EmiPort.literal(viewMode == ViewMode.TREE ? "Board View" : "Tree View"), button -> {
			if (focusedPureRefTree != null) {
				exitPureRefTreeFocus(true);
			}
			if (viewMode == ViewMode.TREE) {
				storedTreeOffX = offX;
				storedTreeOffY = offY;
				storedTreeZoom = zoom;
				viewMode = ViewMode.PURE_REF;
				offX = BoM.pureRefProject.offX;
				offY = BoM.pureRefProject.offY;
				zoom = BoM.pureRefProject.zoom;
			} else {
				BoM.pureRefProject.offX = offX;
				BoM.pureRefProject.offY = offY;
				BoM.pureRefProject.zoom = zoom;
				viewMode = ViewMode.TREE;
				offX = storedTreeOffX;
				offY = storedTreeOffY;
				zoom = storedTreeZoom;
			}
			closeContextMenu();
			projectLibraryOpen = false;
			insertOverlayOpen = false;
			recalculateTree();
			syncTopButtons();
		});
		this.addDrawableChild(modeToggleButton);
		libraryButton = EmiPort.newButton(width - 110, 8, 102, 20, EmiPort.literal("Tree Library"), button -> {
			libraryOpen = !libraryOpen;
			projectLibraryOpen = false;
			insertOverlayOpen = false;
			renamingSlot = -1;
			libraryScrollTarget = MathHelper.clamp(libraryScrollTarget, 0, getLibraryMaxScroll());
			libraryScroll = MathHelper.clamp(libraryScroll, 0, getLibraryMaxScroll());
			updateRenameField();
		});
		this.addDrawableChild(libraryButton);
		projectButton = EmiPort.newButton(width - 214, 8, 96, 20, EmiPort.literal("Projects"), button -> {
			projectLibraryOpen = !projectLibraryOpen;
			libraryOpen = false;
			insertOverlayOpen = false;
			renamingProjectSlot = -1;
			updateProjectRenameField();
		});
		this.addDrawableChild(projectButton);
		insertButton = EmiPort.newButton(width - 110, 8, 96, 20, EmiPort.literal("Insert Tree"), button -> {
			insertOverlayOpen = !insertOverlayOpen;
			projectLibraryOpen = false;
			libraryOpen = false;
		});
		this.addDrawableChild(insertButton);
		backToBoardButton = EmiPort.newButton(width - 118, 8, 110, 20, EmiPort.literal("Back To Board"), button -> {
			exitPureRefTreeFocus(true);
			syncTopButtons();
		});
		this.addDrawableChild(backToBoardButton);
		renameField = new TextFieldWidget(textRenderer, 0, 0, 180, 18, EmiPort.literal(""));
		renameField.setMaxLength(64);
		renameField.setVisible(false);
		this.addDrawableChild(renameField);
		projectRenameField = new TextFieldWidget(textRenderer, 0, 0, 200, 18, EmiPort.literal(""));
		projectRenameField.setMaxLength(64);
		projectRenameField.setVisible(false);
		this.addDrawableChild(projectRenameField);
		checklistTitleField = new TextFieldWidget(textRenderer, 0, 0, 220, 18, EmiPort.literal(""));
		checklistTitleField.setMaxLength(64);
		checklistTitleField.setVisible(false);
		checklistTitleField.setChangedListener(value -> {
			if (editingCheckList != null) {
				editingCheckList.title = value;
				markBoardDirty();
			}
		});
		this.addDrawableChild(checklistTitleField);
		checklistLabelField = new TextFieldWidget(textRenderer, 0, 0, 220, 18, EmiPort.literal(""));
		checklistLabelField.setMaxLength(64);
		checklistLabelField.setVisible(false);
		checklistLabelField.setChangedListener(value -> {
			PureRefProject.CheckListEntry entry = getSelectedCheckListEntry();
			if (entry != null) {
				entry.label = value;
				markBoardDirty();
			}
		});
		this.addDrawableChild(checklistLabelField);
		checklistCurrentField = new TextFieldWidget(textRenderer, 0, 0, 90, 18, EmiPort.literal(""));
		checklistCurrentField.setMaxLength(12);
		checklistCurrentField.setVisible(false);
		checklistCurrentField.setChangedListener(value -> {
			PureRefProject.CheckListEntry entry = getSelectedCheckListEntry();
			if (entry != null) {
				entry.currentAmount = parseChecklistAmount(value, entry.currentAmount);
				markBoardDirty();
			}
		});
		this.addDrawableChild(checklistCurrentField);
		checklistTargetField = new TextFieldWidget(textRenderer, 0, 0, 90, 18, EmiPort.literal(""));
		checklistTargetField.setMaxLength(12);
		checklistTargetField.setVisible(false);
		checklistTargetField.setChangedListener(value -> {
			PureRefProject.CheckListEntry entry = getSelectedCheckListEntry();
			if (entry != null) {
				entry.targetAmount = parseChecklistAmount(value, entry.targetAmount);
				markBoardDirty();
			}
		});
		this.addDrawableChild(checklistTargetField);
		if (BoM.tree != null) {
			if (!Double.isNaN(BoM.tree.snapshotOffX) && !Double.isNaN(BoM.tree.snapshotOffY)) {
				offX = BoM.tree.snapshotOffX;
				offY = BoM.tree.snapshotOffY;
				zoom = BoM.tree.snapshotZoom == Integer.MIN_VALUE ? zoom : BoM.tree.snapshotZoom;
				BoM.tree.snapshotOffX = Double.NaN;
				BoM.tree.snapshotOffY = Double.NaN;
				BoM.tree.snapshotZoom = Integer.MIN_VALUE;
			} else if (offX == 0 && offY == 0) {
				offY = height / -3;
			}
		} else {
			offY = 0;
		}
		syncTopButtons();
		updateProjectRenameField();
		updateNoteEditorFields();
		updateCheckListEditorFields();
		recalculateTree();
	}

	public void recalculateTree() {
		recalculateTree(null);
	}

	private boolean isTreeViewportActive() {
		return viewMode == ViewMode.TREE || focusedPureRefTree != null;
	}

	private void syncTopButtons() {
		if (modeToggleButton != null) {
			modeToggleButton.setMessage(EmiPort.literal(viewMode == ViewMode.TREE ? "Board View" : "Tree View"));
			modeToggleButton.visible = focusedPureRefTree == null;
			modeToggleButton.active = focusedPureRefTree == null;
		}
		if (libraryButton != null) {
			boolean visible = viewMode == ViewMode.TREE && focusedPureRefTree == null;
			libraryButton.visible = visible;
			libraryButton.active = visible;
		}
		if (projectButton != null) {
			boolean visible = viewMode == ViewMode.PURE_REF && focusedPureRefTree == null;
			projectButton.visible = visible;
			projectButton.active = visible;
		}
		if (insertButton != null) {
			boolean visible = viewMode == ViewMode.PURE_REF && focusedPureRefTree == null;
			insertButton.visible = visible;
			insertButton.active = visible;
		}
		if (backToBoardButton != null) {
			boolean visible = focusedPureRefTree != null;
			backToBoardButton.visible = visible;
			backToBoardButton.active = visible;
		}
	}

	public void recalculateTree(String resetPath) {
		help = new Bounds(width - 18, height - 18, 16, 16);
		if (BoM.tree != null) {
			Map<String, NodePosition> previousPositions;
			if (resetPath == null) {
				previousPositions = nodes.stream()
					.collect(Collectors.toMap(n -> n.path, n -> new NodePosition(n.x, n.y, n.getStructureKey()), (a, b) -> b));
			} else {
				// Structure changes must reflow the tree around the changed branch.
				// Preserve only explicit saved offsets, not stale sibling positions.
				previousPositions = Map.of();
			}
			TreeVolume volume = addNewNodes(BoM.tree.goal, BoM.tree.batches, 1, 0, ChanceState.DEFAULT, "0", -1, 0);
			nodes = volume.nodes;
			int horizontalOffset = (volume.getMaxRight() + volume.getMinLeft()) / 2;
			for (Node node : volume.nodes) {
				node.x -= horizontalOffset;
				node.layoutX = node.x;
				node.layoutY = node.y;
			}
			applyAnchoredPositions(previousPositions);
			if (!volume.nodes.isEmpty()) {
				Node node = volume.nodes.get(0);
				int width = textRenderer.getWidth("x" + BoM.tree.batches);
				batches = new Bounds(node.x + node.width / 2 + 6, node.y - 10, width + 12, 22);
			}

			nodeWidth = volume.getMaxRight() - volume.getMinLeft();
			nodeHeight = getNodeHeight(BoM.tree.goal);
			playerInv = EmiPlayerInventory.of(client.player);
			BoM.tree.calculateProgress(playerInv);
			Map<EmiIngredient, FlatMaterialCost> progressCosts = BoM.tree.cost.costs.values().stream()
				.collect(Collectors.toMap(c -> c.ingredient, c -> c));
			Map<EmiIngredient, ChanceMaterialCost> chanceProgressCosts = BoM.tree.cost.chanceCosts.values().stream()
				.collect(Collectors.toMap(c -> c.ingredient, c -> c));
				
			costs.clear();
			BoM.tree.calculateCost();

			List<FlatMaterialCost> treeCosts = Stream.concat(
				BoM.tree.cost.costs.values().stream(),
				BoM.tree.cost.chanceCosts.values().stream()
			).sorted((a, b) -> Integer.compare(
				EmiStackList.getIndex(a.ingredient.getEmiStacks().get(0)),
				EmiStackList.getIndex(b.ingredient.getEmiStacks().get(0))
			)).toList();
			int cy = nodeHeight * NODE_VERTICAL_SPACING * 2;
			int costX = 0;
			for (FlatMaterialCost node : treeCosts) {
				Cost cost = new Cost(node, costX, cy, false);
				if (BoM.craftingMode) {
					if (node instanceof ChanceMaterialCost cmc) {
						if (!chanceProgressCosts.containsKey(node.ingredient)) {
							cost.alreadyDone = node.getEffectiveAmount();
						} else {
							ChanceMaterialCost progress = chanceProgressCosts.get(node.ingredient);
							cost.alreadyDone = (long) Math.ceil(cmc.amount * cmc.chance - progress.amount * progress.chance);
						}
					} else {
						if (!progressCosts.containsKey(node.ingredient)) {
							cost.alreadyDone = node.amount;
						} else {
							FlatMaterialCost progress = progressCosts.get(node.ingredient);
							cost.alreadyDone = node.amount - progress.amount;
						}
					}
				}
				costs.add(cost);
				costX += 16 + COST_HORIZONTAL_SPACING + EmiRenderHelper.getAmountOverflow(cost.getAmountText());
			}
			int costOffset = (costX - COST_HORIZONTAL_SPACING) / 2;
			for (Cost cost : costs) {
				cost.x -= costOffset;
			}

			int totalCostWidth = textRenderer.getWidth(EmiPort.translatable("emi.total_cost"));
			mode = new Bounds(totalCostWidth / 2 + 4, cy - 20, 16, 16);

			List<Cost> remainders = Lists.newArrayList();

			List<FlatMaterialCost> remainderCosts = Stream.concat(
				BoM.tree.cost.remainders.values().stream(),
				BoM.tree.cost.chanceRemainders.values().stream()
			).sorted((a, b) -> Integer.compare(
				EmiStackList.getIndex(a.ingredient.getEmiStacks().get(0)),
				EmiStackList.getIndex(b.ingredient.getEmiStacks().get(0))
			)).toList();
			cy += 40;
			int remainderX = 0;
			for (FlatMaterialCost node : remainderCosts) {
				if (node.getEffectiveAmount() <= 0) {
					continue;
				}
				Cost cost = new Cost(node, remainderX, cy, true);
				remainders.add(cost);
				remainderX += 16 + COST_HORIZONTAL_SPACING + EmiRenderHelper.getAmountOverflow(cost.getAmountText());
			}
			costOffset = (remainderX - COST_HORIZONTAL_SPACING) / 2;
			for (Cost cost : remainders) {
				cost.x -= costOffset;
			}
			costs.addAll(remainders);
			hasRemainders = !remainders.isEmpty();
		} else {
			nodes = Lists.newArrayList();
		}
	}

	private void applyAnchoredPositions(Map<String, NodePosition> previousPositions) {
		if (BoM.tree == null) {
			return;
		}
		Set<String> resetPrefixes = new java.util.HashSet<>();
		List<Node> ordered = nodes.stream()
			.sorted(Comparator.comparingInt((Node n) -> n.path.length()))
			.toList();
		for (Node node : ordered) {
			boolean underReset = resetPrefixes.stream().anyMatch(prefix -> node.path.startsWith(prefix + "/"));
			NodePosition previous = underReset ? null : previousPositions.get(node.path);
			int targetX = node.x;
			int targetY = node.y;
			if (previous != null && Objects.equals(previous.structureKey(), node.getStructureKey())) {
				targetX = previous.x();
				targetY = previous.y();
			} else {
				if (previous != null) {
					resetPrefixes.add(node.path);
				}
				MaterialTree.NodeOffset offset = BoM.tree.nodeOffsets.get(node.path);
				if (offset != null) {
					targetX = node.layoutX + offset.x();
					targetY = node.layoutY + offset.y();
				}
			}
			if (targetX != node.x || targetY != node.y) {
				shiftSubtree(node.path, targetX - node.x, targetY - node.y);
			}
		}
	}

	private void shiftSubtree(String path, int dx, int dy) {
		for (Node node : nodes) {
			if (node.path.equals(path) || node.path.startsWith(path + "/")) {
				node.x += dx;
				node.y += dy;
			}
		}
	}

	private Bounds getLibraryPanelBounds() {
		int panelWidth = Math.min(compactLibrary ? 340 : 430, width - 24);
		int panelHeight = Math.min(height - 56, compactLibrary ? 314 : 392);
		return new Bounds(width - panelWidth - 12, 36, panelWidth, panelHeight);
	}

	private int getLibraryRowHeight() {
		return compactLibrary ? 48 : 60;
	}

	private int getLibraryVisibleRows() {
		return Math.max(1, (getLibraryPanelBounds().height() - 78) / getLibraryRowHeight() + 1);
	}

	private int getLibraryMaxScroll() {
		return Math.max(0, BoM.TREE_SLOT_COUNT - getLibraryVisibleRows());
	}

	private Bounds getLibraryRowBounds(Bounds panel, int visibleIndex) {
		return new Bounds(panel.x() + 12, panel.y() + 40 + visibleIndex * getLibraryRowHeight(), panel.width() - 24, getLibraryRowHeight() - 6);
	}

	private Bounds getLibraryButtonBounds(Bounds row, int right, String label) {
		int width = Math.max(compactLibrary ? 36 : 44, textRenderer.getWidth(label) + (compactLibrary ? 10 : 16));
		int height = compactLibrary ? 14 : 16;
		int y = row.y() + row.height() - height - 6;
		return new Bounds(right - width, y, width, height);
	}

	private Bounds getLibraryHeaderButton(Bounds panel) {
		return new Bounds(panel.x() + panel.width() - 84, panel.y() + 7, 72, 18);
	}

	private LibraryRowButtons getLibraryRowButtons(Bounds row) {
		int right = row.x() + row.width() - 10;
		Bounds override = getLibraryButtonBounds(row, right, "Override");
		right = override.x() - 6;
		Bounds delete = getLibraryButtonBounds(row, right, "Delete");
		right = delete.x() - 6;
		Bounds rename = getLibraryButtonBounds(row, right, "Rename");
		right = rename.x() - 6;
		Bounds save = getLibraryButtonBounds(row, right, "Save");
		return new LibraryRowButtons(save, rename, delete, override);
	}

	private Text trimLibraryText(String text, int width, Formatting formatting) {
		if (width <= 8) {
			return EmiPort.literal("", formatting);
		}
		String trimmed = textRenderer.trimToWidth(text, width);
		if (trimmed.length() < text.length() && width > textRenderer.getWidth("...")) {
			String ellipsis = "...";
			trimmed = textRenderer.trimToWidth(text, Math.max(0, width - textRenderer.getWidth(ellipsis))) + ellipsis;
		}
		return EmiPort.literal(trimmed, formatting);
	}

	private void updateRenameField() {
		if (renameField == null) {
			return;
		}
		boolean wasVisible = renameField.isVisible();
		if (!libraryOpen || renamingSlot < 0 || renamingSlot >= BoM.TREE_SLOT_COUNT) {
			renameField.setVisible(false);
			renameField.setFocused(false);
			return;
		}
		Bounds panel = getLibraryPanelBounds();
		renameField.setVisible(true);
		renameField.setX(panel.x() + 12);
		renameField.setY(panel.y() + panel.height() - 26);
		renameField.setWidth(panel.width() - 24);
		if (!wasVisible) {
			renameField.setText(BoM.getSavedTree(renamingSlot).name);
			renameField.setFocused(true);
		}
	}

	private void commitRename() {
		if (renamingSlot >= 0 && renameField != null) {
			BoM.renameSavedTree(renamingSlot, renameField.getText().trim());
		}
		renamingSlot = -1;
		updateRenameField();
	}

	private void renderLibraryOverlay(EmiDrawContext context, DrawContext raw, int mouseX, int mouseY, float delta) {
		context.push();
		context.matrices().translate(0, 0, 500);
		RenderSystem.disableDepthTest();
		Bounds panel = getLibraryPanelBounds();
		context.fill(panel.x() - 6, panel.y() - 6, panel.width() + 12, panel.height() + 12, 0x33000000);
		context.fill(panel.x() - 1, panel.y() - 1, panel.width() + 2, panel.height() + 2, 0x99354B63);
		context.fill(panel.x(), panel.y(), panel.width(), panel.height(), 0xF1141B24);
		context.fill(panel.x(), panel.y(), panel.width(), 30, 0xFF1D2A38);
		context.fill(panel.x(), panel.y() + 30, panel.width(), 1, 0xAA56738F);
		context.drawTextWithShadow(EmiPort.literal("Recipe Tree Library", Formatting.WHITE), panel.x() + 12, panel.y() + 10, -1);
		renderLibraryAction(context, getLibraryHeaderButton(panel), compactLibrary ? "Comfort" : "Compact", true, mouseX, mouseY);
		int hintX = panel.x() + 124;
		int hintWidth = getLibraryHeaderButton(panel).x() - hintX - 8;
		if (hintWidth > 36) {
			context.drawTextWithShadow(trimLibraryText("Double-click to load", hintWidth, Formatting.DARK_GRAY), hintX, panel.y() + 10, -1);
		}
		int rowHeight = getLibraryRowHeight();
		int firstRow = Math.max(0, (int) Math.floor(libraryScroll));
		float rowOffset = libraryScroll - firstRow;
		int visibleRows = getLibraryVisibleRows();
		for (int i = 0; i < visibleRows + 1; i++) {
			int slot = firstRow + i;
			if (slot >= BoM.TREE_SLOT_COUNT) {
				break;
			}
			SavedRecipeTree saved = BoM.getSavedTree(slot);
			Bounds row = getLibraryRowBounds(panel, i);
			row = new Bounds(row.x(), row.y() - Math.round(rowOffset * rowHeight), row.width(), row.height());
			if (row.y() + row.height() < panel.y() + 32 || row.y() > panel.y() + panel.height() - 40) {
				continue;
			}
			boolean hovered = row.contains(mouseX, mouseY);
			boolean selected = selectedLibrarySlot == slot;
			int card = hovered ? 0xFF243648 : selected ? 0xFF1E3143 : 0xCC18222D;
			context.fill(row.x(), row.y(), row.width(), row.height(), card);
			context.fill(row.x(), row.y(), 3, row.height(), saved.hasMissingData() ? 0xFFE46B6B : selected ? 0xFFD8C27A : 0xFF8AB7D6);
			LibraryRowButtons buttons = getLibraryRowButtons(row);
			int thumbX = row.x() + 10;
			int thumbY = row.y() + 7;
			EmiIngredient thumbnail = saved.thumbnail == null ? EmiStack.EMPTY : saved.thumbnail;
			if (!thumbnail.isEmpty()) {
				thumbnail.render(raw, thumbX, thumbY, delta, 0);
			}
			int textX = thumbX + 24;
			int textWidth = Math.max(40, row.x() + row.width() - textX - 10);
			String titleText = saved.isEmpty()
				? (slot + 1) + ". Empty Slot"
				: (slot + 1) + ". " + (saved.name.isBlank() ? getDefaultTreeName() : saved.name);
			context.drawTextWithShadow(trimLibraryText(titleText, textWidth, saved.isEmpty() ? Formatting.DARK_GRAY : Formatting.WHITE),
				textX, row.y() + 9, -1);
			if (saved.hasMissingData()) {
				context.drawTextWithShadow(trimLibraryText("Missing data", textWidth, Formatting.RED),
					textX, row.y() + 22, -1);
			} else if (!compactLibrary) {
				context.drawTextWithShadow(trimLibraryText(saved.isEmpty() ? "Save current tree here" : "Stored tree snapshot", textWidth, Formatting.DARK_GRAY),
					textX, row.y() + 22, -1);
			}
			renderLibraryAction(context, buttons.save, "Save", canSaveToSlot(saved), mouseX, mouseY);
			renderLibraryAction(context, buttons.rename, "Rename", !saved.isEmpty(), mouseX, mouseY);
			renderLibraryAction(context, buttons.delete, "Delete", !saved.isEmpty(), mouseX, mouseY);
			renderLibraryAction(context, buttons.override, "Override", canOverrideSlot(saved), mouseX, mouseY);
		}
		if (getLibraryMaxScroll() > 0) {
			int trackX = panel.x() + panel.width() - 7;
			int trackHeight = panel.height() - 82;
			context.fill(trackX, panel.y() + 40, 3, trackHeight, 0x55273A4D);
			int thumbHeight = Math.max(20, trackHeight * getLibraryVisibleRows() / BoM.TREE_SLOT_COUNT);
			int thumbY = panel.y() + 40 + Math.round((trackHeight - thumbHeight) * (libraryScroll / Math.max(1, getLibraryMaxScroll())));
			context.fill(trackX, thumbY, 3, thumbHeight, 0xFFC7D8E8);
		}
		if (renameField != null && renameField.isVisible()) {
			context.drawTextWithShadow(EmiPort.literal("Rename slot and press Enter", Formatting.GRAY), panel.x() + 12, panel.y() + panel.height() - 40, -1);
		}
		RenderSystem.enableDepthTest();
		context.pop();
	}

	private void renderLibraryAction(EmiDrawContext context, Bounds bounds, String label, boolean active, int mouseX, int mouseY) {
		int color = active ? (bounds.contains(mouseX, mouseY) ? 0xFF7BA7D0 : 0xFF36506A) : 0xFF26303A;
		context.fill(bounds.x(), bounds.y(), bounds.width(), bounds.height(), color);
		context.fill(bounds.x(), bounds.y(), bounds.width(), 1, active ? 0x55FFFFFF : 0x33111111);
		context.drawCenteredText(EmiPort.literal(label, active ? Formatting.WHITE : Formatting.DARK_GRAY),
			bounds.x() + bounds.width() / 2, bounds.y() + (compactLibrary ? 4 : 5));
	}

	private boolean canSaveToSlot(SavedRecipeTree slot) {
		return slot.isEmpty() && createSnapshot() != null;
	}

	private boolean canOverrideSlot(SavedRecipeTree slot) {
		return !slot.isEmpty() && createSnapshot() != null;
	}

	private Bounds getProjectPanelBounds() {
		int panelWidth = Math.min(408, width - 24);
		int panelHeight = Math.min(356, height - 56);
		return new Bounds(width - panelWidth - 12, 36, panelWidth, panelHeight);
	}

	private int getProjectRowHeight() {
		return 52;
	}

	private int getProjectVisibleRows() {
		return Math.max(1, (getProjectPanelBounds().height() - 78) / getProjectRowHeight() + 1);
	}

	private int getProjectMaxScroll() {
		return Math.max(0, BoM.PURE_REF_SLOT_COUNT - getProjectVisibleRows());
	}

	private Bounds getProjectRowBounds(Bounds panel, int visibleIndex) {
		return new Bounds(panel.x() + 12, panel.y() + 40 + visibleIndex * getProjectRowHeight(), panel.width() - 24, getProjectRowHeight() - 6);
	}

	private Bounds getProjectHeaderButton(Bounds panel) {
		return new Bounds(panel.x() + panel.width() - 86, panel.y() + 7, 74, 18);
	}

	private Bounds getProjectButtonBounds(Bounds row, int right, String label) {
		int width = Math.max(42, textRenderer.getWidth(label) + 14);
		int y = row.y() + row.height() - 20;
		return new Bounds(right - width, y, width, 16);
	}

	private LibraryRowButtons getProjectRowButtons(Bounds row) {
		int right = row.x() + row.width() - 10;
		Bounds override = getProjectButtonBounds(row, right, "Override");
		right = override.x() - 6;
		Bounds delete = getProjectButtonBounds(row, right, "Delete");
		right = delete.x() - 6;
		Bounds rename = getProjectButtonBounds(row, right, "Rename");
		right = rename.x() - 6;
		Bounds save = getProjectButtonBounds(row, right, "Save");
		return new LibraryRowButtons(save, rename, delete, override);
	}

	private void updateProjectRenameField() {
		if (projectRenameField == null) {
			return;
		}
		boolean wasVisible = projectRenameField.isVisible();
		if (!projectLibraryOpen || renamingProjectSlot < 0 || renamingProjectSlot >= BoM.PURE_REF_SLOT_COUNT) {
			projectRenameField.setVisible(false);
			projectRenameField.setFocused(false);
			return;
		}
		Bounds panel = getProjectPanelBounds();
		projectRenameField.setVisible(true);
		projectRenameField.setX(panel.x() + 12);
		projectRenameField.setY(panel.y() + panel.height() - 26);
		projectRenameField.setWidth(panel.width() - 24);
		if (!wasVisible) {
			projectRenameField.setText(renamingProjectSlot == 0 ? project().name : BoM.getSavedPureRefProject(renamingProjectSlot).name);
			projectRenameField.setFocused(true);
		}
	}

	private void commitProjectRename() {
		if (renamingProjectSlot >= 0 && projectRenameField != null) {
			if (renamingProjectSlot == 0) {
				project().name = projectRenameField.getText().trim().isBlank() ? "World Project" : projectRenameField.getText().trim();
				markBoardDirty();
				BoM.flushPureRefAutosave();
			} else {
				BoM.renameSavedPureRefProject(renamingProjectSlot, projectRenameField.getText().trim());
			}
		}
		renamingProjectSlot = -1;
		updateProjectRenameField();
	}

	private String getDefaultProjectName() {
		String name = BoM.pureRefProject.name;
		if (name != null && !name.isBlank()) {
			return name;
		}
		return "Project";
	}

	private void saveProjectToSlot(int slot, boolean override) {
		if (slot == 0) {
			project().name = project().name == null || project().name.isBlank() ? "World Project" : project().name;
			project().offX = offX;
			project().offY = offY;
			project().zoom = zoom;
			markBoardDirty();
			BoM.flushPureRefAutosave();
			selectedProjectSlot = slot;
			return;
		}
		PureRefProject existing = BoM.getSavedPureRefProject(slot);
		if (!override && !existing.isEmpty()) {
			return;
		}
		PureRefProject project = BoM.pureRefProject.copy();
		if (project.name == null || project.name.isBlank()) {
			project.name = getDefaultProjectName();
		}
		project.offX = offX;
		project.offY = offY;
		project.zoom = zoom;
		BoM.savePureRefProject(slot, project);
		selectedProjectSlot = slot;
	}

	private void renderProjectOverlay(EmiDrawContext context, int mouseX, int mouseY) {
		context.push();
		context.matrices().translate(0, 0, 500);
		RenderSystem.disableDepthTest();
		Bounds panel = getProjectPanelBounds();
		context.fill(panel.x() - 6, panel.y() - 6, panel.width() + 12, panel.height() + 12, 0x33000000);
		context.fill(panel.x() - 1, panel.y() - 1, panel.width() + 2, panel.height() + 2, 0x99476335);
		context.fill(panel.x(), panel.y(), panel.width(), panel.height(), 0xF1161B14);
		context.fill(panel.x(), panel.y(), panel.width(), 30, 0xFF2C221B);
		context.drawTextWithShadow(EmiPort.literal("Board Projects", Formatting.WHITE), panel.x() + 12, panel.y() + 10, -1);
		renderLibraryAction(context, getProjectHeaderButton(panel), "Current", true, mouseX, mouseY);
		int firstRow = Math.max(0, (int) Math.floor(projectScroll));
		float rowOffset = projectScroll - firstRow;
		int visibleRows = getProjectVisibleRows();
		for (int i = 0; i < visibleRows + 1; i++) {
			int slot = firstRow + i;
			if (slot >= BoM.PURE_REF_SLOT_COUNT) {
				break;
			}
			PureRefProject project = slot == 0 ? project() : BoM.getSavedPureRefProject(slot);
			Bounds row = getProjectRowBounds(panel, i);
			row = new Bounds(row.x(), row.y() - Math.round(rowOffset * getProjectRowHeight()), row.width(), row.height());
			if (row.y() + row.height() < panel.y() + 32 || row.y() > panel.y() + panel.height() - 40) {
				continue;
			}
			boolean hovered = row.contains(mouseX, mouseY);
			boolean selected = selectedProjectSlot == slot;
			context.fill(row.x(), row.y(), row.width(), row.height(), hovered ? 0xFF3A3128 : selected ? 0xFF302922 : 0xCC241D18);
			context.fill(row.x(), row.y(), 3, row.height(), selected ? 0xFFD8C27A : 0xFFB58C62);
			String title = slot == 0
				? "1. " + (project.name == null || project.name.isBlank() ? "World Project" : project.name)
				: project.isEmpty() ? (slot + 1) + ". Empty Project" : (slot + 1) + ". " + (project.name == null || project.name.isBlank() ? "Project" : project.name);
			context.drawTextWithShadow(trimLibraryText(title, row.width() - 160, project.isEmpty() ? Formatting.DARK_GRAY : Formatting.WHITE),
				row.x() + 10, row.y() + 8, -1);
			String summary = slot == 0 ? "World-local autosaved board"
				: project.isEmpty() ? "Save current board here" : project.objects.size() + " objects";
			context.drawTextWithShadow(trimLibraryText(summary, row.width() - 160, Formatting.DARK_GRAY), row.x() + 10, row.y() + 22, -1);
			LibraryRowButtons buttons = getProjectRowButtons(row);
			renderLibraryAction(context, buttons.save, "Save", true, mouseX, mouseY);
			renderLibraryAction(context, buttons.rename, "Rename", slot == 0 || !project.isEmpty(), mouseX, mouseY);
			renderLibraryAction(context, buttons.delete, "Delete", slot != 0 && !project.isEmpty(), mouseX, mouseY);
			renderLibraryAction(context, buttons.override, "Override", true, mouseX, mouseY);
		}
		RenderSystem.enableDepthTest();
		context.pop();
	}

	private boolean handleProjectOverlayClick(double mouseX, double mouseY, int button) {
		if (!projectLibraryOpen || button != 0) {
			return false;
		}
		Bounds panel = getProjectPanelBounds();
		if (!panel.contains((int) mouseX, (int) mouseY)) {
			renamingProjectSlot = -1;
			updateProjectRenameField();
			return false;
		}
		int firstRow = Math.max(0, (int) Math.floor(projectScroll));
		float rowOffset = projectScroll - firstRow;
		int visibleRows = getProjectVisibleRows();
		for (int i = 0; i < visibleRows + 1; i++) {
			int slot = firstRow + i;
			if (slot >= BoM.PURE_REF_SLOT_COUNT) {
				break;
			}
			PureRefProject project = slot == 0 ? project() : BoM.getSavedPureRefProject(slot);
			Bounds row = getProjectRowBounds(panel, i);
			row = new Bounds(row.x(), row.y() - Math.round(rowOffset * getProjectRowHeight()), row.width(), row.height());
			if (!row.contains((int) mouseX, (int) mouseY)) {
				continue;
			}
			LibraryRowButtons buttons = getProjectRowButtons(row);
			if (buttons.save.contains((int) mouseX, (int) mouseY)) {
				saveProjectToSlot(slot, false);
				return true;
			}
			if (buttons.rename.contains((int) mouseX, (int) mouseY) && (slot == 0 || !project.isEmpty())) {
				selectedProjectSlot = slot;
				renamingProjectSlot = slot;
				updateProjectRenameField();
				return true;
			}
			if (buttons.delete.contains((int) mouseX, (int) mouseY) && slot != 0 && !project.isEmpty()) {
				BoM.deleteSavedPureRefProject(slot);
				selectedProjectSlot = Math.min(slot, BoM.PURE_REF_SLOT_COUNT - 1);
				return true;
			}
			if (buttons.override.contains((int) mouseX, (int) mouseY) && (slot == 0 || !project.isEmpty())) {
				saveProjectToSlot(slot, true);
				return true;
			}
			long now = System.currentTimeMillis();
			selectedProjectSlot = slot;
			if ((slot == 0 || !project.isEmpty()) && lastLibraryClickSlot == slot && now - lastLibraryClickTime < 250) {
				if (slot == 0) {
					offX = project().offX;
					offY = project().offY;
					zoom = project().zoom;
				} else if (BoM.loadSavedPureRefProject(slot)) {
					offX = BoM.pureRefProject.offX;
					offY = BoM.pureRefProject.offY;
					zoom = BoM.pureRefProject.zoom;
					markBoardDirty();
				}
			}
			lastLibraryClickSlot = slot;
			lastLibraryClickTime = now;
			return true;
		}
		return true;
	}

	private Bounds getInsertOverlayBounds() {
		int panelWidth = Math.min(320, width - 24);
		int panelHeight = Math.min(300, height - 56);
		return new Bounds(12, 36, panelWidth, panelHeight);
	}

	private void renderInsertOverlay(EmiDrawContext context, DrawContext raw, int mouseX, int mouseY, float delta) {
		context.push();
		context.matrices().translate(0, 0, 500);
		RenderSystem.disableDepthTest();
		Bounds panel = getInsertOverlayBounds();
		context.fill(panel.x() - 4, panel.y() - 4, panel.width() + 8, panel.height() + 8, 0x33000000);
		context.fill(panel.x(), panel.y(), panel.width(), panel.height(), 0xF1151A23);
		context.fill(panel.x(), panel.y(), panel.width(), 30, 0xFF1D2A38);
		context.drawTextWithShadow(EmiPort.literal("Insert Saved Tree", Formatting.WHITE), panel.x() + 12, panel.y() + 10, -1);
		int y = panel.y() + 38;
		boolean foundAny = false;
		for (int i = 0; i < BoM.TREE_SLOT_COUNT && y < panel.y() + panel.height() - 24; i++) {
			SavedRecipeTree saved = BoM.getSavedTree(i);
			if (saved.isEmpty()) {
				continue;
			}
			foundAny = true;
			String title = (i + 1) + ". " + (saved.name == null || saved.name.isBlank() ? "Saved Tree" : saved.name);
			renderInsertRow(context, raw, new Bounds(panel.x() + 10, y, panel.width() - 20, 24), saved.thumbnail, title, mouseX, mouseY, delta);
			y += 28;
		}
		if (!foundAny) {
			context.drawTextWithShadow(EmiPort.literal("No saved recipe trees available", Formatting.GRAY), panel.x() + 12, panel.y() + 42, -1);
			context.drawTextWithShadow(EmiPort.literal("Save a tree in Tree View first", Formatting.DARK_GRAY), panel.x() + 12, panel.y() + 56, -1);
		}
		RenderSystem.enableDepthTest();
		context.pop();
	}

	private void renderInsertRow(EmiDrawContext context, DrawContext raw, Bounds row, EmiIngredient thumbnail, String title, int mouseX, int mouseY, float delta) {
		context.fill(row.x(), row.y(), row.width(), row.height(), row.contains(mouseX, mouseY) ? 0xFF31465E : 0xCC1F2A36);
		if (thumbnail != null && !thumbnail.isEmpty()) {
			thumbnail.render(raw, row.x() + 4, row.y() + 4, delta, 0);
		}
		context.drawTextWithShadow(trimLibraryText(title, row.width() - 28, Formatting.WHITE), row.x() + 24, row.y() + 8, -1);
	}

	private boolean handleInsertOverlayClick(double mouseX, double mouseY, int button) {
		if (!insertOverlayOpen || button != 0) {
			return false;
		}
		Bounds panel = getInsertOverlayBounds();
		if (!panel.contains((int) mouseX, (int) mouseY)) {
			return false;
		}
		int y = panel.y() + 38;
		for (int i = 0; i < BoM.TREE_SLOT_COUNT && y < panel.y() + panel.height() - 24; i++) {
			SavedRecipeTree saved = BoM.getSavedTree(i);
			if (saved.isEmpty()) {
				continue;
			}
			Bounds row = new Bounds(panel.x() + 10, y, panel.width() - 20, 24);
			if (row.contains((int) mouseX, (int) mouseY)) {
				insertSavedTreeIntoPureRef(i);
				insertOverlayOpen = false;
				return true;
			}
			y += 28;
		}
		return true;
	}

	private boolean handleLibraryClick(double mouseX, double mouseY, int button) {
		if (!libraryOpen || button != 0) {
			return false;
		}
		Bounds panel = getLibraryPanelBounds();
		if (!panel.contains((int) mouseX, (int) mouseY)) {
			renamingSlot = -1;
			updateRenameField();
			return false;
		}
		if (renameField != null && renameField.isVisible()
			&& mouseX >= renameField.getX() && mouseX < renameField.getX() + renameField.getWidth()
			&& mouseY >= renameField.getY() && mouseY < renameField.getY() + renameField.getHeight()) {
			return false;
		}
		if (getLibraryHeaderButton(panel).contains((int) mouseX, (int) mouseY)) {
			compactLibrary = !compactLibrary;
			libraryScrollTarget = MathHelper.clamp(libraryScrollTarget, 0, getLibraryMaxScroll());
			libraryScroll = MathHelper.clamp(libraryScroll, 0, getLibraryMaxScroll());
			updateRenameField();
			return true;
		}
		int rowHeight = getLibraryRowHeight();
		int firstRow = Math.max(0, (int) Math.floor(libraryScroll));
		float rowOffset = libraryScroll - firstRow;
		int visibleRows = getLibraryVisibleRows();
		for (int i = 0; i < visibleRows + 1; i++) {
			int slot = firstRow + i;
			if (slot >= BoM.TREE_SLOT_COUNT) {
				break;
			}
			SavedRecipeTree saved = BoM.getSavedTree(slot);
			Bounds row = getLibraryRowBounds(panel, i);
			row = new Bounds(row.x(), row.y() - Math.round(rowOffset * rowHeight), row.width(), row.height());
			if (!row.contains((int) mouseX, (int) mouseY)) {
				continue;
			}
			LibraryRowButtons buttons = getLibraryRowButtons(row);
			if (buttons.save.contains((int) mouseX, (int) mouseY) && canSaveToSlot(saved)) {
				saveTreeToSlot(slot, false);
				selectedLibrarySlot = slot;
				return true;
			}
			if (buttons.rename.contains((int) mouseX, (int) mouseY) && !saved.isEmpty()) {
				selectedLibrarySlot = slot;
				renamingSlot = slot;
				updateRenameField();
				return true;
			}
			if (buttons.delete.contains((int) mouseX, (int) mouseY) && !saved.isEmpty()) {
				BoM.deleteSavedTree(slot);
				selectedLibrarySlot = Math.min(slot, BoM.TREE_SLOT_COUNT - 1);
				renamingSlot = -1;
				updateRenameField();
				return true;
			}
			if (buttons.override.contains((int) mouseX, (int) mouseY) && canOverrideSlot(saved)) {
				saveTreeToSlot(slot, true);
				selectedLibrarySlot = slot;
				return true;
			}
			long now = System.currentTimeMillis();
			selectedLibrarySlot = slot;
			if (!saved.isEmpty() && lastLibraryClickSlot == slot && now - lastLibraryClickTime < 250) {
				SavedRecipeTree.LoadResult result = BoM.loadSavedTree(slot);
				if (result.loaded) {
					applyLoadedTree(result.missingData);
				}
			}
			lastLibraryClickSlot = slot;
			lastLibraryClickTime = now;
			return true;
		}
		return true;
	}

	private void saveTreeToSlot(int slot, boolean override) {
		SavedRecipeTree existing = BoM.getSavedTree(slot);
		if (!override && !existing.isEmpty()) {
			return;
		}
		SavedRecipeTree.RecipeTreeSnapshot snapshot = createSnapshot();
		if (snapshot == null) {
			return;
		}
		String name = existing.isEmpty() ? getDefaultTreeName() : existing.name;
		BoM.saveTree(slot, new SavedRecipeTree(slot, name, getTreeThumbnail(), snapshot));
		selectedLibrarySlot = slot;
	}

	private Node getContextMenuNode() {
		if (contextMenuNodePath == null) {
			return null;
		}
		for (Node node : nodes) {
			if (contextMenuNodePath.equals(node.path)) {
				return node;
			}
		}
		contextMenuNodePath = null;
		return null;
	}

	private void openContextMenu(Node node, int mouseX, int mouseY) {
		contextMenuNodePath = node == null ? null : node.path;
		contextMenuX = mouseX;
		contextMenuY = mouseY;
	}

	private void closeContextMenu() {
		contextMenuNodePath = null;
	}

	private String getNodePath(MaterialNode node) {
		if (node == null) {
			return null;
		}
		for (Node renderNode : nodes) {
			if (renderNode.node == node) {
				return renderNode.path;
			}
		}
		return null;
	}

	private Set<String> captureOpenComparisonPaths(String excludedPath) {
		Set<String> paths = new HashSet<>();
		for (Node node : nodes) {
			if ((node.comparisonHead || node.node.hasComparisons())
				&& (excludedPath == null || !excludedPath.equals(node.path))) {
				paths.add(node.path);
			}
		}
		return paths;
	}

	private void restoreOpenComparisons(Set<String> openPaths) {
		if (BoM.tree == null || openPaths.isEmpty()) {
			return;
		}
		openPaths.stream()
			.sorted(Comparator.comparingInt(String::length))
			.forEach(path -> {
				MaterialNode node = findNodeByPath(BoM.tree.goal, "0", path);
				if (node != null && !node.hasComparisons()) {
					toggleComparison(node);
				}
			});
	}

	private MaterialNode findNodeByPath(MaterialNode node, String currentPath, String targetPath) {
		if (node == null) {
			return null;
		}
		if (currentPath.equals(targetPath)) {
			return node;
		}
		if (node.recipe != null && node.children != null && !node.children.isEmpty() && node.state == FoldState.EXPANDED) {
			if (node.recipe instanceof EmiResolutionRecipe) {
				return findNodeByPath(node.children.get(0), currentPath + "/0", targetPath);
			}
			for (int i = 0; i < node.children.size(); i++) {
				MaterialNode found = findNodeByPath(node.children.get(i), currentPath + "/" + i, targetPath);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	private Bounds getContextMenuBounds() {
		Node node = getContextMenuNode();
		if (node == null) {
			return new Bounds(0, 0, 0, 0);
		}
		int width = 120;
		int height = ContextAction.values().length * 20 + 4;
		int x = MathHelper.clamp(contextMenuX + 6, 6, width > this.width - 12 ? 6 : this.width - width - 6);
		int y = MathHelper.clamp(contextMenuY + 6, 24, height > this.height - 30 ? 24 : this.height - height - 6);
		return new Bounds(x, y, width, height);
	}

	private Bounds getContextActionBounds(Bounds menu, int index) {
		return new Bounds(menu.x() + 2, menu.y() + 2 + index * 20, menu.width() - 4, 18);
	}

	private String getContextLabel(ContextAction action) {
		return switch (action) {
			case COMPARE -> "Compare";
			case AUTO -> "Auto";
			case CLEAR -> "Clear";
			case RECIPES -> "Open Recipes";
			case FOLD -> "Fold";
			case UNFOLD -> "Unfold";
		};
	}

	private boolean isContextEnabled(ContextAction action, Node node) {
		if (node == null || node.node == null) {
			return false;
		}
		return switch (action) {
			case COMPARE -> node.node.ingredient.getEmiStacks().size() == 1;
			case AUTO -> {
				Hover hover = new Hover(node.node.ingredient, node.node, node.resolution, node);
				yield getAutoResolutions(hover, (stack, recipe) -> {
				});
			}
			case CLEAR -> node.node.recipe != null;
			case RECIPES -> true;
			case FOLD -> node.node.recipe != null && !(node.node.recipe instanceof EmiResolutionRecipe)
				&& node.node.state == FoldState.EXPANDED;
			case UNFOLD -> node.node.recipe != null && !(node.node.recipe instanceof EmiResolutionRecipe)
				&& node.node.state != FoldState.EXPANDED;
		};
	}

	private void renderContextMenu(EmiDrawContext context, int mouseX, int mouseY) {
		Node node = getContextMenuNode();
		if (node == null || node.comparisonCandidate) {
			return;
		}
		context.push();
		context.matrices().translate(0, 0, 600);
		RenderSystem.disableDepthTest();
		Bounds menu = getContextMenuBounds();
		context.fill(menu.x() - 1, menu.y() - 1, menu.width() + 2, menu.height() + 2, 0xAA0E141B);
		context.fill(menu.x(), menu.y(), menu.width(), menu.height(), 0xF11C2936);
		for (int i = 0; i < ContextAction.values().length; i++) {
			ContextAction action = ContextAction.values()[i];
			Bounds bounds = getContextActionBounds(menu, i);
			boolean active = isContextEnabled(action, node);
			int color = active ? (bounds.contains(mouseX, mouseY) ? 0xFF6E97BF : 0xFF355069) : 0xFF26303A;
			context.fill(bounds.x(), bounds.y(), bounds.width(), bounds.height(), color);
			context.drawCenteredText(EmiPort.literal(getContextLabel(action), active ? Formatting.WHITE : Formatting.DARK_GRAY),
				bounds.x() + bounds.width() / 2, bounds.y() + 5);
		}
		RenderSystem.enableDepthTest();
		context.pop();
	}

	private boolean handleContextMenuClick(int mouseX, int mouseY) {
		Node node = getContextMenuNode();
		if (node == null) {
			return false;
		}
		Bounds menu = getContextMenuBounds();
		if (!menu.contains(mouseX, mouseY)) {
			closeContextMenu();
			return false;
		}
		for (int i = 0; i < ContextAction.values().length; i++) {
			ContextAction action = ContextAction.values()[i];
			Bounds bounds = getContextActionBounds(menu, i);
			if (!bounds.contains(mouseX, mouseY) || !isContextEnabled(action, node)) {
				continue;
			}
			switch (action) {
				case COMPARE -> {
					if (toggleComparison(node.node)) {
						recalculateTree(node.path);
					}
				}
				case AUTO -> {
					Set<String> openComparisons = captureOpenComparisonPaths(null);
					Hover hover = new Hover(node.node.ingredient, node.node, node.resolution, node);
					if (getAutoResolutions(hover, BoM.tree::addResolution)) {
						restoreOpenComparisons(openComparisons);
						recalculateTree(node.path);
					}
				}
				case CLEAR -> {
					Set<String> openComparisons = captureOpenComparisonPaths(null);
					BoM.tree.addResolution(node.node.ingredient, null);
					node.node.clearComparisons();
					restoreOpenComparisons(openComparisons);
					recalculateTree(node.path);
				}
				case RECIPES -> {
					EmiApi.displayRecipes(node.node.ingredient);
					RecipeScreen.resolve = node.node.ingredient;
					MinecraftClient client = MinecraftClient.getInstance();
					client.currentScreen.init(client, client.currentScreen.width, client.currentScreen.height);
					if (node.node.recipe != null) {
						EmiApi.focusRecipe(node.node.recipe);
					}
				}
				case FOLD -> {
					node.node.state = FoldState.COLLAPSED;
					recalculateTree(node.path);
				}
				case UNFOLD -> {
					node.node.state = FoldState.EXPANDED;
					recalculateTree(node.path);
				}
			}
			closeContextMenu();
			return true;
		}
		return false;
	}

	@Override
	public void render(DrawContext raw, int mouseX, int mouseY, float delta) {
		if (viewMode == ViewMode.PURE_REF && focusedPureRefTree == null) {
			renderPureRefBoard(raw, mouseX, mouseY, delta);
			return;
		}
		EmiDrawContext context = EmiDrawContext.wrap(raw);
		this.renderBackgroundTexture(context.raw());
		lastMouseX = mouseX;
		lastMouseY = mouseY;
		float scale = getScale();
		int scaledWidth = (int) (width / scale);
		int scaledHeight = (int) (height / scale);
		// TODO should be the ingredient width if higher
		int contentWidth = nodeWidth * NODE_WIDTH;
		int contentHeight = nodeHeight * NODE_VERTICAL_SPACING + 80;
		int xBound = scaledWidth / 2 + contentWidth - 100;
		int topBound = scaledHeight * 1 / -2 + 20;
		int bottomBound = contentHeight + scaledHeight / 2 - 20;
		offX = MathHelper.clamp(offX, -xBound, xBound);
		offY = MathHelper.clamp(offY, -bottomBound, -topBound);

		int mx = (int) ((mouseX - width / 2) / scale - offX);
		int my = (int) ((mouseY - height / 2) / scale - offY);

		MatrixStack view = RenderSystem.getModelViewStack();
		view.push();
		view.translate(width / 2, height / 2, 0);
		view.scale(scale, scale, 1);
		view.translate(offX, offY, 0);
		EmiPort.applyModelViewMatrix();
		if (BoM.tree != null) {
			int cy = nodeHeight * NODE_VERTICAL_SPACING * 2;
			context.drawCenteredText(EmiPort.translatable("emi.total_cost"), 0, cy - 16);
			if (hasRemainders) {
				context.drawCenteredText(EmiPort.translatable("emi.leftovers"), 0, cy - 16 + 40);
			}
			for (Cost cost : costs) {
				cost.render(context);
			}
			for (Node node : nodes) {
				node.render(context, mx, my, delta);
			}
			int color = -1;
			if (batches.contains(mx, my)) {
				color = 0xff8099ff;
			}
			context.drawTextWithShadow(EmiPort.literal("x" + BoM.tree.batches),
					batches.x() + 6, batches.y() + batches.height() / 2 - 4, color);

			if (mode.contains(mx, my)) {
				context.setColor(0.5f, 0.6f, 1f, 1f);
			}
			context.drawTexture(EmiRenderHelper.WIDGETS, mode.x(), mode.y(), BoM.craftingMode ? 16 : 0, 146, mode.width(), mode.height());
			context.setColor(1f, 1f, 1f, 1f);
		} else {
			context.drawCenteredText(EmiPort.translatable("emi.tree_welcome", EmiRenderHelper.getEmiText()), 0, -72);
			context.drawCenteredText(EmiPort.translatable("emi.no_tree"), 0, -48);
			context.drawCenteredText(EmiPort.translatable("emi.random_tree"), 0, -24);
			context.drawCenteredText(EmiPort.translatable("emi.random_tree_input"), 0, 0);
		}

		view.pop();
		EmiPort.applyModelViewMatrix();

		if (help.contains(mouseX, mouseY)) {
			context.setColor(0.5f, 0.6f, 1f, 1f);
		}
		context.drawTexture(EmiRenderHelper.WIDGETS, help.x(), help.y(), 0, 200, help.width(), help.height());
		context.setColor(1f, 1f, 1f, 1f);
		if (loadWarning) {
			context.drawTextWithShadow(EmiPort.literal("Loaded recipe tree with missing data", Formatting.YELLOW), 8, 34, -1);
		}
		if (focusedPureRefTree != null) {
			context.drawTextWithShadow(EmiPort.literal("Editing embedded tree  |  Back To Board saves changes to the Board View project", Formatting.DARK_GRAY),
				8, height - 42, -1);
		}
		context.drawTextWithShadow(EmiPort.literal("RMB node: menu  |  LMB drag: move node  |  Ctrl+LMB drag: move branch  |  MMB drag background: pan", Formatting.DARK_GRAY),
			8, height - 28, -1);
		if (libraryOpen && viewMode == ViewMode.TREE) {
			libraryScroll += (libraryScrollTarget - libraryScroll) * 0.35f;
			if (Math.abs(libraryScrollTarget - libraryScroll) < 0.01f) {
				libraryScroll = libraryScrollTarget;
			}
			updateRenameField();
			renderLibraryOverlay(context, raw, mouseX, mouseY, delta);
		}
		super.render(raw, mouseX, mouseY, delta);

		Hover hover = getHoveredStack(mouseX, mouseY);
		if (getContextMenuNode() != null) {
			renderContextMenu(context, mouseX, mouseY);
		} else if (hover != null) {
			hover.drawTooltip(this, context, mouseX, mouseY);
		} else if (BoM.tree != null && batches.contains(mx, my)) {
			List<TooltipComponent> list = Lists.newArrayList();
			list.addAll(EmiTooltip.splitTranslate("tooltip.emi.bom.batch_size", BoM.tree.batches));
			list.add(EmiTooltipComponents.of(EmiPort.translatable("tooltip.emi.bom.batch_size.ideal", EmiBind.LEFT_CLICK.getBindText())));
			EmiRenderHelper.drawTooltip(this, context, list, mouseX, mouseY);
		} else if (BoM.tree != null && mode.contains(mx, my)) {
			String key = BoM.craftingMode ? "tooltip.emi.bom.mode.craft" : "tooltip.emi.bom.mode.view";
			List<TooltipComponent> list = EmiTooltip.splitTranslate(key, BoM.tree.batches);
			EmiRenderHelper.drawTooltip(this, context, list, mouseX, mouseY);
		} else if (help.contains(mouseX, mouseY)) {
			List<TooltipComponent> list = Lists.newArrayList();
			list.add(EmiTooltipComponents.of(EmiPort.literal("Tree controls", Formatting.WHITE)));
			list.add(EmiTooltipComponents.of(EmiPort.literal("Right click node: open menu", Formatting.GRAY)));
			list.add(EmiTooltipComponents.of(EmiPort.literal("Left drag: move node", Formatting.GRAY)));
			list.add(EmiTooltipComponents.of(EmiPort.literal("Ctrl + Left drag: move branch", Formatting.GRAY)));
			list.add(EmiTooltipComponents.of(EmiPort.literal("Middle drag on background: pan view", Formatting.GRAY)));
			list.add(EmiTooltipComponents.of(EmiPort.literal("Left click compare candidate: select recipe", Formatting.GRAY)));
			EmiRenderHelper.drawTooltip(this, context, list, width - 18, height - 18, width);
		}
	}

	public Hover getHoveredStack(int mx, int my) {
		float scale = getScale();
		mx = (int) ((mx - width / 2) / scale - offX);
		my = (int) ((my - height / 2) / scale - offY);
		for (Cost cost : costs) {
			if (mx >= cost.x && mx < cost.x + 16 && my >= cost.y && my < cost.y + 16) {
				return new Hover(cost.cost.ingredient);
			}
		}
		for (Node node : nodes) {
			Hover hover = node.getHover(mx, my);
			if (hover != null) {
				return hover;
			}
		}
		return null;
	}

	private record NodePosition(int x, int y, String structureKey) {
	}

	private record LibraryRowButtons(Bounds save, Bounds rename, Bounds delete, Bounds override) {
	}

	private enum ContextAction {
		COMPARE,
		AUTO,
		CLEAR,
		RECIPES,
		FOLD,
		UNFOLD
	}

	private enum ViewMode {
		TREE,
		PURE_REF
	}

	private enum PureRefTool {
		SELECT("Select"),
		NOTE("Note"),
		CHECKLIST("Checklist"),
		LINE("Line"),
		ARROW("Arrow"),
		BOX("Box");

		private final String label;

		PureRefTool(String label) {
			this.label = label;
		}
	}

	private enum ShapeHandle {
		NONE,
		MOVE,
		START,
		END,
		TOP_LEFT,
		TOP_RIGHT,
		BOTTOM_LEFT,
		BOTTOM_RIGHT
	}

	public int getNodeHeight(MaterialNode node) {
		if (node.hasComparisons()) {
			int i = 1;
			for (MaterialNode.Comparison comparison : node.comparisons) {
				i = Math.max(i, getNodeHeight(comparison.node));
			}
			return i + 1;
		}
		if (node.recipe != null && node.state == FoldState.EXPANDED) {
			int i = 1;
			for (MaterialNode n : node.children) {
				i = Math.max(i, getNodeHeight(n));
			}
			if (node.recipe instanceof EmiResolutionRecipe) {
				return i;
			}
			return i + 1;
		}
		return 1;
	}

	public TreeVolume addNewNodes(MaterialNode node, long multiplier, long divisor, int depth, ChanceState chance, String path, int outlineColor, int colorSeed) {
		if (node.catalyst) {
			multiplier = node.amount;
		} else {
			multiplier = node.amount * (int) Math.ceil(multiplier / (float) divisor);
		}
		if (node.hasComparisons()) {
			List<TreeVolume> comparisonVolumes = Lists.newArrayList();
			for (int i = 0; i < node.comparisons.size(); i++) {
				MaterialNode.Comparison comparison = node.comparisons.get(i);
				int childColor = getBranchColor(path + "/cmp", i, colorSeed);
				TreeVolume volume = addNewNodes(comparison.node, multiplier, comparison.node.divisor, depth + 1, chance,
					path + "/@cmp/" + i, childColor, colorSeed + i + 1);
				if (!volume.nodes.isEmpty()) {
					volume.nodes.get(0).comparisonCandidate = true;
					volume.nodes.get(0).compareOwner = node;
					volume.nodes.get(0).comparisonCost = comparison.estimatedCost;
					volume.nodes.get(0).comparisonSelected = comparison.selected;
				}
				comparisonVolumes.add(volume);
			}
			if (!comparisonVolumes.isEmpty()) {
				TreeVolume combined = TreeVolume.combineComparisonLanes(comparisonVolumes, COMPARISON_HORIZONTAL_SPACING);
				combined.addHead(node, multiplier, depth * NODE_VERTICAL_SPACING, chance, path, outlineColor);
				if (!combined.nodes.isEmpty()) {
					combined.nodes.get(0).comparisonHead = true;
				}
				return combined;
			}
		}
		if (node.recipe != null && node.children.size() > 0 && node.state == FoldState.EXPANDED) {
			ChanceState produced = chance.produce(node.produceChance);
			if (node.recipe instanceof EmiResolutionRecipe) {
				TreeVolume volume = addNewNodes(node.children.get(0), multiplier, node.divisor, depth, produced, path + "/0", outlineColor, colorSeed);
				volume.nodes.get(0).resolution = node;
				return volume;
			}
			TreeVolume left = null;
			for (int i = 0; i < node.children.size(); i++) {
				ChanceState consumed = produced.consume(node.children.get(i).consumeChance);
				int childColor = getBranchColor(path, i, colorSeed);
				TreeVolume volume = addNewNodes(node.children.get(i), multiplier, node.divisor, depth + 1, consumed, path + "/" + i, childColor, colorSeed + i + 1);
				if (left == null) {
					left = volume;
				} else {
					left.addToRight(volume);
				}
			}
			left.addHead(node, multiplier, depth * NODE_VERTICAL_SPACING, chance, path, outlineColor);
			return left;
		}
		return new TreeVolume(node, multiplier, depth * NODE_VERTICAL_SPACING, chance, path, outlineColor);
	}

	private int getBranchColor(String path, int childIndex, int seed) {
		int[] palette = {
			0xFFF2B5D4,
			0xFFBEE3DB,
			0xFFF7D6A3,
			0xFFD0C7F2,
			0xFFF3C4A7,
			0xFFC1D7F0,
			0xFFCDE7B0,
			0xFFF1BFCB
		};
		int hash = Math.abs(path.hashCode() + seed * 31 + childIndex * 17);
		return palette[hash % palette.length];
	}

	private static void drawLine(EmiDrawContext context, int x1, int y1, int x2, int y2) {
		if (x2 < x1) {
			drawLine(context, x2, y1, x1, y2);
			return;
		}
		if (y2 < y1) {
			drawLine(context, x1, y2, x2, y1);
			return;
		}
		context.fill(x1, y1, x2 - x1 + 1, y2 - y1 + 1, 0xFFFFFFFF);
	}

	public float getScale() {
		zoom = MathHelper.clamp(zoom, -6, 4);
		int scale = (int) this.client.getWindow().getScaleFactor();
		int desired = scale + zoom;
		if (desired < 1) {
			zoom -= desired - 1;
			desired = 1;
		}
		return (float) desired / scale;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (viewMode == ViewMode.PURE_REF && focusedPureRefTree == null) {
			if (projectRenameField != null && projectRenameField.isVisible() && projectRenameField.isFocused()
				&& projectRenameField.keyPressed(keyCode, scanCode, modifiers)) {
				return true;
			}
			if (editingCheckList != null) {
				if ((checklistTitleField.isFocused() && checklistTitleField.keyPressed(keyCode, scanCode, modifiers))
					|| (checklistLabelField.isFocused() && checklistLabelField.keyPressed(keyCode, scanCode, modifiers))
					|| (checklistCurrentField.isFocused() && checklistCurrentField.keyPressed(keyCode, scanCode, modifiers))
					|| (checklistTargetField.isFocused() && checklistTargetField.keyPressed(keyCode, scanCode, modifiers))) {
					return true;
				}
			}
			if (editingNote != null && handleInlineNoteKeyPressed(keyCode, scanCode, modifiers)) {
				return true;
			}
			if (editingNote != null) {
				if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
					insertInlineNoteLineBreak();
					return true;
				} else if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
					closeNoteEditor();
					selectedPureRefObject = null;
					pureRefTool = PureRefTool.SELECT;
					return true;
				}
			}
			if (editingCheckList != null && keyCode == GLFW.GLFW_KEY_ESCAPE) {
				closeCheckListEditor();
				selectedPureRefObject = null;
				pureRefTool = PureRefTool.SELECT;
				return true;
			}
			if (projectRenameField != null && projectRenameField.isVisible()) {
				if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
					commitProjectRename();
					return true;
				} else if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
					renamingProjectSlot = -1;
					updateProjectRenameField();
					return true;
				}
			}
			if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
				if (insertOverlayOpen) {
					insertOverlayOpen = false;
					return true;
				}
				if (projectLibraryOpen) {
					projectLibraryOpen = false;
					renamingProjectSlot = -1;
					updateProjectRenameField();
					return true;
				}
				if (editingNote != null) {
					closeNoteEditor();
					return true;
				}
				if (pendingTreeInsert != null) {
					pendingTreeInsert = null;
					return true;
				}
				this.close();
				return true;
			}
			if (isPureRefTextInputFocused()) {
				return true;
			}
			if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
				&& selectedPureRefObject instanceof PureRefProject.NoteObject note) {
				return true;
			}
			if (keyCode == GLFW.GLFW_KEY_DELETE && selectedPureRefObject != null) {
				if (selectedPureRefObject instanceof PureRefProject.NoteObject note) {
					if (editingNote != null) {
						commitNoteEditor();
					}
					pendingNoteDelete = note;
				} else {
					project().objects.remove(selectedPureRefObject);
					selectedPureRefObject = null;
					markBoardDirty();
				}
				return true;
			}
			if (EmiInput.isControlDown() && keyCode == GLFW.GLFW_KEY_D && selectedPureRefObject != null) {
				PureRefProject.Object copy = selectedPureRefObject.copy();
				copy.x += 18;
				copy.y += 18;
				project().objects.add(copy);
				selectedPureRefObject = copy;
				markBoardDirty();
				return true;
			}
			if (keyCode == GLFW.GLFW_KEY_1) {
				pureRefTool = PureRefTool.SELECT;
				return true;
			} else if (keyCode == GLFW.GLFW_KEY_2) {
				pureRefTool = PureRefTool.NOTE;
				return true;
			} else if (keyCode == GLFW.GLFW_KEY_3) {
				pureRefTool = PureRefTool.CHECKLIST;
				return true;
			} else if (keyCode == GLFW.GLFW_KEY_4) {
				pureRefTool = PureRefTool.LINE;
				return true;
			} else if (keyCode == GLFW.GLFW_KEY_5) {
				pureRefTool = PureRefTool.ARROW;
				return true;
			} else if (keyCode == GLFW.GLFW_KEY_6) {
				pureRefTool = PureRefTool.BOX;
				return true;
			}
			return super.keyPressed(keyCode, scanCode, modifiers);
		}
		if (focusedPureRefTree != null && keyCode == GLFW.GLFW_KEY_ESCAPE) {
			exitPureRefTreeFocus(true);
			return true;
		}
		if (renameField != null && renameField.isVisible()) {
			if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
				commitRename();
				return true;
			} else if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
				renamingSlot = -1;
				updateRenameField();
				return true;
			}
		}
		if (libraryOpen && keyCode == GLFW.GLFW_KEY_TAB) {
			libraryOpen = false;
			renamingSlot = -1;
			updateRenameField();
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
			if (libraryOpen) {
				libraryOpen = false;
				renamingSlot = -1;
				updateRenameField();
				return true;
			}
			this.close();
			return true;
		} else if (this.client.options.inventoryKey.matchesKey(keyCode, scanCode)) {
			this.close();
			return true;
		}
		Function<EmiBind, Boolean> function = bind -> bind.matchesKey(keyCode, scanCode);
		if (function.apply(EmiConfig.back)) {
			EmiHistory.pop();
			return true;
		}
		Hover hover = getHoveredStack(lastMouseX, lastMouseY);
		if (hover != null && hover.stack != null && !hover.stack.isEmpty()) {
			if (function.apply(EmiConfig.favorite)) {
				EmiFavorites.addFavorite(hover.stack, hover.node == null ? null : hover.node.recipe);
			}
		}
		if (EmiInput.isControlDown() && keyCode == GLFW.GLFW_KEY_R) {
			List<EmiRecipe> recipes = EmiApi.getRecipeManager().getRecipes();
			if (recipes.size() > 0) {
				for (int i = 0; i < 100_000; i++) {
					EmiRecipe recipe = recipes.get(EmiUtil.RANDOM.nextInt(recipes.size()));
					if (recipe.supportsRecipeTree()) {
						BoM.setGoal(recipe);
						init();
						return true;
					}
				}
			}
		} else if (EmiInput.isControlDown() && keyCode == GLFW.GLFW_KEY_C) {
			BoM.tree = null;
			init();
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean charTyped(char chr, int modifiers) {
		if (viewMode == ViewMode.PURE_REF && focusedPureRefTree == null) {
			if (projectRenameField != null && projectRenameField.isVisible() && projectRenameField.isFocused()
				&& projectRenameField.charTyped(chr, modifiers)) {
				return true;
			}
			if (editingCheckList != null) {
				if ((checklistTitleField.isFocused() && checklistTitleField.charTyped(chr, modifiers))
					|| (checklistLabelField.isFocused() && checklistLabelField.charTyped(chr, modifiers))
					|| (checklistCurrentField.isFocused() && checklistCurrentField.charTyped(chr, modifiers))
					|| (checklistTargetField.isFocused() && checklistTargetField.charTyped(chr, modifiers))) {
					return true;
				}
			}
			if (editingNote != null && handleInlineNoteCharTyped(chr)) {
				return true;
			}
		}
		if (renameField != null && renameField.isVisible() && renameField.isFocused() && renameField.charTyped(chr, modifiers)) {
			return true;
		}
		return super.charTyped(chr, modifiers);
	}

	private boolean isPureRefTextInputFocused() {
		return projectRenameField != null && projectRenameField.isVisible() && projectRenameField.isFocused()
			|| editingCheckList != null && (
				checklistTitleField.isFocused()
				|| checklistLabelField.isFocused()
				|| checklistCurrentField.isFocused()
				|| checklistTargetField.isFocused())
			|| editingNote != null;
	}

	private boolean handleInlineNoteCharTyped(char chr) {
		if (editingNote == null || Character.isISOControl(chr)) {
			return false;
		}
		String line = editingNoteLines.get(editingNoteCursorLine);
		editingNoteLines.set(editingNoteCursorLine,
			line.substring(0, editingNoteCursorColumn) + chr + line.substring(editingNoteCursorColumn));
		editingNoteCursorColumn++;
		normalizeInlineNoteWrapping();
		syncEditingNoteToObject();
		return true;
	}

	private boolean handleInlineNoteKeyPressed(int keyCode, int scanCode, int modifiers) {
		if (editingNote == null) {
			return false;
		}
		boolean ctrl = Screen.hasControlDown();
		switch (keyCode) {
			case GLFW.GLFW_KEY_BACKSPACE -> {
				if (ctrl) {
					deleteInlineNoteToPreviousWord();
				} else if (editingNoteCursorColumn > 0) {
					String line = editingNoteLines.get(editingNoteCursorLine);
					editingNoteLines.set(editingNoteCursorLine,
						line.substring(0, editingNoteCursorColumn - 1) + line.substring(editingNoteCursorColumn));
					editingNoteCursorColumn--;
				} else if (editingNoteCursorLine > 0) {
					String current = editingNoteLines.remove(editingNoteCursorLine);
					editingNoteCursorLine--;
					String previous = editingNoteLines.get(editingNoteCursorLine);
					editingNoteCursorColumn = previous.length();
					editingNoteLines.set(editingNoteCursorLine, previous + current);
				}
				normalizeInlineNoteWrapping();
				syncEditingNoteToObject();
				return true;
			}
			case GLFW.GLFW_KEY_DELETE -> {
				if (ctrl) {
					deleteInlineNoteToNextWord();
				} else {
					String line = editingNoteLines.get(editingNoteCursorLine);
					if (editingNoteCursorColumn < line.length()) {
						editingNoteLines.set(editingNoteCursorLine,
							line.substring(0, editingNoteCursorColumn) + line.substring(editingNoteCursorColumn + 1));
					} else if (editingNoteCursorLine < editingNoteLines.size() - 1) {
						String next = editingNoteLines.remove(editingNoteCursorLine + 1);
						editingNoteLines.set(editingNoteCursorLine, line + next);
					}
				}
				normalizeInlineNoteWrapping();
				syncEditingNoteToObject();
				return true;
			}
			case GLFW.GLFW_KEY_LEFT -> {
				if (ctrl) {
					moveInlineNoteCursorToPreviousWord();
				} else if (editingNoteCursorColumn > 0) {
					editingNoteCursorColumn--;
				} else if (editingNoteCursorLine > 0) {
					editingNoteCursorLine--;
					editingNoteCursorColumn = editingNoteLines.get(editingNoteCursorLine).length();
				}
				return true;
			}
			case GLFW.GLFW_KEY_RIGHT -> {
				if (ctrl) {
					moveInlineNoteCursorToNextWord();
				} else {
					String line = editingNoteLines.get(editingNoteCursorLine);
					if (editingNoteCursorColumn < line.length()) {
						editingNoteCursorColumn++;
					} else if (editingNoteCursorLine < editingNoteLines.size() - 1) {
						editingNoteCursorLine++;
						editingNoteCursorColumn = 0;
					}
				}
				return true;
			}
			case GLFW.GLFW_KEY_UP -> {
				if (editingNoteCursorLine > 0) {
					editingNoteCursorLine--;
					editingNoteCursorColumn = Math.min(editingNoteCursorColumn, editingNoteLines.get(editingNoteCursorLine).length());
				}
				return true;
			}
			case GLFW.GLFW_KEY_DOWN -> {
				if (editingNoteCursorLine < editingNoteLines.size() - 1) {
					editingNoteCursorLine++;
					editingNoteCursorColumn = Math.min(editingNoteCursorColumn, editingNoteLines.get(editingNoteCursorLine).length());
				}
				return true;
			}
			case GLFW.GLFW_KEY_HOME -> {
				editingNoteCursorColumn = 0;
				return true;
			}
			case GLFW.GLFW_KEY_END -> {
				editingNoteCursorColumn = editingNoteLines.get(editingNoteCursorLine).length();
				return true;
			}
		}
		return false;
	}

	private void moveInlineNoteCursorToPreviousWord() {
		if (editingNoteCursorColumn == 0) {
			if (editingNoteCursorLine > 0) {
				editingNoteCursorLine--;
				editingNoteCursorColumn = editingNoteLines.get(editingNoteCursorLine).length();
			}
			return;
		}
		String line = editingNoteLines.get(editingNoteCursorLine);
		editingNoteCursorColumn = findPreviousWordBoundary(line, editingNoteCursorColumn);
	}

	private void moveInlineNoteCursorToNextWord() {
		String line = editingNoteLines.get(editingNoteCursorLine);
		if (editingNoteCursorColumn >= line.length()) {
			if (editingNoteCursorLine < editingNoteLines.size() - 1) {
				editingNoteCursorLine++;
				editingNoteCursorColumn = 0;
			}
			return;
		}
		editingNoteCursorColumn = findNextWordBoundary(line, editingNoteCursorColumn);
	}

	private void deleteInlineNoteToPreviousWord() {
		if (editingNoteCursorColumn > 0) {
			String line = editingNoteLines.get(editingNoteCursorLine);
			int boundary = findPreviousWordBoundary(line, editingNoteCursorColumn);
			editingNoteLines.set(editingNoteCursorLine,
				line.substring(0, boundary) + line.substring(editingNoteCursorColumn));
			editingNoteCursorColumn = boundary;
		} else if (editingNoteCursorLine > 0) {
			String current = editingNoteLines.remove(editingNoteCursorLine);
			editingNoteCursorLine--;
			String previous = editingNoteLines.get(editingNoteCursorLine);
			editingNoteCursorColumn = previous.length();
			editingNoteLines.set(editingNoteCursorLine, previous + current);
		}
	}

	private void deleteInlineNoteToNextWord() {
		String line = editingNoteLines.get(editingNoteCursorLine);
		if (editingNoteCursorColumn < line.length()) {
			int boundary = findNextWordBoundary(line, editingNoteCursorColumn);
			editingNoteLines.set(editingNoteCursorLine,
				line.substring(0, editingNoteCursorColumn) + line.substring(boundary));
		} else if (editingNoteCursorLine < editingNoteLines.size() - 1) {
			String next = editingNoteLines.remove(editingNoteCursorLine + 1);
			editingNoteLines.set(editingNoteCursorLine, line + next);
		}
	}

	private int findPreviousWordBoundary(String line, int column) {
		int index = Math.max(0, Math.min(column, line.length()));
		while (index > 0 && !isWordChar(line.charAt(index - 1))) {
			index--;
		}
		while (index > 0 && isWordChar(line.charAt(index - 1))) {
			index--;
		}
		return index;
	}

	private int findNextWordBoundary(String line, int column) {
		int index = Math.max(0, Math.min(column, line.length()));
		while (index < line.length() && !isWordChar(line.charAt(index))) {
			index++;
		}
		while (index < line.length() && isWordChar(line.charAt(index))) {
			index++;
		}
		return index;
	}

	private boolean isWordChar(char c) {
		return Character.isLetterOrDigit(c) || c == '_';
	}

	private void insertInlineNoteLineBreak() {
		if (editingNote == null) {
			return;
		}
		String line = editingNoteLines.get(editingNoteCursorLine);
		String before = line.substring(0, editingNoteCursorColumn);
		String after = line.substring(editingNoteCursorColumn);
		editingNoteLines.set(editingNoteCursorLine, before);
		editingNoteLines.add(editingNoteCursorLine + 1, after);
		editingNoteCursorLine++;
		editingNoteCursorColumn = 0;
		normalizeInlineNoteWrapping();
		syncEditingNoteToObject();
	}

	private boolean getAutoResolutions(Hover hover, BiConsumer<EmiIngredient, EmiRecipe> consumer) {
		EmiPlayerInventory inv = playerInv;
		if (inv != null) {
			List<EmiStack> stacks = hover.stack.getEmiStacks();
			if (stacks.size() > 1) {
				for (EmiStack stack : stacks) {
					if (inv.inventory.containsKey(stack)) {
						consumer.accept(hover.stack, new EmiResolutionRecipe(hover.stack, stack));
						return true;
					}
				}
				for (EmiStack stack : stacks) {
					for (Cost cost : costs) {
						if (cost.cost.ingredient.equals(stack)) {
							consumer.accept(hover.stack, new EmiResolutionRecipe(hover.stack, stack));
							return true;
						}
					}
				}
				consumer.accept(hover.stack, new EmiResolutionRecipe(hover.stack, stacks.get(0)));
				return true;
			} else {
				EmiRecipe recipe = EmiUtil.getRecipeResolution(hover.stack, inv);
				if (recipe != null) {
					consumer.accept(hover.stack, recipe);
					return true;
				}
			}
		}
		return false;
	}

	private boolean toggleComparison(MaterialNode node) {
		if (BoM.tree == null || node == null || node.ingredient.getEmiStacks().size() != 1) {
			return false;
		}
		if (node.hasComparisons()) {
			node.clearComparisons();
			return true;
		}
		EmiStack stack = node.ingredient.getEmiStacks().get(0);
		List<EmiRecipe> recipes = EmiApi.getRecipeManager().getRecipesByOutput(stack).stream()
			.filter(EmiRecipe::supportsRecipeTree)
			.filter(r -> r.getOutputs().stream().anyMatch(o -> o.isEqual(stack)))
			.distinct()
			.toList();
		if (recipes.size() <= 1) {
			return false;
		}
		EmiRecipe selected = node.recipe;
		node.comparisons = recipes.stream()
			.map(r -> {
				MaterialNode comparisonNode = BoM.tree.createComparisonNode(node, r);
				long estimatedCost = BoM.tree.estimateCost(comparisonNode);
				boolean isSelected = selected != null && selected.equals(r);
				return new MaterialNode.Comparison(r, comparisonNode, estimatedCost, isSelected);
			})
			.sorted(Comparator
				.comparingLong((MaterialNode.Comparison c) -> c.estimatedCost)
				.thenComparingInt(c -> EmiRecipeCategoryProperties.getOrder(c.recipe.getCategory())))
			.collect(Collectors.toList());
		if (node.comparisons.stream().noneMatch(c -> c.selected) && !node.comparisons.isEmpty()) {
			node.comparisons.get(0).selected = true;
		}
		return true;
	}

	private boolean selectComparison(MaterialNode compareOwner, EmiRecipe recipe) {
		if (BoM.tree == null || compareOwner == null || recipe == null) {
			return false;
		}
		String ownerPath = getNodePath(compareOwner);
		Set<String> openComparisons = captureOpenComparisonPaths(ownerPath);
		BoM.tree.addResolution(compareOwner.ingredient, recipe);
		compareOwner.clearComparisons();
		restoreOpenComparisons(openComparisons);
		return true;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (viewMode == ViewMode.PURE_REF && focusedPureRefTree == null) {
			if (handleBoardContextMenuClick((int) mouseX, (int) mouseY, button)) {
				return true;
			}
			if (handleCheckListEditorClick((int) mouseX, (int) mouseY, button)) {
				return true;
			}
			if (button == 0 && pendingNoteDelete != null) {
				if (getConfirmNoteDeleteBounds().contains((int) mouseX, (int) mouseY)) {
					project().objects.remove(pendingNoteDelete);
					if (selectedPureRefObject == pendingNoteDelete) {
						selectedPureRefObject = null;
					}
					if (editingNote == pendingNoteDelete) {
						closeNoteEditor();
					}
					pendingNoteDelete = null;
					pureRefTool = PureRefTool.SELECT;
					markBoardDirty();
					return true;
				}
				if (getCancelNoteDeleteBounds().contains((int) mouseX, (int) mouseY)) {
					pendingNoteDelete = null;
					return true;
				}
				pendingNoteDelete = null;
			}
			if (handleProjectOverlayClick(mouseX, mouseY, button)) {
				return true;
			}
			if (handleInsertOverlayClick(mouseX, mouseY, button)) {
				return true;
			}
			if (editingNote != null && button == 0) {
				int cx = getPureRefCanvasX(mouseX);
				int cy = getPureRefCanvasY(mouseY);
				if (getPureRefNoteBounds(editingNote).contains(cx, cy)) {
					setInlineNoteCursor(editingNote, cx, cy);
				} else {
					commitNoteEditor();
					selectedPureRefObject = null;
					pureRefTool = PureRefTool.SELECT;
				}
				return true;
			}
			if (super.mouseClicked(mouseX, mouseY, button)) {
				return true;
			}
			for (int i = 0, x = 8; i < PureRefTool.values().length; i++, x += 78) {
				Bounds bounds = new Bounds(x, 36, 74, 18);
				if (bounds.contains((int) mouseX, (int) mouseY) && button == 0) {
					pureRefTool = PureRefTool.values()[i];
					return true;
				}
			}
			int cx = getPureRefCanvasX(mouseX);
			int cy = getPureRefCanvasY(mouseY);
			if (button == 0 && pendingTreeInsert != null) {
				pendingTreeInsert.x = cx - pendingTreeInsert.width / 2;
				pendingTreeInsert.y = cy - pendingTreeInsert.height / 2;
				project().objects.add(pendingTreeInsert);
				selectedPureRefObject = pendingTreeInsert;
				lastSelectedBoardTreeId = pendingTreeInsert.id;
				pendingTreeInsert = null;
				markBoardDirty();
				return true;
			}
			if (button == 2) {
				closeBoardContextMenu();
				pureRefPanning = true;
				pureRefDragLastX = cx;
				pureRefDragLastY = cy;
				return true;
			}
			if (button == 1) {
				if (editingNote != null) {
					commitNoteEditor();
				}
				PureRefProject.Object object = getPureRefObjectAt(cx, cy);
				if (object instanceof PureRefProject.NoteObject || object instanceof PureRefProject.ShapeObject
					|| object instanceof PureRefProject.CheckListObject || object instanceof PureRefProject.TreeObject) {
					selectedPureRefObject = object;
					openBoardContextMenu(object, (int) mouseX, (int) mouseY);
					return true;
				}
				closeBoardContextMenu();
			}
			if (button == 0) {
				pureRefObjectMoved = false;
				if (pureRefTool == PureRefTool.NOTE) {
					PureRefProject.NoteObject note = new PureRefProject.NoteObject(PureRefProject.nextObjectId(), cx, cy, 220, 10, "", "New note", 0);
					project().objects.add(note);
					selectedPureRefObject = note;
					markBoardDirty();
					return true;
				}
				if (pureRefTool == PureRefTool.CHECKLIST) {
					PureRefProject.CheckListObject checkList = new PureRefProject.CheckListObject(PureRefProject.nextObjectId(), cx, cy, 260, 160, "Checklist", null, false, Lists.newArrayList());
					checkList.entries.add(new PureRefProject.CheckListEntry(PureRefProject.nextCheckListEntryId(), "Item", 0, 0, null, null));
					project().objects.add(checkList);
					selectedPureRefObject = checkList;
					markBoardDirty();
					return true;
				}
				if (pureRefTool == PureRefTool.LINE || pureRefTool == PureRefTool.ARROW || pureRefTool == PureRefTool.BOX) {
					beginShapeDraft(cx, cy);
					pureRefDragLastX = cx;
					pureRefDragLastY = cy;
					return true;
				}
				PureRefProject.Object object = getPureRefObjectAt(cx, cy);
				selectedPureRefObject = object;
				closeBoardContextMenu();
				pendingNoteDelete = null;
				editingShape = null;
				activeShapeHandle = ShapeHandle.NONE;
				if (object instanceof PureRefProject.TreeObject tree) {
					lastSelectedBoardTreeId = tree.id;
				}
				if (object instanceof PureRefProject.NoteObject note && isNoteResizeHandle(note, cx, cy)) {
					pureRefResizingNote = true;
				} else if (object instanceof PureRefProject.TreeObject tree && isTreeResizeHandle(tree, cx, cy)) {
					pureRefResizingTree = true;
				} else if (object instanceof PureRefProject.CheckListObject checkList && isCheckListResizeHandle(checkList, cx, cy)) {
					pureRefResizingCheckList = true;
				} else if (object instanceof PureRefProject.ShapeObject shape) {
					editingShape = shape;
					activeShapeHandle = getShapeHandle(shape, cx, cy);
					pureRefDraggingObject = activeShapeHandle != ShapeHandle.NONE;
				} else if (object != null) {
					pureRefDraggingObject = true;
				}
				pureRefDragLastX = cx;
				pureRefDragLastY = cy;
				lastPureRefClickId = object == null ? null : object.id;
				lastPureRefClickTime = System.currentTimeMillis();
				return true;
			}
			return super.mouseClicked(mouseX, mouseY, button);
		}
		if (handleLibraryClick(mouseX, mouseY, button)) {
			return true;
		}
		if (button == 0 && handleContextMenuClick((int) mouseX, (int) mouseY)) {
			return true;
		}
		float scale = getScale();
		int mx = (int) ((mouseX - width / 2) / scale - offX);
		int my = (int) ((mouseY - height / 2) / scale - offY);
		Hover hover = getHoveredStack((int) mouseX, (int) mouseY);
		if (hover != null) {
			if (button == 1 && hover.node != null) {
				openContextMenu(hover.renderNode, (int) mouseX, (int) mouseY);
				return true;
			}
			if (button == 0 && hover.renderNode != null) {
				closeContextMenu();
				draggedNode = hover.renderNode;
				draggingBranch = EmiInput.isControlDown();
				draggedNodeMoved = false;
				dragLastTreeX = mx;
				dragLastTreeY = my;
				return true;
			}
			closeContextMenu();
		} else if (mode.contains(mx, my)) {
			closeContextMenu();
			MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0f));
			BoM.craftingMode = !BoM.craftingMode;
			recalculateTree();
		} else if (batches.contains(mx, my) && BoM.tree != null) {
			closeContextMenu();
			long ideal = BoM.tree.cost.getIdealBatch(BoM.tree.goal, 1, 1);
			if (ideal != BoM.tree.batches) {
				MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0f));
				BoM.tree.batches = ideal;
				recalculateTree();
			}
		} else if (button == 2) {
			closeContextMenu();
			panningView = true;
			dragLastTreeX = mx;
			dragLastTreeY = my;
			return true;
		} else if (button == 0 || button == 1 || button == 2) {
			closeContextMenu();
		}
		Function<EmiBind, Boolean> function = bind -> bind.matchesMouse(button);
		if (function.apply(EmiConfig.back)) {
			EmiHistory.pop();
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (viewMode == ViewMode.PURE_REF && focusedPureRefTree == null) {
			if (button == 0) {
				if (pureRefObjectMoved || pureRefResizingTree || pureRefResizingNote || pureRefCreatingShape) {
					markBoardDirty();
				}
				pureRefDraggingObject = false;
				pureRefResizingNote = false;
				pureRefResizingTree = false;
				pureRefResizingCheckList = false;
				editingShape = null;
				activeShapeHandle = ShapeHandle.NONE;
				if (pureRefCreatingShape) {
					pureRefCreatingShape = false;
					if (activeShapeDraft != null && activeShapeDraft.x == activeShapeDraft.x2 && activeShapeDraft.y == activeShapeDraft.y2) {
						project().objects.remove(activeShapeDraft);
					}
					activeShapeDraft = null;
				}
				return true;
			}
			if (button == 2 && pureRefPanning) {
				pureRefPanning = false;
				markBoardDirty();
				return true;
			}
			return super.mouseReleased(mouseX, mouseY, button);
		}
		if (button == 0 && draggedNode != null) {
			if (!draggedNodeMoved) {
				long now = System.currentTimeMillis();
				if (draggedNode.comparisonCandidate) {
					if (selectComparison(draggedNode.compareOwner, draggedNode.node.recipe)) {
						closeContextMenu();
						recalculateTree(getNodePath(draggedNode.compareOwner));
					}
				} else if (lastNodeClickPath != null && lastNodeClickPath.equals(draggedNode.path) && now - lastNodeClickTime < 250) {
					if (toggleComparison(draggedNode.node)) {
						closeContextMenu();
						recalculateTree(draggedNode.path);
					}
					lastNodeClickPath = null;
					lastNodeClickTime = 0;
				} else {
					lastNodeClickPath = draggedNode.path;
					lastNodeClickTime = now;
				}
			}
			draggedNode = null;
			draggingBranch = false;
			draggedNodeMoved = false;
			return true;
		}
		if (button == 2 && panningView) {
			panningView = false;
			return true;
		}
		if (button == 2 && draggedNode != null) {
			draggedNode = null;
			draggingBranch = false;
			draggedNodeMoved = false;
			return true;
		}
		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (viewMode == ViewMode.PURE_REF && focusedPureRefTree == null) {
			if (projectLibraryOpen && getProjectPanelBounds().contains((int) mouseX, (int) mouseY)) {
				projectScrollTarget = MathHelper.clamp(projectScrollTarget - (float) amount * 0.65f, 0, getProjectMaxScroll());
				return true;
			}
			int cx = getPureRefCanvasX(mouseX);
			int cy = getPureRefCanvasY(mouseY);
			PureRefProject.Object hoveredObject = getPureRefObjectAt(cx, cy);
			if (hoveredObject instanceof PureRefProject.CheckListObject checkList) {
				int visibleRows = Math.max(1, Math.min(6, (checkList.height - 42) / 16));
				for (int i = 0; i < visibleRows && i < checkList.entries.size(); i++) {
					if (getChecklistBoardAmountBounds(checkList, i).contains(cx, cy)) {
						long adjustment = amount > 0 ? 1 : -1;
						if (EmiInput.isShiftDown()) {
							adjustment *= 16;
						}
						PureRefProject.CheckListEntry entry = checkList.entries.get(i);
						entry.currentAmount = Math.max(0, entry.currentAmount + adjustment);
						markBoardDirty();
						return true;
					}
				}
			}
			if (editingCheckList != null && getCheckListEditorBounds().contains((int) mouseX, (int) mouseY)) {
				editingCheckListScroll = MathHelper.clamp(editingCheckListScroll - (int) amount, 0, Math.max(0, editingCheckList.entries.size() - 6));
				return true;
			}
			zoom += (int) amount;
			project().zoom = zoom;
			markBoardDirty();
			return true;
		}
		if (libraryOpen && getLibraryPanelBounds().contains((int) mouseX, (int) mouseY)) {
			libraryScrollTarget = MathHelper.clamp(libraryScrollTarget - (float) amount * 0.65f, 0, getLibraryMaxScroll());
			return true;
		}
		scrollAcc += amount;
		amount = (int) scrollAcc;
		scrollAcc %= 1;
		float scale = getScale();
		int mx = (int) ((mouseX - width / 2) / scale - offX);
		int my = (int) ((mouseY - height / 2) / scale - offY);
		if (BoM.tree != null && batches.contains(mx, my)) {
			long adjustment = (long) amount;
			if (EmiInput.isShiftDown()) {
				adjustment *= 16;
			} else if (EmiInput.isControlDown()) {
				if (amount > 0) {
					adjustment = BoM.tree.batches;
				} else {
					adjustment = -BoM.tree.batches / 2;
				}
			}
			if (BoM.tree.batches == 1 && adjustment > 1) {
				BoM.tree.batches = adjustment;
			} else {
				BoM.tree.batches += adjustment;
			}
			BoM.tree.batches = Math.max(1, BoM.tree.batches);
			recalculateTree();
			return true;
		}
		zoom += (int) amount;
		return true;
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		if (viewMode == ViewMode.PURE_REF && focusedPureRefTree == null) {
			int cx = getPureRefCanvasX(mouseX);
			int cy = getPureRefCanvasY(mouseY);
			if (button == 0 && pureRefCreatingShape && activeShapeDraft != null) {
				activeShapeDraft.x2 = cx;
				activeShapeDraft.y2 = cy;
				return true;
			}
			if (button == 0 && pureRefDraggingObject && selectedPureRefObject != null) {
				int dx = cx - pureRefDragLastX;
				int dy = cy - pureRefDragLastY;
				pureRefDragLastX = cx;
				pureRefDragLastY = cy;
				if (dx != 0 || dy != 0) {
					pureRefObjectMoved = true;
					if (editingNote != null) {
						commitNoteEditor();
					}
				}
				if (selectedPureRefObject instanceof PureRefProject.ShapeObject shape) {
					switch (activeShapeHandle) {
						case START -> {
							shape.x += dx;
							shape.y += dy;
						}
						case END -> {
							shape.x2 += dx;
							shape.y2 += dy;
						}
						case TOP_LEFT -> {
							shape.x += dx;
							shape.y += dy;
						}
						case TOP_RIGHT -> {
							shape.x2 += dx;
							shape.y += dy;
						}
						case BOTTOM_LEFT -> {
							shape.x += dx;
							shape.y2 += dy;
						}
						case BOTTOM_RIGHT -> {
							shape.x2 += dx;
							shape.y2 += dy;
						}
						case MOVE, NONE -> {
							shape.x += dx;
							shape.y += dy;
							shape.x2 += dx;
							shape.y2 += dy;
						}
					}
				} else {
					selectedPureRefObject.x += dx;
					selectedPureRefObject.y += dy;
				}
				return true;
			}
			if (button == 0 && pureRefResizingTree && selectedPureRefObject instanceof PureRefProject.TreeObject tree) {
				tree.width = Math.max(220, cx - tree.x);
				tree.height = Math.max(140, cy - tree.y);
				return true;
			}
			if (button == 0 && pureRefResizingCheckList && selectedPureRefObject instanceof PureRefProject.CheckListObject checkList) {
				checkList.width = Math.max(220, cx - checkList.x);
				checkList.height = Math.max(120, cy - checkList.y);
				return true;
			}
			if (button == 0 && pureRefResizingNote && selectedPureRefObject instanceof PureRefProject.NoteObject note) {
				note.width = Math.max(120, cx - note.x);
				note.height = Math.max(70, cy - note.y);
				return true;
			}
			if (button == 2 && pureRefPanning) {
				float scale = getScale();
				offX += deltaX / scale;
				offY += deltaY / scale;
				project().offX = offX;
				project().offY = offY;
				return true;
			}
			return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
		}
		if (button == 0 && draggedNode != null) {
			float scale = getScale();
			int mx = (int) ((mouseX - width / 2) / scale - offX);
			int my = (int) ((mouseY - height / 2) / scale - offY);
			int dx = mx - dragLastTreeX;
			int dy = my - dragLastTreeY;
			dragLastTreeX = mx;
			dragLastTreeY = my;
			if (dx != 0 || dy != 0) {
				draggedNodeMoved = true;
			}
			moveDraggedNode(dx, dy);
			return true;
		}
		if (button == 2 && panningView) {
			float scale = getScale();
			offX += deltaX / scale;
			offY += deltaY / scale;
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	private void moveDraggedNode(int dx, int dy) {
		if (dx == 0 && dy == 0 || BoM.tree == null || draggedNode == null) {
			return;
		}
		for (Node node : nodes) {
			if (node == draggedNode || (draggingBranch && node.path.startsWith(draggedNode.path + "/"))) {
				node.x += dx;
				node.y += dy;
				MaterialTree.NodeOffset current = BoM.tree.nodeOffsets.get(node.path);
				int ox = current == null ? 0 : current.x();
				int oy = current == null ? 0 : current.y();
				BoM.tree.nodeOffsets.put(node.path, new MaterialTree.NodeOffset(ox + dx, oy + dy));
			}
		}
	}

	@Override
	public void close() {
		if (focusedPureRefTree != null) {
			exitPureRefTreeFocus(true);
		}
		if (viewMode == ViewMode.PURE_REF) {
			project().offX = offX;
			project().offY = offY;
			project().zoom = zoom;
			markBoardDirty();
		}
		BoM.flushPureRefAutosave();
		MinecraftClient.getInstance().setScreen(old);
	}

	public @org.jetbrains.annotations.Nullable SavedRecipeTree.RecipeTreeSnapshot createSnapshot() {
		return SavedRecipeTree.RecipeTreeSnapshot.capture(BoM.tree, offX, offY, zoom);
	}

	public EmiIngredient getTreeThumbnail() {
		if (BoM.tree != null && BoM.tree.goal != null) {
			return BoM.tree.goal.ingredient;
		}
		return EmiStack.EMPTY;
	}

	public String getDefaultTreeName() {
		EmiIngredient ingredient = getTreeThumbnail();
		if (!ingredient.isEmpty() && !ingredient.getEmiStacks().isEmpty()) {
			return ingredient.getEmiStacks().get(0).getName().getString();
		}
		return "Empty Slot";
	}

	public void applyLoadedTree(boolean missingData) {
		loadWarning = missingData;
		init(client, width, height);
	}

	private void updateNoteEditorFields() {
		// Note editing is handled directly on the canvas.
	}

	private void updateCheckListEditorFields() {
		boolean visible = editingCheckList != null;
		if (checklistTitleField == null) {
			return;
		}
		checklistTitleField.setVisible(visible);
		checklistLabelField.setVisible(visible);
		checklistCurrentField.setVisible(visible);
		checklistTargetField.setVisible(visible);
		if (!visible) {
			checklistTitleField.setFocused(false);
			checklistLabelField.setFocused(false);
			checklistCurrentField.setFocused(false);
			checklistTargetField.setFocused(false);
			return;
		}
		Bounds panel = getCheckListEditorBounds();
		checklistTitleField.setX(panel.x() + 12);
		checklistTitleField.setY(panel.y() + 24);
		checklistTitleField.setWidth(panel.width() - 24);
		checklistLabelField.setX(panel.x() + 128);
		checklistLabelField.setY(panel.y() + 154);
		checklistLabelField.setWidth(panel.width() - 140);
		checklistCurrentField.setX(panel.x() + 128);
		checklistCurrentField.setY(panel.y() + 190);
		checklistCurrentField.setWidth(64);
		checklistTargetField.setX(panel.x() + 202);
		checklistTargetField.setY(panel.y() + 190);
		checklistTargetField.setWidth(64);
		String title = editingCheckList.title == null ? "" : editingCheckList.title;
		if (!checklistTitleField.isFocused() && !Objects.equals(checklistTitleField.getText(), title)) {
			checklistTitleField.setText(title);
		}
		PureRefProject.CheckListEntry entry = getSelectedCheckListEntry();
		String label = entry == null ? "" : entry.label;
		String current = entry == null ? "" : Long.toString(entry.currentAmount);
		String target = entry == null ? "" : Long.toString(entry.targetAmount);
		if (!checklistLabelField.isFocused() && !Objects.equals(checklistLabelField.getText(), label)) {
			checklistLabelField.setText(label);
		}
		if (!checklistCurrentField.isFocused() && !Objects.equals(checklistCurrentField.getText(), current)) {
			checklistCurrentField.setText(current);
		}
		if (!checklistTargetField.isFocused() && !Objects.equals(checklistTargetField.getText(), target)) {
			checklistTargetField.setText(target);
		}
	}

	private Bounds getCheckListEditorBounds() {
		int panelWidth = Math.min(280, width - 24);
		int panelHeight = Math.min(254, height - 70);
		return new Bounds(width - panelWidth - 12, 64, panelWidth, panelHeight);
	}

	private PureRefProject.CheckListEntry getSelectedCheckListEntry() {
		if (editingCheckList == null || editingCheckList.entries.isEmpty()) {
			return null;
		}
		if (editingCheckListRow < 0 || editingCheckListRow >= editingCheckList.entries.size()) {
			editingCheckListRow = MathHelper.clamp(editingCheckListRow, 0, editingCheckList.entries.size() - 1);
		}
		return editingCheckList.entries.get(editingCheckListRow);
	}

	private long parseChecklistAmount(String text, long fallback) {
		try {
			return Math.max(0, Long.parseLong(text.trim()));
		} catch (Exception e) {
			return fallback;
		}
	}

	private void openCheckListEditor(PureRefProject.CheckListObject checkList) {
		editingCheckList = checkList;
		if (editingCheckList.entries.isEmpty()) {
			editingCheckList.entries.add(new PureRefProject.CheckListEntry(PureRefProject.nextCheckListEntryId(), "Item", 0, 0, null, null));
		}
		editingCheckListRow = 0;
		editingCheckListScroll = 0;
		updateCheckListEditorFields();
	}

	private void closeCheckListEditor() {
		editingCheckList = null;
		editingCheckListRow = -1;
		editingCheckListScroll = 0;
		updateCheckListEditorFields();
	}

	private Bounds getBoardContextMenuBounds() {
		int width = 160;
		int height = boardContextMenuObject instanceof PureRefProject.NoteObject || boardContextMenuObject instanceof PureRefProject.ShapeObject ? 74 : 42;
		int x = MathHelper.clamp(boardContextMenuX, 8, this.width - width - 8);
		int y = MathHelper.clamp(boardContextMenuY, 64, this.height - height - 8);
		return new Bounds(x, y, width, height);
	}

	private Bounds getBoardContextEditBounds(Bounds menu) {
		return new Bounds(menu.x() + 10, menu.y() + 22, menu.width() - 20, 16);
	}

	private Bounds getBoardContextColorBounds(Bounds menu, int index) {
		int size = 18;
		int startX = menu.x() + 10;
		return new Bounds(startX + index * (size + 6), menu.y() + 46, size, size);
	}

	private void openBoardContextMenu(PureRefProject.Object object, int mouseX, int mouseY) {
		boardContextMenuObject = object;
		boardContextMenuX = mouseX + 8;
		boardContextMenuY = mouseY + 6;
	}

	private void closeBoardContextMenu() {
		boardContextMenuObject = null;
	}

	private int getBoardObjectColor(PureRefProject.Object object) {
		if (object instanceof PureRefProject.NoteObject note) {
			return note.color;
		} else if (object instanceof PureRefProject.ShapeObject shape) {
			return shape.color;
		}
		return 0xFFFFFFFF;
	}

	private void applyBoardContextColor(int color) {
		if (boardContextMenuObject instanceof PureRefProject.NoteObject note) {
			note.color = color;
			markBoardDirty();
		} else if (boardContextMenuObject instanceof PureRefProject.ShapeObject shape) {
			shape.color = color;
			markBoardDirty();
		}
		closeBoardContextMenu();
	}

	private void renderBoardContextMenu(EmiDrawContext context, int mouseX, int mouseY) {
		Bounds menu = getBoardContextMenuBounds();
		boolean editable = boardContextMenuObject instanceof PureRefProject.NoteObject
			|| boardContextMenuObject instanceof PureRefProject.CheckListObject
			|| boardContextMenuObject instanceof PureRefProject.TreeObject;
		boolean colorable = boardContextMenuObject instanceof PureRefProject.NoteObject || boardContextMenuObject instanceof PureRefProject.ShapeObject;
		context.push();
		context.matrices().translate(0, 0, 500);
		RenderSystem.disableDepthTest();
		context.fill(menu.x() - 2, menu.y() - 2, menu.width() + 4, menu.height() + 4, 0x33000000);
		context.fill(menu.x(), menu.y(), menu.width(), menu.height(), 0xF1181D24);
		context.fill(menu.x(), menu.y(), menu.width(), 18, 0xFF253442);
		context.drawTextWithShadow(EmiPort.literal("Board Menu", Formatting.WHITE), menu.x() + 8, menu.y() + 5, -1);
		if (editable) {
			renderLibraryAction(context, getBoardContextEditBounds(menu), "Edit", true, mouseX, mouseY);
		}
		if (colorable) {
			context.drawTextWithShadow(EmiPort.literal("Color", Formatting.GRAY), menu.x() + 10, menu.y() + 34, -1);
			int current = getBoardObjectColor(boardContextMenuObject);
			for (int i = 0; i < BOARD_CONTEXT_COLORS.length; i++) {
				Bounds swatch = getBoardContextColorBounds(menu, i);
				int border = BOARD_CONTEXT_COLORS[i] == current ? 0xFFF3D77A : swatch.contains(mouseX, mouseY) ? 0xFFB7C8D8 : 0xFF3A4B5B;
				context.fill(swatch.x() - 1, swatch.y() - 1, swatch.width() + 2, swatch.height() + 2, border);
				context.fill(swatch.x(), swatch.y(), swatch.width(), swatch.height(), BOARD_CONTEXT_COLORS[i]);
			}
		}
		RenderSystem.enableDepthTest();
		context.pop();
	}

	private boolean handleBoardContextMenuClick(int mouseX, int mouseY, int button) {
		if (boardContextMenuObject == null) {
			return false;
		}
		Bounds menu = getBoardContextMenuBounds();
		if (!menu.contains(mouseX, mouseY)) {
			if (button == 0 || button == 1) {
				closeBoardContextMenu();
			}
			return false;
		}
		if (button == 0 || button == 1) {
			if ((boardContextMenuObject instanceof PureRefProject.NoteObject || boardContextMenuObject instanceof PureRefProject.CheckListObject
				|| boardContextMenuObject instanceof PureRefProject.TreeObject)
				&& getBoardContextEditBounds(menu).contains(mouseX, mouseY)) {
				if (boardContextMenuObject instanceof PureRefProject.NoteObject note) {
					openNoteEditor(note);
				} else if (boardContextMenuObject instanceof PureRefProject.CheckListObject checkList) {
					openCheckListEditor(checkList);
				} else if (boardContextMenuObject instanceof PureRefProject.TreeObject tree) {
					enterPureRefTreeFocus(tree);
				}
				closeBoardContextMenu();
				return true;
			}
			if (boardContextMenuObject instanceof PureRefProject.NoteObject || boardContextMenuObject instanceof PureRefProject.ShapeObject) {
				for (int i = 0; i < BOARD_CONTEXT_COLORS.length; i++) {
					if (getBoardContextColorBounds(menu, i).contains(mouseX, mouseY)) {
						applyBoardContextColor(BOARD_CONTEXT_COLORS[i]);
						return true;
					}
				}
			}
		}
		return true;
	}

	private Bounds getChecklistBoardRowBounds(PureRefProject.CheckListObject checkList, int visibleIndex) {
		return new Bounds(checkList.x + 6, checkList.y + 24 + visibleIndex * 16 - 1, checkList.width - 12, 14);
	}

	private Bounds getChecklistBoardAmountBounds(PureRefProject.CheckListObject checkList, int visibleIndex) {
		Bounds row = getChecklistBoardRowBounds(checkList, visibleIndex);
		return new Bounds(row.x() + row.width() - 52, row.y() + 1, 50, 12);
	}

	private PureRefProject.TreeObject findBoardTreeById(String objectId) {
		if (objectId == null || objectId.isBlank()) {
			return null;
		}
		for (PureRefProject.Object object : project().objects) {
			if (object instanceof PureRefProject.TreeObject tree && objectId.equals(tree.id)) {
				return tree;
			}
		}
		return null;
	}

	private PureRefProject.TreeObject getChecklistTargetTree() {
		if (selectedPureRefObject instanceof PureRefProject.TreeObject tree) {
			return tree;
		}
		return findBoardTreeById(lastSelectedBoardTreeId);
	}

	private List<FlatMaterialCost> getTreeChecklistCosts(PureRefProject.TreeObject treeObject) {
		if (treeObject == null) {
			return List.of();
		}
		SavedRecipeTree.TreeBuildResult result = treeObject.buildTree();
		if (result == null || result.tree == null) {
			return List.of();
		}
		result.tree.calculateCost();
		return Stream.concat(result.tree.cost.costs.values().stream(), result.tree.cost.chanceCosts.values().stream())
			.sorted((a, b) -> Integer.compare(
				EmiStackList.getIndex(a.ingredient.getEmiStacks().isEmpty() ? EmiStack.EMPTY : a.ingredient.getEmiStacks().get(0)),
				EmiStackList.getIndex(b.ingredient.getEmiStacks().isEmpty() ? EmiStack.EMPTY : b.ingredient.getEmiStacks().get(0))
			))
			.toList();
	}

	private String getCheckListEntryLabel(FlatMaterialCost cost) {
		if (cost.ingredient == null || cost.ingredient.getEmiStacks().isEmpty()) {
			return "Item";
		}
		return cost.ingredient.getEmiStacks().get(0).getName().getString();
	}

	private void populateCheckListFromTree(PureRefProject.CheckListObject checkList, PureRefProject.TreeObject treeObject, boolean preserveProgress) {
		if (checkList == null || treeObject == null) {
			return;
		}
		Map<String, Long> existing = preserveProgress
			? checkList.entries.stream().collect(Collectors.toMap(e -> e.ingredient == null ? e.label : e.ingredient.toString(), e -> e.currentAmount, (a, b) -> a))
			: Map.of();
		List<PureRefProject.CheckListEntry> entries = Lists.newArrayList();
		for (FlatMaterialCost cost : getTreeChecklistCosts(treeObject)) {
			JsonElement ingredientJson = EmiIngredientSerializer.getSerialized(cost.ingredient);
			String key = ingredientJson == null ? getCheckListEntryLabel(cost) : ingredientJson.toString();
			long current = preserveProgress ? existing.getOrDefault(key, 0L) : 0L;
			entries.add(new PureRefProject.CheckListEntry(
				PureRefProject.nextCheckListEntryId(),
				getCheckListEntryLabel(cost),
				current,
				cost.getEffectiveAmount(),
				ingredientJson,
				null
			));
		}
		if (entries.isEmpty()) {
			entries.add(new PureRefProject.CheckListEntry(PureRefProject.nextCheckListEntryId(), "Item", 0, 0, null, null));
		}
		checkList.entries.clear();
		checkList.entries.addAll(entries);
		checkList.linkedTreeObjectId = treeObject.id;
		checkList.title = (treeObject.title == null || treeObject.title.isBlank() ? "Checklist" : treeObject.title) + " Checklist";
		editingCheckListRow = 0;
		updateCheckListEditorFields();
		markBoardDirty();
	}

	private EmiIngredient getCheckListIngredient(PureRefProject.CheckListEntry entry) {
		if (entry == null || entry.ingredient == null) {
			return EmiStack.EMPTY;
		}
		EmiIngredient ingredient = EmiIngredientSerializer.getDeserialized(entry.ingredient);
		return ingredient == null ? EmiStack.EMPTY : ingredient;
	}

	private void renderCheckListIcon(EmiDrawContext context, PureRefProject.CheckListEntry entry, int x, int y, float delta) {
		EmiIngredient ingredient = getCheckListIngredient(entry);
		if (ingredient != null && !ingredient.isEmpty()) {
			ingredient.render(context.raw(), x, y, delta, ~(EmiIngredient.RENDER_AMOUNT | EmiIngredient.RENDER_REMAINDER));
		}
	}

	private Bounds getCheckListListBounds(Bounds panel) {
		return new Bounds(panel.x() + 12, panel.y() + 52, panel.width() - 24, 92);
	}

	private Bounds getCheckListEditorButtonBounds(Bounds panel, int row, int column, String label) {
		int width = Math.max(60, textRenderer.getWidth(label) + 14);
		int totalGap = 8;
		int totalWidth = panel.width() - 24;
		int x = panel.x() + 12 + column * ((totalWidth - totalGap) / 2 + totalGap);
		int y = panel.y() + 220 + row * 20;
		return new Bounds(x, y, (totalWidth - totalGap) / 2, 18);
	}

	private Bounds getCheckListEditorCloseBounds(Bounds panel) {
		return new Bounds(panel.x() + panel.width() - 66, panel.y() + 22, 54, 18);
	}

	private void renderCheckListEditor(EmiDrawContext context, int mouseX, int mouseY) {
		Bounds panel = getCheckListEditorBounds();
		Bounds list = getCheckListListBounds(panel);
		context.push();
		context.matrices().translate(0, 0, 500);
		RenderSystem.disableDepthTest();
		context.fill(panel.x() - 4, panel.y() - 4, panel.width() + 8, panel.height() + 8, 0x33000000);
		context.fill(panel.x(), panel.y(), panel.width(), panel.height(), 0xF1161B14);
		context.fill(panel.x(), panel.y(), panel.width(), 18, 0xFF2C221B);
		context.drawTextWithShadow(EmiPort.literal("Checklist", Formatting.WHITE), panel.x() + 8, panel.y() + 5, -1);
		renderLibraryAction(context, getCheckListEditorCloseBounds(panel), "Close", true, mouseX, mouseY);
		context.fill(list.x(), list.y(), list.width(), list.height(), 0x66242B33);
		int rowHeight = 14;
		int visible = 6;
		for (int i = 0; i < visible; i++) {
			int index = editingCheckListScroll + i;
			if (index >= editingCheckList.entries.size()) {
				break;
			}
			PureRefProject.CheckListEntry entry = editingCheckList.entries.get(index);
			int y = list.y() + 4 + i * rowHeight;
			int bg = index == editingCheckListRow ? 0xFF34506A : 0x88303A45;
			context.fill(list.x() + 2, y - 1, list.width() - 4, rowHeight - 1, bg);
			int iconX = list.x() + 6;
			int nameX = iconX + 18;
			renderCheckListIcon(context, entry, iconX, y + 1, 0);
			int progressColor = entry.currentAmount >= entry.targetAmount && entry.targetAmount > 0 ? 0xFF9AD27A : 0xFFE7D9AA;
			String amount = entry.currentAmount + "/" + entry.targetAmount;
			int amountX = list.x() + list.width() - textRenderer.getWidth(amount) - 8;
			context.drawTextWithShadow(trimLibraryText(entry.label, amountX - nameX - 8, Formatting.WHITE), nameX, y + 3, -1);
			context.drawTextWithShadow(EmiPort.literal(amount, Formatting.WHITE), amountX, y + 3, progressColor);
		}
		renderLibraryAction(context, getCheckListEditorButtonBounds(panel, 0, 0, "Add Row"), "Add Row", true, mouseX, mouseY);
		renderLibraryAction(context, getCheckListEditorButtonBounds(panel, 0, 1, "Remove"), "Remove", getSelectedCheckListEntry() != null, mouseX, mouseY);
		renderLibraryAction(context, getCheckListEditorButtonBounds(panel, 1, 0, "Link Tree"), "Link Tree", getChecklistTargetTree() != null, mouseX, mouseY);
		renderLibraryAction(context, getCheckListEditorButtonBounds(panel, 1, 1, "From Tree"), "From Tree", getChecklistTargetTree() != null, mouseX, mouseY);
		renderLibraryAction(context, getCheckListEditorButtonBounds(panel, 2, 0, "Refresh"), "Refresh", findBoardTreeById(editingCheckList.linkedTreeObjectId) != null, mouseX, mouseY);
		String autoSyncLabel = editingCheckList.autoSync ? "Auto Sync: On" : "Auto Sync: Off";
		renderLibraryAction(context, getCheckListEditorButtonBounds(panel, 2, 1, autoSyncLabel),
			autoSyncLabel, editingCheckList.linkedTreeObjectId != null, mouseX, mouseY);
		RenderSystem.enableDepthTest();
		context.pop();
	}

	private boolean handleCheckListEditorClick(int mouseX, int mouseY, int button) {
		if (editingCheckList == null || button != 0) {
			return false;
		}
		Bounds panel = getCheckListEditorBounds();
		if (!panel.contains(mouseX, mouseY)) {
			return false;
		}
		if (getCheckListEditorCloseBounds(panel).contains(mouseX, mouseY)) {
			closeCheckListEditor();
			return true;
		}
		if (checklistTitleField.isMouseOver(mouseX, mouseY)
			|| checklistLabelField.isMouseOver(mouseX, mouseY)
			|| checklistCurrentField.isMouseOver(mouseX, mouseY)
			|| checklistTargetField.isMouseOver(mouseX, mouseY)) {
			return false;
		}
		Bounds list = getCheckListListBounds(panel);
		if (list.contains(mouseX, mouseY)) {
			int local = (mouseY - list.y() - 4) / 14;
			int index = editingCheckListScroll + local;
			if (local >= 0 && index >= 0 && index < editingCheckList.entries.size()) {
				editingCheckListRow = index;
				updateCheckListEditorFields();
				return true;
			}
		}
		if (getCheckListEditorButtonBounds(panel, 0, 0, "Add Row").contains(mouseX, mouseY)) {
			editingCheckList.entries.add(new PureRefProject.CheckListEntry(PureRefProject.nextCheckListEntryId(), "Item", 0, 0, null, null));
			editingCheckListRow = editingCheckList.entries.size() - 1;
			updateCheckListEditorFields();
			markBoardDirty();
			return true;
		}
		if (getCheckListEditorButtonBounds(panel, 0, 1, "Remove").contains(mouseX, mouseY)) {
			PureRefProject.CheckListEntry entry = getSelectedCheckListEntry();
			if (entry != null) {
				editingCheckList.entries.remove(entry);
				if (editingCheckList.entries.isEmpty()) {
					editingCheckList.entries.add(new PureRefProject.CheckListEntry(PureRefProject.nextCheckListEntryId(), "Item", 0, 0, null, null));
				}
				editingCheckListRow = MathHelper.clamp(editingCheckListRow, 0, editingCheckList.entries.size() - 1);
				updateCheckListEditorFields();
				markBoardDirty();
			}
			return true;
		}
		if (getCheckListEditorButtonBounds(panel, 1, 0, "Link Tree").contains(mouseX, mouseY)) {
			PureRefProject.TreeObject tree = getChecklistTargetTree();
			if (tree != null) {
				populateCheckListFromTree(editingCheckList, tree, true);
			}
			return true;
		}
		if (getCheckListEditorButtonBounds(panel, 1, 1, "From Tree").contains(mouseX, mouseY)) {
			PureRefProject.TreeObject tree = getChecklistTargetTree();
			if (tree != null) {
				populateCheckListFromTree(editingCheckList, tree, false);
			}
			return true;
		}
		if (getCheckListEditorButtonBounds(panel, 2, 0, "Refresh").contains(mouseX, mouseY)) {
			PureRefProject.TreeObject tree = findBoardTreeById(editingCheckList.linkedTreeObjectId);
			if (tree != null) {
				populateCheckListFromTree(editingCheckList, tree, true);
			}
			return true;
		}
		if (getCheckListEditorButtonBounds(panel, 2, 1, editingCheckList.autoSync ? "Auto Sync: On" : "Auto Sync: Off").contains(mouseX, mouseY)
			&& editingCheckList.linkedTreeObjectId != null) {
			editingCheckList.autoSync = !editingCheckList.autoSync;
			markBoardDirty();
			return true;
		}
		return true;
	}

	private void normalizeInlineNoteWrapping() {
		if (editingNote == null) {
			return;
		}
		int cursorIndex = getInlineNoteCursorIndex();
		editingNoteLines = wrapInlineNoteLines(editingNoteLines);
		restoreInlineNoteCursorFromIndex(cursorIndex);
	}

	private int getInlineNoteCursorIndex() {
		int index = 0;
		for (int i = 0; i < editingNoteCursorLine && i < editingNoteLines.size(); i++) {
			index += editingNoteLines.get(i).length() + 1;
		}
		if (!editingNoteLines.isEmpty()) {
			index += Math.min(editingNoteCursorColumn, editingNoteLines.get(Math.min(editingNoteCursorLine, editingNoteLines.size() - 1)).length());
		}
		return index;
	}

	private void restoreInlineNoteCursorFromIndex(int index) {
		if (editingNoteLines.isEmpty()) {
			editingNoteLines.add("");
		}
		int remaining = Math.max(0, index);
		for (int i = 0; i < editingNoteLines.size(); i++) {
			String line = editingNoteLines.get(i);
			if (remaining <= line.length()) {
				editingNoteCursorLine = i;
				editingNoteCursorColumn = remaining;
				return;
			}
			remaining -= line.length();
			if (i < editingNoteLines.size() - 1) {
				if (remaining == 0) {
					editingNoteCursorLine = i;
					editingNoteCursorColumn = line.length();
					return;
				}
				remaining--;
			}
		}
		editingNoteCursorLine = editingNoteLines.size() - 1;
		editingNoteCursorColumn = editingNoteLines.get(editingNoteCursorLine).length();
	}

	private List<String> wrapInlineNoteLines(List<String> sourceLines) {
		List<String> wrapped = Lists.newArrayList();
		for (String source : sourceLines) {
			if (source.isEmpty()) {
				wrapped.add("");
				continue;
			}
			String remaining = source;
			while (!remaining.isEmpty()) {
				int split = remaining.length();
				while (split > 0 && textRenderer.getWidth(remaining.substring(0, split)) > PURE_REF_NOTE_WRAP_WIDTH) {
					split--;
				}
				if (split <= 0) {
					split = 1;
				}
				if (split < remaining.length()) {
					int lastSpace = remaining.lastIndexOf(' ', split - 1);
					if (lastSpace > 0) {
						split = lastSpace + 1;
					}
				}
				String line = remaining.substring(0, split);
				wrapped.add(line);
				remaining = remaining.substring(split);
			}
		}
		if (wrapped.isEmpty()) {
			wrapped.add("");
		}
		return wrapped;
	}

	private String getPureRefNoteText(PureRefProject.NoteObject note) {
		String body = note.body == null ? "" : note.body.trim();
		if (!body.isEmpty()) {
			return body;
		}
		String title = note.title == null ? "" : note.title.trim();
		if (!title.isEmpty()) {
			return title;
		}
		return "Note";
	}

	private List<String> getPureRefNoteLines(PureRefProject.NoteObject note) {
		String text = getPureRefNoteText(note);
		List<String> lines = Lists.newArrayList(text.split("\\n", -1));
		if (lines.isEmpty()) {
			lines.add("Note");
		}
		return lines;
	}

	private void openNoteEditor(PureRefProject.NoteObject note) {
		editingNote = note;
		String text = note.body == null || note.body.isBlank() ? getPureRefNoteText(note) : note.body;
		editingNoteLines = Lists.newArrayList(text.split("\\n", -1));
		if (editingNoteLines.isEmpty()) {
			editingNoteLines.add("");
		}
		editingNoteLines = wrapInlineNoteLines(editingNoteLines);
		editingNoteCursorLine = editingNoteLines.size() - 1;
		editingNoteCursorColumn = editingNoteLines.get(editingNoteCursorLine).length();
	}

	private void commitNoteEditor() {
		if (editingNote != null) {
			syncEditingNoteToObject();
		}
		editingNote = null;
		editingNoteLines = Lists.newArrayList();
		editingNoteCursorLine = 0;
		editingNoteCursorColumn = 0;
	}

	private void closeNoteEditor() {
		editingNote = null;
		editingNoteLines = Lists.newArrayList();
		editingNoteCursorLine = 0;
		editingNoteCursorColumn = 0;
	}

	private void syncEditingNoteToObject() {
		if (editingNote == null) {
			return;
		}
		String text = String.join("\n", editingNoteLines);
		editingNote.title = "";
		editingNote.body = text;
		int width = 80;
		for (String line : editingNoteLines) {
			width = Math.max(width, textRenderer.getWidth(line.isEmpty() ? " " : line) + 6);
		}
		editingNote.width = width;
		editingNote.height = Math.max(10, editingNoteLines.size() * 10);
		markBoardDirty();
	}

	private void setInlineNoteCursor(PureRefProject.NoteObject note, int canvasX, int canvasY) {
		List<String> lines = editingNote == note ? editingNoteLines : getPureRefNoteLines(note);
		if (lines.isEmpty()) {
			editingNoteCursorLine = 0;
			editingNoteCursorColumn = 0;
			return;
		}
		Bounds bounds = getPureRefNoteBounds(note);
		int line = MathHelper.clamp((canvasY - bounds.y()) / 10, 0, lines.size() - 1);
		String text = lines.get(line);
		int targetX = Math.max(0, canvasX - bounds.x());
		int bestColumn = 0;
		int bestDistance = Integer.MAX_VALUE;
		for (int i = 0; i <= text.length(); i++) {
			int width = textRenderer.getWidth(text.substring(0, i));
			int distance = Math.abs(width - targetX);
			if (distance <= bestDistance) {
				bestDistance = distance;
				bestColumn = i;
			}
		}
		editingNoteCursorLine = line;
		editingNoteCursorColumn = bestColumn;
	}

	private PureRefProject project() {
		return BoM.pureRefProject;
	}

	private void markBoardDirty() {
		BoM.markPureRefDirty();
	}

	private int getPureRefCanvasX(double mouseX) {
		float scale = getScale();
		return (int) ((mouseX - width / 2) / scale - offX);
	}

	private int getPureRefCanvasY(double mouseY) {
		float scale = getScale();
		return (int) ((mouseY - height / 2) / scale - offY);
	}

	private void renderPureRefBoard(DrawContext raw, int mouseX, int mouseY, float delta) {
		EmiDrawContext context = EmiDrawContext.wrap(raw);
		this.renderBackgroundTexture(raw);
		float scale = getScale();
		int canvasX = getPureRefCanvasX(mouseX);
		int canvasY = getPureRefCanvasY(mouseY);
		MatrixStack view = RenderSystem.getModelViewStack();
		view.push();
		view.translate(width / 2, height / 2, 0);
		view.scale(scale, scale, 1);
		view.translate(offX, offY, 0);
		EmiPort.applyModelViewMatrix();
		renderPureRefGrid(context);
		for (PureRefProject.Object object : project().objects) {
			if (object instanceof PureRefProject.ShapeObject shape) {
				renderPureRefShape(context, shape);
			}
		}
		for (PureRefProject.Object object : project().objects) {
			if (object instanceof PureRefProject.TreeObject tree) {
				renderPureRefTreeCard(context, raw, tree, canvasX, canvasY, delta);
			} else if (object instanceof PureRefProject.NoteObject note) {
				renderPureRefNote(context, note, canvasX, canvasY);
			} else if (object instanceof PureRefProject.CheckListObject checkList) {
				renderPureRefCheckList(context, checkList, canvasX, canvasY, delta);
			}
		}
		if (pendingTreeInsert != null) {
			int oldX = pendingTreeInsert.x;
			int oldY = pendingTreeInsert.y;
			pendingTreeInsert.x = canvasX - pendingTreeInsert.width / 2;
			pendingTreeInsert.y = canvasY - pendingTreeInsert.height / 2;
			renderPureRefTreeCard(context, raw, pendingTreeInsert, canvasX, canvasY, delta);
			pendingTreeInsert.x = oldX;
			pendingTreeInsert.y = oldY;
		}
		if (selectedPureRefObject != null) {
			renderPureRefSelection(context, selectedPureRefObject);
		}
		view.pop();
		EmiPort.applyModelViewMatrix();
		renderPureRefToolbar(context, mouseX, mouseY);
		String hint = pendingTreeInsert != null
			? "LMB: place tree  |  Esc: cancel placement"
			: "MMB drag: pan  |  Wheel: zoom  |  Double click tree/note/checklist: edit  |  Del: remove  |  Ctrl+D: duplicate";
		context.drawTextWithShadow(EmiPort.literal(hint, Formatting.DARK_GRAY),
			8, height - 28, -1);
		if (pendingNoteDelete != null) {
			renderNoteDeleteConfirmation(context, mouseX, mouseY);
		}
		if (boardContextMenuObject != null) {
			renderBoardContextMenu(context, mouseX, mouseY);
		}
		if (editingNote != null) {
			updateNoteEditorFields();
		}
		if (editingCheckList != null) {
			updateCheckListEditorFields();
			renderCheckListEditor(context, mouseX, mouseY);
		}
		if (projectLibraryOpen) {
			projectScroll += (projectScrollTarget - projectScroll) * 0.35f;
			if (Math.abs(projectScrollTarget - projectScroll) < 0.01f) {
				projectScroll = projectScrollTarget;
			}
			updateProjectRenameField();
			renderProjectOverlay(context, mouseX, mouseY);
		}
		if (insertOverlayOpen) {
			renderInsertOverlay(context, raw, mouseX, mouseY, delta);
		}
		super.render(raw, mouseX, mouseY, delta);
	}

	private void renderPureRefGrid(EmiDrawContext context) {
		int left = -2000;
		int right = 2000;
		int top = -2000;
		int bottom = 2000;
		for (int x = left; x <= right; x += 32) {
			int color = x % 128 == 0 ? 0x2A7C8EA6 : 0x143E4A58;
			context.fill(x, top, 1, bottom - top, color);
		}
		for (int y = top; y <= bottom; y += 32) {
			int color = y % 128 == 0 ? 0x2A7C8EA6 : 0x143E4A58;
			context.fill(left, y, right - left, 1, color);
		}
	}

	private void renderPureRefToolbar(EmiDrawContext context, int mouseX, int mouseY) {
		int x = 8;
		int y = 36;
		for (PureRefTool tool : PureRefTool.values()) {
			Bounds bounds = new Bounds(x, y, 74, 18);
			int color = pureRefTool == tool ? 0xFF6B8FB2 : bounds.contains(mouseX, mouseY) ? 0xFF3B556F : 0xFF273241;
			context.fill(bounds.x(), bounds.y(), bounds.width(), bounds.height(), color);
			context.drawCenteredText(EmiPort.literal(tool.label, Formatting.WHITE), bounds.x() + bounds.width() / 2, bounds.y() + 5);
			x += 78;
		}
	}

	private Bounds getConfirmNoteDeleteBounds() {
		return new Bounds(width - 194, 36, 98, 18);
	}

	private Bounds getCancelNoteDeleteBounds() {
		return new Bounds(width - 92, 36, 80, 18);
	}

	private void renderNoteDeleteConfirmation(EmiDrawContext context, int mouseX, int mouseY) {
		Bounds confirm = getConfirmNoteDeleteBounds();
		Bounds cancel = getCancelNoteDeleteBounds();
		context.drawTextWithShadow(EmiPort.literal("Delete note?", Formatting.GOLD), confirm.x() - 86, confirm.y() + 5, -1);
		renderLibraryAction(context, confirm, "Confirm", true, mouseX, mouseY);
		renderLibraryAction(context, cancel, "Cancel", true, mouseX, mouseY);
	}

	private void renderPureRefTreeCard(EmiDrawContext context, DrawContext raw, PureRefProject.TreeObject tree, int mouseX, int mouseY, float delta) {
		Bounds bounds = getPureRefTreeBounds(tree);
		boolean hovered = bounds.contains(mouseX, mouseY);
		context.fill(bounds.x(), bounds.y(), bounds.width(), bounds.height(), hovered ? 0xF1283340 : 0xE01C252E);
		context.fill(bounds.x(), bounds.y(), bounds.width(), BOARD_TREE_HEADER_HEIGHT, 0xFF24394A);
		context.fill(bounds.x(), bounds.y(), 3, bounds.height(), 0xFF8AB7D6);
		String editHint = "RMB Edit";
		int hintWidth = textRenderer.getWidth(editHint);
		context.drawTextWithShadow(EmiPort.literal(editHint, Formatting.DARK_GRAY), bounds.x() + bounds.width() - hintWidth - 8, bounds.y() + 5, -1);
		context.drawTextWithShadow(trimLibraryText(tree.title, bounds.width() - hintWidth - 28, Formatting.WHITE), bounds.x() + 8, bounds.y() + 5, -1);
		SavedRecipeTree.TreeBuildResult build = tree.buildTree();
		if (build != null && build.tree != null && build.tree.goal != null) {
			renderPureRefTreePreview(context, raw, tree, build.tree, bounds, delta);
		}
	}

	private void renderPureRefTreePreview(EmiDrawContext context, DrawContext raw, PureRefProject.TreeObject object, MaterialTree tree, Bounds bounds, float delta) {
		TreeVolume volume = addNewNodes(tree.goal, tree.batches, 1, 0, ChanceState.DEFAULT, "0", -1, 0);
		int horizontalOffset = (volume.getMaxRight() + volume.getMinLeft()) / 2;
		for (Node node : volume.nodes) {
			node.x -= horizontalOffset;
			node.layoutX = node.x;
			node.layoutY = node.y;
		}
		applyPreviewNodeOffsets(volume.nodes, tree);
		int previewX = bounds.x() + 10;
		int previewY = bounds.y() + BOARD_TREE_HEADER_HEIGHT + 6;
		int previewWidth = bounds.width() - 20;
		int totalsHeight = BOARD_TREE_FOOTER_HEIGHT;
		int previewHeight = Math.max(40, bounds.height() - BOARD_TREE_HEADER_HEIGHT - totalsHeight - 12);
		int minLeft = Integer.MAX_VALUE;
		int maxRight = Integer.MIN_VALUE;
		int minTop = Integer.MAX_VALUE;
		int maxBottom = Integer.MIN_VALUE;
		for (Node node : volume.nodes) {
			minLeft = Math.min(minLeft, node.getLeft());
			maxRight = Math.max(maxRight, node.getRight());
			minTop = Math.min(minTop, node.getTop() - (node.resolution != null ? 8 : 0));
			maxBottom = Math.max(maxBottom, node.getBottom());
		}
		if (minLeft == Integer.MAX_VALUE) {
			minLeft = -8;
			maxRight = 8;
			minTop = -8;
			maxBottom = 8;
		}
		int contentWidth = Math.max(32, maxRight - minLeft + 20);
		int contentHeight = Math.max(24, maxBottom - minTop + 20);
		float scale = Math.min(previewWidth / (float) contentWidth, previewHeight / (float) contentHeight);
		scale = Math.min(scale, 1f);
		float translatedX = previewX + previewWidth / 2f - ((minLeft + maxRight) / 2f) * scale;
		float translatedY = previewY + previewHeight / 2f - ((minTop + maxBottom) / 2f) * scale;
		context.push();
		context.matrices().translate(translatedX, translatedY, 0);
		context.matrices().scale(scale, scale, 1f);
		for (Node node : volume.nodes) {
			node.render(context, Integer.MIN_VALUE, Integer.MIN_VALUE, delta);
		}
		context.pop();
		tree.calculateCost();
		renderPureRefTreeTotals(context, tree, bounds.x() + 8, bounds.y() + bounds.height() - 18, bounds.width() - 16, delta);
	}

	private void applyPreviewNodeOffsets(List<Node> previewNodes, MaterialTree tree) {
		Map<String, Node> byPath = previewNodes.stream().collect(Collectors.toMap(n -> n.path, Function.identity(), (a, b) -> a));
		for (Map.Entry<String, MaterialTree.NodeOffset> entry : tree.nodeOffsets.entrySet()) {
			MaterialTree.NodeOffset offset = entry.getValue();
			if (offset == null || (offset.x() == 0 && offset.y() == 0)) {
				continue;
			}
			Node node = byPath.get(entry.getKey());
			if (node != null) {
				node.x += offset.x();
				node.y += offset.y();
			}
		}
	}

	private void renderPureRefTreeTotals(EmiDrawContext context, MaterialTree tree, int x, int y, int width, float delta) {
		List<FlatMaterialCost> treeCosts = Stream.concat(
			tree.cost.costs.values().stream(),
			tree.cost.chanceCosts.values().stream()
		).sorted((a, b) -> Integer.compare(
			EmiStackList.getIndex(a.ingredient.getEmiStacks().get(0)),
			EmiStackList.getIndex(b.ingredient.getEmiStacks().get(0))
		)).toList();
		context.drawTextWithShadow(EmiPort.literal("Total", Formatting.GRAY), x, y - 10, -1);
		int currentX = x;
		int shown = 0;
		for (FlatMaterialCost cost : treeCosts) {
			if (shown >= 5) {
				break;
			}
			Text amountText = EmiRenderHelper.getAmountText(cost.ingredient, cost.getEffectiveAmount());
			int advance = 16 + COST_HORIZONTAL_SPACING + EmiRenderHelper.getAmountOverflow(amountText);
			if (currentX + advance > x + width - 8) {
				break;
			}
			cost.ingredient.render(context.raw(), currentX, y, delta, ~(EmiIngredient.RENDER_AMOUNT | EmiIngredient.RENDER_REMAINDER));
			EmiRenderHelper.renderAmount(context, currentX, y, amountText);
			currentX += advance;
			shown++;
		}
	}

	private void renderPureRefNote(EmiDrawContext context, PureRefProject.NoteObject note, int mouseX, int mouseY) {
		Bounds bounds = getPureRefNoteBounds(note);
		List<String> lines = editingNote == note ? editingNoteLines : getPureRefNoteLines(note);
		for (int i = 0; i < lines.size(); i++) {
			context.drawTextWithShadow(EmiPort.literal(lines.get(i)), bounds.x(), bounds.y() + i * 10, note.color);
		}
		if (editingNote == note && (System.currentTimeMillis() / 500) % 2 == 0) {
			String line = editingNoteLines.get(editingNoteCursorLine);
			int caretX = bounds.x() + textRenderer.getWidth(line.substring(0, Math.min(editingNoteCursorColumn, line.length())));
			int caretY = bounds.y() + editingNoteCursorLine * 10;
			context.fill(caretX, caretY, 1, 9, 0xFFFFFFFF);
		}
	}

	private void renderPureRefCheckList(EmiDrawContext context, PureRefProject.CheckListObject checkList, int mouseX, int mouseY, float delta) {
		Bounds bounds = new Bounds(checkList.x, checkList.y, checkList.width, checkList.height);
		boolean hovered = bounds.contains(mouseX, mouseY);
		context.fill(bounds.x(), bounds.y(), bounds.width(), bounds.height(), hovered ? 0xF12A3138 : 0xE021272E);
		context.fill(bounds.x(), bounds.y(), bounds.width(), 18, 0xFF34465A);
		context.fill(bounds.x(), bounds.y(), 3, bounds.height(), 0xFF9EB6CC);
		String title = checkList.title == null || checkList.title.isBlank() ? "Checklist" : checkList.title;
		context.drawTextWithShadow(trimLibraryText(title, bounds.width() - 16, Formatting.WHITE), bounds.x() + 8, bounds.y() + 5, -1);
		String linkText = checkList.linkedTreeObjectId == null ? "Manual list" : "Linked tree";
		context.drawTextWithShadow(EmiPort.literal(linkText, Formatting.DARK_GRAY), bounds.x() + 8, bounds.y() + bounds.height() - 13, -1);
		int listY = bounds.y() + 24;
		int rowHeight = 16;
		int visibleRows = Math.max(1, Math.min(6, (bounds.height() - 42) / rowHeight));
		for (int i = 0; i < visibleRows; i++) {
			int index = i;
			if (index >= checkList.entries.size()) {
				break;
			}
			PureRefProject.CheckListEntry entry = checkList.entries.get(index);
			int y = listY + i * rowHeight;
			context.fill(bounds.x() + 6, y - 1, bounds.width() - 12, rowHeight - 2, index == editingCheckListRow && editingCheckList == checkList ? 0x77426788 : 0x44323A45);
			int iconX = bounds.x() + 10;
			int nameX = iconX + 18;
			renderCheckListIcon(context, entry, iconX, y, delta);
			Bounds amountBounds = getChecklistBoardAmountBounds(checkList, i);
			String amount = entry.currentAmount + "/" + entry.targetAmount;
			int amountRight = amountBounds.x() + amountBounds.width();
			int amountWidth = textRenderer.getWidth(amount);
			context.drawTextWithShadow(trimLibraryText(entry.label, amountRight - nameX - amountWidth - 8, Formatting.WHITE), nameX, y + 4, -1);
			if (amountBounds.contains(mouseX, mouseY)) {
				context.fill(amountBounds.x() - 2, amountBounds.y() - 1, amountBounds.width() + 4, amountBounds.height() + 2, 0x443C5268);
			}
			context.drawTextWithShadow(EmiPort.literal(amount, entry.currentAmount >= entry.targetAmount && entry.targetAmount > 0 ? Formatting.GREEN : Formatting.GOLD),
				amountRight - amountWidth, y + 4, -1);
		}
	}

	private void renderPureRefShape(EmiDrawContext context, PureRefProject.ShapeObject shape) {
		int color = shape.color;
		if (shape.shapeType == PureRefProject.ShapeType.BOX) {
			drawPureRefRect(context, shape.x, shape.y, shape.x2, shape.y2, color, shape.thickness);
		} else {
			drawPureRefLine(context, shape.x, shape.y, shape.x2, shape.y2, color, shape.thickness);
			if (shape.shapeType == PureRefProject.ShapeType.ARROW) {
				int dx = shape.x2 - shape.x;
				int dy = shape.y2 - shape.y;
				double len = Math.max(1, Math.sqrt(dx * dx + dy * dy));
				int hx1 = shape.x2 - (int) (dx / len * 10 - dy / len * 5);
				int hy1 = shape.y2 - (int) (dy / len * 10 + dx / len * 5);
				int hx2 = shape.x2 - (int) (dx / len * 10 + dy / len * 5);
				int hy2 = shape.y2 - (int) (dy / len * 10 - dx / len * 5);
				drawPureRefLine(context, shape.x2, shape.y2, hx1, hy1, color, shape.thickness);
				drawPureRefLine(context, shape.x2, shape.y2, hx2, hy2, color, shape.thickness);
			}
		}
	}

	private void renderPureRefSelection(EmiDrawContext context, PureRefProject.Object object) {
		Bounds bounds = getPureRefObjectBounds(object);
		context.fill(bounds.x() - 1, bounds.y() - 1, bounds.width() + 2, 1, 0xFF7BA7D0);
		context.fill(bounds.x() - 1, bounds.y() + bounds.height(), bounds.width() + 2, 1, 0xFF7BA7D0);
		context.fill(bounds.x() - 1, bounds.y(), 1, bounds.height(), 0xFF7BA7D0);
		context.fill(bounds.x() + bounds.width(), bounds.y(), 1, bounds.height(), 0xFF7BA7D0);
		if (object instanceof PureRefProject.ShapeObject shape) {
			renderShapeHandles(context, shape);
		} else if (object instanceof PureRefProject.TreeObject tree) {
			drawHandle(context, tree.x + tree.width, tree.y + tree.height, pureRefResizingTree);
		} else if (object instanceof PureRefProject.CheckListObject checkList) {
			drawHandle(context, checkList.x + checkList.width, checkList.y + checkList.height, pureRefResizingCheckList);
		}
	}

	private void renderShapeHandles(EmiDrawContext context, PureRefProject.ShapeObject shape) {
		if (shape.shapeType == PureRefProject.ShapeType.BOX) {
			int left = Math.min(shape.x, shape.x2);
			int right = Math.max(shape.x, shape.x2);
			int top = Math.min(shape.y, shape.y2);
			int bottom = Math.max(shape.y, shape.y2);
			drawHandle(context, left, top, activeShapeHandle == ShapeHandle.TOP_LEFT);
			drawHandle(context, right, top, activeShapeHandle == ShapeHandle.TOP_RIGHT);
			drawHandle(context, left, bottom, activeShapeHandle == ShapeHandle.BOTTOM_LEFT);
			drawHandle(context, right, bottom, activeShapeHandle == ShapeHandle.BOTTOM_RIGHT);
		} else {
			drawHandle(context, shape.x, shape.y, activeShapeHandle == ShapeHandle.START);
			drawHandle(context, shape.x2, shape.y2, activeShapeHandle == ShapeHandle.END);
		}
	}

	private void drawHandle(EmiDrawContext context, int x, int y, boolean active) {
		context.fill(x - 3, y - 3, 6, 6, active ? 0xFFF0D46A : 0xFF7BA7D0);
	}

	private Bounds getPureRefTreeBounds(PureRefProject.TreeObject tree) {
		return new Bounds(tree.x, tree.y, tree.width, tree.height);
	}

	private Bounds getPureRefNoteBounds(PureRefProject.NoteObject note) {
		List<String> wrapped = editingNote == note ? editingNoteLines : getPureRefNoteLines(note);
		int widest = 8;
		for (String line : wrapped) {
			widest = Math.max(widest, textRenderer.getWidth(line));
		}
		int height = Math.max(10, wrapped.size() * 10);
		return new Bounds(note.x, note.y, widest, height);
	}

	private Bounds getPureRefShapeBounds(PureRefProject.ShapeObject shape) {
		int x = Math.min(shape.x, shape.x2);
		int y = Math.min(shape.y, shape.y2);
		int w = Math.abs(shape.x2 - shape.x);
		int h = Math.abs(shape.y2 - shape.y);
		return new Bounds(x - 4, y - 4, Math.max(8, w + 8), Math.max(8, h + 8));
	}

	private Bounds getPureRefObjectBounds(PureRefProject.Object object) {
		if (object instanceof PureRefProject.TreeObject tree) {
			return getPureRefTreeBounds(tree);
		} else if (object instanceof PureRefProject.NoteObject note) {
			return getPureRefNoteBounds(note);
		} else if (object instanceof PureRefProject.CheckListObject checkList) {
			return new Bounds(checkList.x, checkList.y, checkList.width, checkList.height);
		} else if (object instanceof PureRefProject.ShapeObject shape) {
			return getPureRefShapeBounds(shape);
		}
		return new Bounds(object.x, object.y, 16, 16);
	}

	private PureRefProject.Object getPureRefObjectAt(int x, int y) {
		for (int i = project().objects.size() - 1; i >= 0; i--) {
			PureRefProject.Object object = project().objects.get(i);
			if (!(object instanceof PureRefProject.ShapeObject) && getPureRefObjectBounds(object).contains(x, y)) {
				return object;
			}
		}
		for (int i = project().objects.size() - 1; i >= 0; i--) {
			PureRefProject.Object object = project().objects.get(i);
			if (object instanceof PureRefProject.ShapeObject shape && getShapeHandle(shape, x, y) != ShapeHandle.NONE) {
				return object;
			}
		}
		for (int i = project().objects.size() - 1; i >= 0; i--) {
			PureRefProject.Object object = project().objects.get(i);
			if (object instanceof PureRefProject.ShapeObject && getPureRefObjectBounds(object).contains(x, y)) {
				return object;
			}
		}
		return null;
	}

	private boolean isNoteResizeHandle(PureRefProject.NoteObject note, int x, int y) {
		return false;
	}

	private boolean isTreeResizeHandle(PureRefProject.TreeObject tree, int x, int y) {
		return isNearPoint(x, y, tree.x + tree.width, tree.y + tree.height, 6);
	}

	private boolean isCheckListResizeHandle(PureRefProject.CheckListObject checkList, int x, int y) {
		return isNearPoint(x, y, checkList.x + checkList.width, checkList.y + checkList.height, 6);
	}

	private ShapeHandle getShapeHandle(PureRefProject.ShapeObject shape, int x, int y) {
		if (shape.shapeType == PureRefProject.ShapeType.BOX) {
			int left = Math.min(shape.x, shape.x2);
			int right = Math.max(shape.x, shape.x2);
			int top = Math.min(shape.y, shape.y2);
			int bottom = Math.max(shape.y, shape.y2);
			if (isNearPoint(x, y, left, top, 6)) {
				return ShapeHandle.TOP_LEFT;
			}
			if (isNearPoint(x, y, right, top, 6)) {
				return ShapeHandle.TOP_RIGHT;
			}
			if (isNearPoint(x, y, left, bottom, 6)) {
				return ShapeHandle.BOTTOM_LEFT;
			}
			if (isNearPoint(x, y, right, bottom, 6)) {
				return ShapeHandle.BOTTOM_RIGHT;
			}
			if (x >= left - 4 && x <= right + 4 && y >= top - 4 && y <= bottom + 4) {
				return ShapeHandle.MOVE;
			}
			return ShapeHandle.NONE;
		}
		if (isNearPoint(x, y, shape.x, shape.y, 6)) {
			return ShapeHandle.START;
		}
		if (isNearPoint(x, y, shape.x2, shape.y2, 6)) {
			return ShapeHandle.END;
		}
		if (distanceToSegment(x, y, shape.x, shape.y, shape.x2, shape.y2) <= 4) {
			return ShapeHandle.MOVE;
		}
		return ShapeHandle.NONE;
	}

	private boolean isNearPoint(int x, int y, int px, int py, int threshold) {
		return Math.abs(x - px) <= threshold && Math.abs(y - py) <= threshold;
	}

	private double distanceToSegment(int px, int py, int x1, int y1, int x2, int y2) {
		double dx = x2 - x1;
		double dy = y2 - y1;
		if (dx == 0 && dy == 0) {
			return Math.sqrt((px - x1) * (double) (px - x1) + (py - y1) * (double) (py - y1));
		}
		double t = ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy);
		t = Math.max(0, Math.min(1, t));
		double sx = x1 + t * dx;
		double sy = y1 + t * dy;
		double ox = px - sx;
		double oy = py - sy;
		return Math.sqrt(ox * ox + oy * oy);
	}

	private void insertSavedTreeIntoPureRef(int slot) {
		SavedRecipeTree saved = BoM.getSavedTree(slot);
		if (saved.isEmpty()) {
			return;
		}
		pendingTreeInsert = new PureRefProject.TreeObject(PureRefProject.nextObjectId(),
			0, 0, 340, 220,
			saved.name == null || saved.name.isBlank() ? "Saved Tree" : saved.name,
			saved.thumbnail, saved.snapshot);
	}

	private void beginShapeDraft(int x, int y) {
		PureRefProject.ShapeType shape = switch (pureRefTool) {
			case LINE -> PureRefProject.ShapeType.LINE;
			case ARROW -> PureRefProject.ShapeType.ARROW;
			case BOX -> PureRefProject.ShapeType.BOX;
			default -> null;
		};
		if (shape == null) {
			return;
		}
		activeShapeDraft = new PureRefProject.ShapeObject(PureRefProject.nextObjectId(), x, y, x, y, shape, 0xFF89B8E8, 2);
		project().objects.add(activeShapeDraft);
		selectedPureRefObject = activeShapeDraft;
		pureRefCreatingShape = true;
	}

	private boolean enterPureRefTreeFocus(PureRefProject.TreeObject treeObject) {
		if (treeObject == null || treeObject.snapshot == null) {
			return false;
		}
		SavedRecipeTree.TreeBuildResult result = treeObject.buildTree();
		if (result == null || result.tree == null) {
			return false;
		}
		pureRefBoardOffX = offX;
		pureRefBoardOffY = offY;
		pureRefBoardZoom = zoom;
		previousFocusedTree = BoM.tree;
		previousFocusedCraftingMode = BoM.craftingMode;
		BoM.tree = result.tree;
		BoM.craftingMode = treeObject.snapshot.craftingMode;
		loadWarning = result.missingData;
		offX = treeObject.snapshot.offX;
		offY = treeObject.snapshot.offY;
		zoom = treeObject.snapshot.zoom;
		focusedPureRefTree = treeObject;
		closeContextMenu();
		libraryOpen = false;
		projectLibraryOpen = false;
		insertOverlayOpen = false;
		recalculateTree();
		syncTopButtons();
		return true;
	}

	private void exitPureRefTreeFocus(boolean saveBack) {
		if (focusedPureRefTree == null) {
			return;
		}
		if (saveBack) {
			focusedPureRefTree.snapshot = createSnapshot();
			focusedPureRefTree.thumbnail = getTreeThumbnail();
			if (focusedPureRefTree.title == null || focusedPureRefTree.title.isBlank()) {
				focusedPureRefTree.title = getDefaultTreeName();
			}
			for (PureRefProject.Object object : project().objects) {
				if (object instanceof PureRefProject.CheckListObject checkList && checkList.autoSync
					&& Objects.equals(checkList.linkedTreeObjectId, focusedPureRefTree.id)) {
					populateCheckListFromTree(checkList, focusedPureRefTree, true);
				}
			}
			markBoardDirty();
		}
		BoM.tree = previousFocusedTree;
		BoM.craftingMode = previousFocusedCraftingMode;
		offX = pureRefBoardOffX;
		offY = pureRefBoardOffY;
		zoom = pureRefBoardZoom;
		focusedPureRefTree = null;
		loadWarning = false;
		closeContextMenu();
		syncTopButtons();
	}

	private static void drawPureRefLine(EmiDrawContext context, int x1, int y1, int x2, int y2, int color, int thickness) {
		double dx = x2 - x1;
		double dy = y2 - y1;
		int steps = Math.max(Math.abs((int) dx), Math.abs((int) dy));
		if (steps == 0) {
			context.fill(x1, y1, thickness, thickness, color);
			return;
		}
		for (int i = 0; i <= steps; i++) {
			int x = x1 + (int) Math.round(dx * i / steps);
			int y = y1 + (int) Math.round(dy * i / steps);
			context.fill(x - thickness / 2, y - thickness / 2, thickness, thickness, color);
		}
	}

	private static void drawPureRefRect(EmiDrawContext context, int x1, int y1, int x2, int y2, int color, int thickness) {
		int left = Math.min(x1, x2);
		int top = Math.min(y1, y2);
		int width = Math.abs(x2 - x1);
		int height = Math.abs(y2 - y1);
		context.fill(left, top, width, thickness, color);
		context.fill(left, top + height - thickness, width, thickness, color);
		context.fill(left, top, thickness, height, color);
		context.fill(left + width - thickness, top, thickness, height, color);
	}

	private class Cost {
		public FlatMaterialCost cost;
		public int x, y;
		public long alreadyDone = 0;
		public boolean remainder;

		public Cost(FlatMaterialCost cost, int x, int y, boolean remainder) {
			this.cost = cost;
			this.x = x;
			this.y = y;
			this.remainder = remainder;
		}

		public void render(EmiDrawContext context) {
			cost.ingredient.render(context.raw(), x, y, 0, ~(EmiIngredient.RENDER_AMOUNT | EmiIngredient.RENDER_REMAINDER));
			EmiRenderHelper.renderAmount(context, x, y, getAmountText());
		}

		public Text getAmountText() {
			long adjusted = cost.getEffectiveAmount();
			Text totalText;
			if (cost instanceof ChanceMaterialCost cmc) {
				totalText = EmiPort.append(EmiPort.literal("≈"), EmiRenderHelper.getAmountText(cost.ingredient, adjusted))
					.formatted(Formatting.GOLD);
			} else {
				totalText = EmiRenderHelper.getAmountText(cost.ingredient, adjusted);
			}
			if (!remainder && BoM.craftingMode) {
				long amount = alreadyDone;
				if (amount < adjusted) {
					Text amountText = amount == 0 ? EmiPort.literal("0") : (EmiRenderHelper.getAmountText(cost.ingredient, amount));
					MutableText text = EmiPort.append(EmiPort.literal("", Formatting.RED), amountText);
					text = EmiPort.append(text, EmiPort.literal("/"));
					text = EmiPort.append(text, totalText);
					return text;
				}
			}
			return totalText;
		}
	}

	private class Hover {
		public EmiIngredient stack;
		public MaterialNode node, resolve;
		public EmiRecipeCategory category;
		public Node renderNode;

		public Hover(EmiIngredient stack) {
			this.stack = stack;
		}

		public Hover(EmiIngredient stack, MaterialNode node, MaterialNode resolve, Node renderNode) {
			this.stack = stack;
			this.node = node;
			this.resolve = resolve;
			this.renderNode = renderNode;
		}

		public Hover(EmiRecipeCategory category, MaterialNode node, Node renderNode) {
			this.category = category;
			this.node = node;
			this.renderNode = renderNode;
		}

		public Hover(MaterialNode node, Node renderNode) {
			this.node = node;
			this.renderNode = renderNode;
		}

		public boolean drawTooltip(Screen screen, EmiDrawContext context, int mouseX, int mouseY) {
			if (stack != null) {
				List<TooltipComponent> list = Lists.newArrayList();
				list.addAll(stack.getTooltip());
				if (node != null && node.recipe != null) {
					list.add(new RecipeTooltipComponent(node.recipe));
					if (node.hasComparisons()) {
						list.add(EmiTooltipComponents.of(EmiPort.literal("Comparison expanded", Formatting.GRAY)));
					}
				}
				if (renderNode != null && renderNode.comparisonCandidate) {
					list.add(EmiTooltipComponents.of(EmiPort.literal("Left click to select this recipe path", Formatting.AQUA)));
				} else if (node != null) {
					list.add(EmiTooltipComponents.of(EmiPort.literal("Right click for node menu", Formatting.DARK_GRAY)));
				}
				if (node != null) {
					if (node.consumeChance != 1) {
						list.add(EmiTooltip.chance("consume", node.consumeChance));
					} else if (resolve != null && resolve.consumeChance != 1) {
						list.add(EmiTooltip.chance("consume", resolve.consumeChance));
					}
					if (node.produceChance != 1) {
						list.add(EmiTooltip.chance("produce", node.produceChance));
					}
				}
				EmiRenderHelper.drawTooltip(screen, context, list, mouseX, mouseY);
				return true;
			} else if (category != null) {
				EmiRenderHelper.drawTooltip(screen, context, category.getTooltip(), mouseX, mouseY);
				return true;
			}
			return false;
		}
	}

	private class Node {
		public Node parent = null;
		public MaterialNode resolution = null;
		public MaterialNode node;
		public String path;
		public int width, x, y, midOffset;
		public int layoutX, layoutY;
		public int outlineColor;
		public long amount;
		public ChanceState chance;
		public boolean comparisonHead = false;
		public boolean comparisonCandidate = false;
		public boolean comparisonSelected = false;
		public long comparisonCost = 0;
		public MaterialNode compareOwner = null;

		public Node(MaterialNode node, long amount, int x, int y, ChanceState chance, String path, int outlineColor) {
			this.node = node;
			this.path = path;
			this.outlineColor = outlineColor;
			if (node.recipe != null && !node.hasComparisons()) {
				width = 42;
			} else {
				width = 16;
			}
			this.amount = amount;
			this.x = x;
			this.y = y;
			this.layoutX = x;
			this.layoutY = y;
			this.chance = chance;
			int tw = EmiRenderHelper.getAmountOverflow(getAmountText());
			width += tw;
			midOffset = tw / -2;
		}

		public void render(EmiDrawContext context, int mouseX, int mouseY, float delta) {
			if (parent != null) {
				context.push();

				setColor(context, outlineColor, parent.node, node.consumeChance != 1 || (resolution != null && resolution.consumeChance != 1), false);
				
				int nx = x;
				int ny = y;
				int px = parent.x;
				int py = parent.y;
				int off = NODE_VERTICAL_SPACING - 1;
				if (resolution != null) {
					context.drawTexture(EmiRenderHelper.WIDGETS, x - 3, y - 19, 9, 192, 7, 7);
					drawLine(context, nx, y - 12, nx, ny - 11);
					drawLine(context, nx, py + off, nx, y - 19);
				} else {
					drawLine(context, nx, ny - 11, nx, py + off);
				}
				setColor(context, outlineColor, parent.node, false, false);
				drawLine(context, px, py + off, nx, py + off);
				context.pop();
			}
			int xo = 0;
			if (node.recipe != null && !comparisonHead) {
				int lx = x - width / 2;
				int ly = y - 11;
				int hx = x + width / 2;
				int hy = y + 10;
				context.push();

				setColor(context, outlineColor, node, node.produceChance != 1, false);

				if (node.state != FoldState.EXPANDED) {
					drawLine(context, x, hy + 1, x, hy + 3);
				} else {
					drawLine(context, x, hy + 1, x, hy + 8);
				}

				boolean hovered = mouseX >= lx && mouseY >= ly && mouseX <= hx && mouseY <= hy;
				setColor(context, outlineColor, node, node.produceChance != 1, hovered);
				if (comparisonCandidate && comparisonSelected) {
					context.setColor(0.93f, 0.82f, 0.35f, 1f);
				}
				drawLine(context, lx, ly, lx, hy);
				drawLine(context, hx, ly, hx, hy);
				drawLine(context, lx, ly, hx, ly);
				drawLine(context, lx, hy, hx, hy);
				EmiRecipeCategory cat = node.recipe.getCategory();
				cat.renderSimplified(context.raw(), x - 18 + midOffset, y - 8, delta);
				xo = 11;
				context.pop();
				if (comparisonCandidate) {
					MicroTextRenderer.render(context, comparisonCost, false, 18, x + width / 2 - 1, y - 12, comparisonSelected ? 0xFFF0D46A : 0xFFB5C6D8);
				}
			}
			context.setColor(1f, 1f, 1f, 1f);
			node.ingredient.render(context.raw(), x + xo - 8 + midOffset, y - 8, 0, -1);
			EmiRenderHelper.renderAmount(context, x + xo - 8 + midOffset, y - 8, getAmountText());
		}

		public void setColor(EmiDrawContext context, int baseColor, MaterialNode node, boolean chanced, boolean hovered) {
			if (baseColor == -1) {
				context.setColor(1f, 1f, 1f, 1f);
			} else {
				float r = ((baseColor >> 16) & 0xFF) / 255f;
				float g = ((baseColor >> 8) & 0xFF) / 255f;
				float b = (baseColor & 0xFF) / 255f;
				context.setColor(r, g, b, 1f);
			}
			if (chanced) {
				context.setColor(0.8f, 0.6f, 0.1f, 1f);
			}
			if (BoM.craftingMode) {
				if (node.progress == ProgressState.COMPLETED) {
					context.setColor(0.1f, 0.8f, 0.5f, 1f);
				} else if (node.progress == ProgressState.PARTIAL) {
					context.setColor(0.8f, 0.2f, 0.9f, 1f);
				}
			}
			if (node.missing) {
				context.setColor(0.95f, 0.35f, 0.35f, 1f);
			}
			if (hovered) {
				context.setColor(0.5f, 0.6f, 1f, 1f);
			}
		}

		public Text getAmountText() {
			if (chance.chanced()) {
				long a = Math.round(amount * chance.chance());
				a = Math.max(a, node.amount);
				return EmiPort.append(EmiPort.literal("≈"),
						EmiRenderHelper.getAmountText(node.ingredient, a))
					.formatted(Formatting.GOLD);
			} else {
				return EmiRenderHelper.getAmountText(node.ingredient, amount);
			}
		}

		public int getLeft() {
			return x - width / 2;
		}

		public int getRight() {
			return x + width / 2;
		}

		public int getTop() {
			return y - 11;
		}

		public int getBottom() {
			return y + 10;
		}

		public String getStructureKey() {
			String recipeId = node.recipe != null && node.recipe.getId() != null ? node.recipe.getId().toString() : "none";
			return recipeId + "|" + node.state.name() + "|" + node.hasComparisons();
		}

		public Hover getHover(int mouseX, int mouseY) {
			if (resolution != null) {
				if (mouseX >= x - 4 && mouseX < x + 4 && mouseY >= y - 19 && mouseY < y - 11) {
					return new Hover(resolution.ingredient, resolution, null, this);
				}
			}
			int imx = mouseX;
			if (node.recipe != null && !comparisonHead) {
				if (mouseX >= x - 18 + midOffset && mouseX < x - 2 + midOffset && mouseY >= y - 8 && mouseY < y + 8) {
					return new Hover(node.recipe.getCategory(), node, this);
				}
				imx -= 11;
			}
			if (imx >= x - 8 + midOffset && imx < x + 8 + midOffset && mouseY >= y - 8 && mouseY < y + 8) {
				return new Hover(node.ingredient, node, resolution, this);
			}
			int lx = x - width / 2;
			int ly = y - 11;
			int hx = x + width / 2;
			int hy = y + 10;
			if (mouseX >= lx && mouseY >= ly && mouseX <= hx && mouseY <= hy) {
				return new Hover(node, this);
			}
			return null;
		}
	}

	private class TreeVolume {
		public List<Width> widths = Lists.newArrayList();
		public List<Node> nodes = Lists.newArrayList();

		public TreeVolume(MaterialNode node, long amount, int y, ChanceState chance, String path, int outlineColor) {
			Node head = new Node(node, amount, 0, y, chance, path, outlineColor);
			int l = head.width / 2;
			widths.add(new Width(-l, head.width - l));
			nodes.add(head);
		}

		public void addHead(MaterialNode node, long amount, int y, ChanceState chance, String path, int outlineColor) {
			int x = (getLeft(0) + getRight(0)) / 2;
			Node newNode = new Node(node, amount, x, y, chance, path, outlineColor);
			for (Node n : nodes) {
				if (n.parent == null) {
					n.parent = newNode;
				}
				n.y += NODE_VERTICAL_SPACING;
			}
			int l = newNode.width / 2;
			widths.add(0, new Width(x - l, x + newNode.width - l));
			nodes.add(0, newNode);
		}

		public int getDepth() {
			return widths.size();
		}

		public int getMinLeft() {
			int m = getLeft(0);
			for (int i = 1; i < getDepth(); i++) {
				m = Math.min(m, getLeft(i));
			}
			return m;
		}

		public int getMaxRight() {
			int m = getRight(0);
			for (int i = 1; i < getDepth(); i++) {
				m = Math.max(m, getRight(i));
			}
			return m;
		}

		public int getLeft(int depth) {
			return widths.get(depth).left;
		}

		public int getRight(int depth) {
			return widths.get(depth).right;
		}

		public void addToRight(TreeVolume other) {
			addToRight(other, NODE_HORIZONTAL_SPACING);
		}

		public void addToRight(TreeVolume other, int spacing) {
			int rOff = getRight(0) - other.getLeft(0) + spacing;
			for (int i = 1; i < getDepth() && i < other.getDepth(); i++) {
				rOff = Math.max(rOff, getRight(i) - other.getLeft(i) + spacing);
			}
			for (int i = 0; i < other.getDepth(); i++) {
				if (i < getDepth()) {
					widths.get(i).right = other.getRight(i) + rOff;
				} else {
					widths.add(new Width(other.getLeft(i) + rOff, other.getRight(i) + rOff));
				}
			}
			for (Node node : other.nodes) {
				node.x += rOff;
				nodes.add(node);
			}
		}

		public static TreeVolume combineComparisonLanes(List<TreeVolume> volumes, int spacing) {
			TreeVolume combined = null;
			int laneWidth = 0;
			for (TreeVolume volume : volumes) {
				laneWidth = Math.max(laneWidth, volume.getMaxRight() - volume.getMinLeft());
			}
			int laneLeft = 0;
			for (TreeVolume volume : volumes) {
				int volumeWidth = volume.getMaxRight() - volume.getMinLeft();
				int offset = laneLeft - volume.getMinLeft() + (laneWidth - volumeWidth) / 2;
				for (int i = 0; i < volume.getDepth(); i++) {
					volume.widths.get(i).left += offset;
					volume.widths.get(i).right += offset;
				}
				for (Node node : volume.nodes) {
					node.x += offset;
				}
				if (combined == null) {
					combined = volume;
				} else {
					combined.mergeAligned(volume);
				}
				laneLeft += laneWidth + spacing;
			}
			return combined;
		}

		private void mergeAligned(TreeVolume other) {
			for (int i = 0; i < other.getDepth(); i++) {
				if (i < getDepth()) {
					widths.get(i).left = Math.min(widths.get(i).left, other.getLeft(i));
					widths.get(i).right = Math.max(widths.get(i).right, other.getRight(i));
				} else {
					widths.add(new Width(other.getLeft(i), other.getRight(i)));
				}
			}
			nodes.addAll(other.nodes);
		}

		private static class Width {
			private int left, right;

			public Width(int left, int right) {
				this.left = left;
				this.right = right;
			}
		}
	}
}
