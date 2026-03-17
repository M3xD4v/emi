package dev.emi.emi.screen;

import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.lwjgl.glfw.GLFW;

import com.google.common.collect.Lists;
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
import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.bom.BoM;
import dev.emi.emi.bom.ChanceMaterialCost;
import dev.emi.emi.bom.ChanceState;
import dev.emi.emi.bom.FlatMaterialCost;
import dev.emi.emi.bom.FoldState;
import dev.emi.emi.bom.MaterialNode;
import dev.emi.emi.bom.MaterialTree;
import dev.emi.emi.bom.ProgressState;
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
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;

public class BoMScreen extends Screen {
	private static final int NODE_WIDTH = 30;
	private static final int NODE_HORIZONTAL_SPACING = 8;
	private static final int NODE_VERTICAL_SPACING = 20;
	private static final int COST_HORIZONTAL_SPACING = 8;
	private static int zoom = 0;
	private Bounds batches = new Bounds(-24, -50, 48, 26);
	private Bounds mode = new Bounds(-24, -50, 16, 16);
	private Bounds help = new Bounds(0, 0, 16, 16);
	private double offX, offY;
	private List<Node> nodes = Lists.newArrayList();
	private List<Cost> costs = Lists.newArrayList();
	private ButtonWidget libraryButton;
	private EmiPlayerInventory playerInv;
	private boolean hasRemainders = false;;
	public HandledScreen<?> old;
	private int nodeWidth = 0;
	private int nodeHeight = 0;
	private int lastMouseX, lastMouseY;
	private double scrollAcc = 0;
	private Node draggedNode;
	private boolean draggingBranch = false;
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
	private String actionNodePath = null;

	public BoMScreen(HandledScreen<?> old) {
		super(EmiPort.translatable("screen.emi.recipe_tree"));
		this.old = old;
	}

	public void init() {
		this.clearChildren();
		libraryButton = EmiPort.newButton(width - 110, 8, 102, 20, EmiPort.literal("Tree Library"), button -> {
			libraryOpen = !libraryOpen;
			renamingSlot = -1;
			libraryScrollTarget = MathHelper.clamp(libraryScrollTarget, 0, getLibraryMaxScroll());
			libraryScroll = MathHelper.clamp(libraryScroll, 0, getLibraryMaxScroll());
			updateRenameField();
		});
		this.addDrawableChild(libraryButton);
		renameField = new TextFieldWidget(textRenderer, 0, 0, 180, 18, EmiPort.literal(""));
		renameField.setMaxLength(64);
		renameField.setVisible(false);
		this.addDrawableChild(renameField);
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
		recalculateTree();
	}

	public void recalculateTree() {
		help = new Bounds(width - 18, height - 18, 16, 16);
		if (BoM.tree != null) {
			TreeVolume volume = addNewNodes(BoM.tree.goal, BoM.tree.batches, 1, 0, ChanceState.DEFAULT, "0", -1, 0);
			nodes = volume.nodes;
			int horizontalOffset = (volume.getMaxRight() + volume.getMinLeft()) / 2;
			for (Node node : volume.nodes) {
				node.x -= horizontalOffset;
			}
			applyNodeOffsets();
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

	private void applyNodeOffsets() {
		if (BoM.tree == null) {
			return;
		}
		for (Node node : nodes) {
			MaterialTree.NodeOffset offset = BoM.tree.nodeOffsets.get(node.path);
			if (offset != null) {
				node.x += offset.x();
				node.y += offset.y();
			}
		}
	}

	private Bounds getLibraryPanelBounds() {
		int panelWidth = Math.min(compactLibrary ? 340 : 430, width - 24);
		int panelHeight = Math.min(height - 56, compactLibrary ? 314 : 392);
		return new Bounds(width - panelWidth - 12, 36, panelWidth, panelHeight);
	}

	private int getLibraryRowHeight() {
		return compactLibrary ? 36 : 54;
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
		int height = compactLibrary ? 16 : 18;
		return new Bounds(right - width, row.y() + row.height() / 2 - height / 2, width, height);
	}

	private Bounds getLibraryHeaderButton(Bounds panel) {
		return new Bounds(panel.x() + panel.width() - 76, panel.y() + 8, 64, 18);
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
		RenderSystem.disableDepthTest();
		Bounds panel = getLibraryPanelBounds();
		context.fill(panel.x() - 6, panel.y() - 6, panel.width() + 12, panel.height() + 12, 0x33000000);
		context.fill(panel.x() - 1, panel.y() - 1, panel.width() + 2, panel.height() + 2, 0x99354B63);
		context.fill(panel.x(), panel.y(), panel.width(), panel.height(), 0xF1141B24);
		context.fill(panel.x(), panel.y(), panel.width(), 30, 0xFF1D2A38);
		context.fill(panel.x(), panel.y() + 30, panel.width(), 1, 0xAA56738F);
		context.drawTextWithShadow(EmiPort.literal("Recipe Tree Library", Formatting.WHITE), panel.x() + 12, panel.y() + 10, -1);
		context.drawTextWithShadow(EmiPort.literal(compactLibrary ? "Compact" : "Comfort", Formatting.GRAY), panel.x() + 126, panel.y() + 10, -1);
		renderLibraryAction(context, getLibraryHeaderButton(panel), compactLibrary ? "Comfort" : "Compact", true, mouseX, mouseY);
		context.drawTextWithShadow(EmiPort.literal("Double-click to load", Formatting.DARK_GRAY), panel.x() + 208, panel.y() + 10, -1);
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
			EmiIngredient thumbnail = saved.thumbnail == null ? EmiStack.EMPTY : saved.thumbnail;
			if (!thumbnail.isEmpty()) {
				thumbnail.render(raw, row.x() + 10, row.y() + row.height() / 2 - 8, delta, 0);
			}
			Text title = saved.isEmpty()
				? EmiPort.literal((slot + 1) + ". Empty Slot", Formatting.DARK_GRAY)
				: EmiPort.literal((slot + 1) + ". " + (saved.name.isBlank() ? getDefaultTreeName() : saved.name));
			context.drawTextWithShadow(title, row.x() + 34, row.y() + (compactLibrary ? 6 : 9), -1);
			if (saved.hasMissingData()) {
				context.drawTextWithShadow(EmiPort.literal("Missing data", Formatting.RED), row.x() + 34, row.y() + (compactLibrary ? 18 : 29), -1);
			} else if (!compactLibrary) {
				context.drawTextWithShadow(EmiPort.literal(saved.isEmpty() ? "Save current tree here" : "Stored tree snapshot", Formatting.DARK_GRAY), row.x() + 34, row.y() + 29, -1);
			}

			int right = row.x() + row.width() - 10;
			Bounds override = getLibraryButtonBounds(row, right, "Override");
			right = override.x() - 6;
			Bounds delete = getLibraryButtonBounds(row, right, "Delete");
			right = delete.x() - 6;
			Bounds rename = getLibraryButtonBounds(row, right, "Rename");
			right = rename.x() - 6;
			Bounds save = getLibraryButtonBounds(row, right, "Save");
			renderLibraryAction(context, save, "Save", canSaveToSlot(saved), mouseX, mouseY);
			renderLibraryAction(context, rename, "Rename", !saved.isEmpty(), mouseX, mouseY);
			renderLibraryAction(context, delete, "Delete", !saved.isEmpty(), mouseX, mouseY);
			renderLibraryAction(context, override, "Override", canOverrideSlot(saved), mouseX, mouseY);
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
			int right = row.x() + row.width() - 10;
			Bounds override = getLibraryButtonBounds(row, right, "Override");
			right = override.x() - 6;
			Bounds delete = getLibraryButtonBounds(row, right, "Delete");
			right = delete.x() - 6;
			Bounds rename = getLibraryButtonBounds(row, right, "Rename");
			right = rename.x() - 6;
			Bounds save = getLibraryButtonBounds(row, right, "Save");
			if (save.contains((int) mouseX, (int) mouseY) && canSaveToSlot(saved)) {
				saveTreeToSlot(slot, false);
				selectedLibrarySlot = slot;
				return true;
			}
			if (rename.contains((int) mouseX, (int) mouseY) && !saved.isEmpty()) {
				selectedLibrarySlot = slot;
				renamingSlot = slot;
				updateRenameField();
				return true;
			}
			if (delete.contains((int) mouseX, (int) mouseY) && !saved.isEmpty()) {
				BoM.deleteSavedTree(slot);
				selectedLibrarySlot = Math.min(slot, BoM.TREE_SLOT_COUNT - 1);
				renamingSlot = -1;
				updateRenameField();
				return true;
			}
			if (override.contains((int) mouseX, (int) mouseY) && canOverrideSlot(saved)) {
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

	private Node getActionNode() {
		if (actionNodePath == null) {
			return null;
		}
		for (Node node : nodes) {
			if (actionNodePath.equals(node.path)) {
				return node;
			}
		}
		actionNodePath = null;
		return null;
	}

	private Bounds getActionStripBounds(Node node) {
		int width = 122;
		int height = 18;
		return new Bounds(node.x + node.width / 2 + 10, node.y - 9, width, height);
	}

	private Bounds getActionButtonBounds(Bounds strip, int index) {
		return new Bounds(strip.x() + index * 31, strip.y(), 28, strip.height());
	}

	private String getActionLabel(ActionButton action) {
		return switch (action) {
			case COMPARE -> "Cmp";
			case AUTO -> "Auto";
			case CLEAR -> "Clear";
			case RECIPES -> "Open";
		};
	}

	private boolean isActionEnabled(ActionButton action, Node node) {
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
		};
	}

	private void renderActionStrip(EmiDrawContext context, int mouseX, int mouseY) {
		Node node = getActionNode();
		if (node == null || node.comparisonCandidate) {
			return;
		}
		Bounds strip = getActionStripBounds(node);
		context.fill(strip.x() - 1, strip.y() - 1, strip.width() + 2, strip.height() + 2, 0xAA0E141B);
		context.fill(strip.x(), strip.y(), strip.width(), strip.height(), 0xF11C2936);
		for (int i = 0; i < ActionButton.values().length; i++) {
			ActionButton action = ActionButton.values()[i];
			Bounds bounds = getActionButtonBounds(strip, i);
			boolean active = isActionEnabled(action, node);
			int color = active ? (bounds.contains(mouseX, mouseY) ? 0xFF6E97BF : 0xFF355069) : 0xFF26303A;
			context.fill(bounds.x(), bounds.y(), bounds.width(), bounds.height(), color);
			context.drawCenteredText(EmiPort.literal(getActionLabel(action), active ? Formatting.WHITE : Formatting.DARK_GRAY),
				bounds.x() + bounds.width() / 2, bounds.y() + 5);
		}
	}

	private boolean handleActionClick(int mouseX, int mouseY) {
		Node node = getActionNode();
		if (node == null) {
			return false;
		}
		Bounds strip = getActionStripBounds(node);
		if (!strip.contains(mouseX, mouseY)) {
			return false;
		}
		for (int i = 0; i < ActionButton.values().length; i++) {
			ActionButton action = ActionButton.values()[i];
			Bounds bounds = getActionButtonBounds(strip, i);
			if (!bounds.contains(mouseX, mouseY) || !isActionEnabled(action, node)) {
				continue;
			}
			switch (action) {
				case COMPARE -> {
					if (toggleComparison(node.node)) {
						recalculateTree();
					}
				}
				case AUTO -> {
					Hover hover = new Hover(node.node.ingredient, node.node, node.resolution, node);
					if (getAutoResolutions(hover, BoM.tree::addResolution)) {
						recalculateTree();
					}
				}
				case CLEAR -> {
					BoM.tree.addResolution(node.node.ingredient, null);
					node.node.clearComparisons();
					recalculateTree();
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
			}
			return true;
		}
		return false;
	}

	@Override
	public void render(DrawContext raw, int mouseX, int mouseY, float delta) {
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
			renderActionStrip(context, mx, my);
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
		context.drawTextWithShadow(EmiPort.literal("LMB node: actions  |  LMB compare: select  |  RMB: fold  |  MMB drag: pan  |  Ctrl+MMB: move node  |  Ctrl+Shift+MMB: move branch", Formatting.DARK_GRAY),
			8, height - 28, -1);
		if (libraryOpen) {
			libraryScroll += (libraryScrollTarget - libraryScroll) * 0.35f;
			if (Math.abs(libraryScrollTarget - libraryScroll) < 0.01f) {
				libraryScroll = libraryScrollTarget;
			}
			updateRenameField();
			renderLibraryOverlay(context, raw, mouseX, mouseY, delta);
		}
		super.render(raw, mouseX, mouseY, delta);

		Hover hover = getHoveredStack(mouseX, mouseY);
		if (hover != null) {
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
			list.add(EmiTooltipComponents.of(EmiPort.literal("Left click node: open actions", Formatting.GRAY)));
			list.add(EmiTooltipComponents.of(EmiPort.literal("Left click compare candidate: select recipe", Formatting.GRAY)));
			list.add(EmiTooltipComponents.of(EmiPort.literal("Right click: fold or unfold", Formatting.GRAY)));
			list.add(EmiTooltipComponents.of(EmiPort.literal("Middle drag: pan view", Formatting.GRAY)));
			list.add(EmiTooltipComponents.of(EmiPort.literal("Ctrl + Middle drag: move node", Formatting.GRAY)));
			list.add(EmiTooltipComponents.of(EmiPort.literal("Ctrl + Shift + Middle drag: move branch", Formatting.GRAY)));
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

	private enum ActionButton {
		COMPARE,
		AUTO,
		CLEAR,
		RECIPES
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
			TreeVolume left = null;
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
				if (left == null) {
					left = volume;
				} else {
					left.addToRight(volume);
				}
			}
			if (left != null) {
				left.addHead(node, multiplier, depth * NODE_VERTICAL_SPACING, chance, path, outlineColor);
				if (!left.nodes.isEmpty()) {
					left.nodes.get(0).comparisonHead = true;
				}
				return left;
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
		BoM.tree.addResolution(compareOwner.ingredient, recipe);
		compareOwner.clearComparisons();
		return true;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (handleLibraryClick(mouseX, mouseY, button)) {
			return true;
		}
		float scale = getScale();
		int mx = (int) ((mouseX - width / 2) / scale - offX);
		int my = (int) ((mouseY - height / 2) / scale - offY);
		if (button == 0 && handleActionClick(mx, my)) {
			return true;
		}
		Hover hover = getHoveredStack((int) mouseX, (int) mouseY);
		if (button == 2 && EmiInput.isControlDown() && hover != null && hover.node != null) {
			for (Node node : nodes) {
				if (node.node == hover.node) {
					draggedNode = node;
					draggingBranch = EmiInput.isShiftDown();
					dragLastTreeX = mx;
					dragLastTreeY = my;
					return true;
				}
			}
		}
		if (hover != null) {
			if (button == 0 && hover.renderNode != null && hover.renderNode.comparisonCandidate) {
				if (selectComparison(hover.renderNode.compareOwner, hover.node.recipe)) {
					actionNodePath = null;
					recalculateTree();
					return true;
				}
			}
			if (button == 0 && EmiInput.isControlDown() && EmiInput.isShiftDown() && hover.node != null) {
				if (hover.renderNode != null && hover.renderNode.comparisonCandidate) {
					if (selectComparison(hover.renderNode.compareOwner, hover.node.recipe)) {
						actionNodePath = null;
						recalculateTree();
						return true;
					}
				} else if (toggleComparison(hover.node)) {
					actionNodePath = hover.renderNode != null ? hover.renderNode.path : actionNodePath;
					recalculateTree();
					return true;
				}
			}
			if (button == 1 && hover.node != null && hover.node.recipe != null) {
				if (!(hover.node.recipe instanceof EmiResolutionRecipe)) {
					if (hover.node.state == FoldState.EXPANDED) {
						hover.node.state = FoldState.COLLAPSED;
					} else {
						hover.node.state = FoldState.EXPANDED;
					}
				}
				recalculateTree();
				return true;
			}
			if (hover.stack != null) {
				if (button == 0 && hover.node != null) {
					actionNodePath = hover.renderNode != null ? hover.renderNode.path : null;
					return true;
				} else if (button == 0) {
					EmiApi.displayRecipes(hover.stack);
					RecipeScreen.resolve = hover.stack;
					MinecraftClient client = MinecraftClient.getInstance();
					client.currentScreen.init(client, client.currentScreen.width, client.currentScreen.height);
					return true;
				}
			} else if (button == 0 && hover.node != null) {
				actionNodePath = hover.renderNode != null ? hover.renderNode.path : null;
				return true;
			}
			actionNodePath = null;
		} else if (mode.contains(mx, my)) {
			MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0f));
			BoM.craftingMode = !BoM.craftingMode;
			recalculateTree();
		} else if (batches.contains(mx, my) && BoM.tree != null) {
			long ideal = BoM.tree.cost.getIdealBatch(BoM.tree.goal, 1, 1);
			if (ideal != BoM.tree.batches) {
				MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0f));
				BoM.tree.batches = ideal;
				recalculateTree();
			}
		} else if (button == 0) {
			actionNodePath = null;
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
		if (button == 2 && draggedNode != null) {
			draggedNode = null;
			draggingBranch = false;
			return true;
		}
		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
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
		if (button == 2 && draggedNode != null && EmiInput.isControlDown()) {
			float scale = getScale();
			int mx = (int) ((mouseX - width / 2) / scale - offX);
			int my = (int) ((mouseY - height / 2) / scale - offY);
			int dx = mx - dragLastTreeX;
			int dy = my - dragLastTreeY;
			dragLastTreeX = mx;
			dragLastTreeY = my;
			moveDraggedNode(dx, dy);
			return true;
		}
		if (button == 2) {
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
					list.add(EmiTooltipComponents.of(EmiPort.literal("Left click for node actions", Formatting.DARK_GRAY)));
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
			int rOff = getRight(0) - other.getLeft(0) + NODE_HORIZONTAL_SPACING;
			for (int i = 1; i < getDepth() && i < other.getDepth(); i++) {
				rOff = Math.max(rOff, getRight(i) - other.getLeft(i) + NODE_HORIZONTAL_SPACING);
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

		private static class Width {
			private int left, right;

			public Width(int left, int right) {
				this.left = left;
				this.right = right;
			}
		}
	}
}
