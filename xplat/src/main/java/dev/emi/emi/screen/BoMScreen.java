package dev.emi.emi.screen;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
	private static final int COMPARISON_HORIZONTAL_SPACING = 28;
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
		recalculateTree(null);
	}

	public void recalculateTree(String resetPath) {
		help = new Bounds(width - 18, height - 18, 16, 16);
		if (BoM.tree != null) {
			Map<String, NodePosition> previousPositions = nodes.stream()
				.filter(n -> resetPath == null || !n.path.startsWith(resetPath + "/"))
				.collect(Collectors.toMap(n -> n.path, n -> new NodePosition(n.x, n.y, n.getStructureKey()), (a, b) -> b));
			LayoutBlock layout = buildLayoutBlock(BoM.tree.goal, BoM.tree.batches, 1, ChanceState.DEFAULT, "0", -1, 0);
			nodes = Lists.newArrayList();
			placeLayoutBlock(layout, -layout.subtreeWidth / 2, 0, null);
			applyAnchoredPositions(previousPositions);
			if (!nodes.isEmpty()) {
				Node node = nodes.get(0);
				int width = textRenderer.getWidth("x" + BoM.tree.batches);
				batches = new Bounds(node.x + node.width / 2 + 6, node.y - 10, width + 12, 22);
			}

			nodeWidth = getNodeMaxRight() - getNodeMinLeft();
			nodeHeight = getRenderedNodeHeight();
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

	private LayoutBlock buildLayoutBlock(MaterialNode node, long multiplier, long divisor, ChanceState chance, String path, int outlineColor, int colorSeed) {
		long amount;
		if (node.catalyst) {
			amount = node.amount;
		} else {
			amount = node.amount * (int) Math.ceil(multiplier / (float) divisor);
		}
		if (node.hasComparisons()) {
			List<LayoutBlock> children = Lists.newArrayList();
			int laneWidth = 0;
			for (int i = 0; i < node.comparisons.size(); i++) {
				MaterialNode.Comparison comparison = node.comparisons.get(i);
				int childColor = getBranchColor(path + "/cmp", i, colorSeed);
				LayoutBlock child = buildLayoutBlock(comparison.node, amount, comparison.node.divisor, chance,
					path + "/@cmp/" + i, childColor, colorSeed + i + 1);
				child.comparisonCandidate = true;
				child.comparisonSelected = comparison.selected;
				child.comparisonCost = comparison.estimatedCost;
				child.compareOwner = node;
				laneWidth = Math.max(laneWidth, child.subtreeWidth);
				children.add(child);
			}
			int selfWidth = measureNodeWidth(node, amount, chance);
			int childrenWidth = children.isEmpty() ? 0 : laneWidth * children.size() + COMPARISON_HORIZONTAL_SPACING * (children.size() - 1);
			LayoutBlock block = new LayoutBlock(node, amount, chance, path, outlineColor, selfWidth, Math.max(selfWidth, childrenWidth));
			block.comparisonHead = true;
			block.children.addAll(children);
			block.childLaneWidth = laneWidth;
			block.childSpacing = COMPARISON_HORIZONTAL_SPACING;
			block.equalChildLanes = true;
			return block;
		}
		if (node.recipe != null && !node.children.isEmpty() && node.state == FoldState.EXPANDED) {
			ChanceState produced = chance.produce(node.produceChance);
			if (node.recipe instanceof EmiResolutionRecipe) {
				LayoutBlock child = buildLayoutBlock(node.children.get(0), amount, node.divisor, produced, path + "/0", outlineColor, colorSeed);
				child.resolution = node;
				return child;
			}
			List<LayoutBlock> children = Lists.newArrayList();
			int childrenWidth = 0;
			for (int i = 0; i < node.children.size(); i++) {
				ChanceState consumed = produced.consume(node.children.get(i).consumeChance);
				int childColor = getBranchColor(path, i, colorSeed);
				LayoutBlock child = buildLayoutBlock(node.children.get(i), amount, node.divisor, consumed,
					path + "/" + i, childColor, colorSeed + i + 1);
				if (!children.isEmpty()) {
					childrenWidth += NODE_HORIZONTAL_SPACING;
				}
				childrenWidth += child.subtreeWidth;
				children.add(child);
			}
			int selfWidth = measureNodeWidth(node, amount, chance);
			LayoutBlock block = new LayoutBlock(node, amount, chance, path, outlineColor, selfWidth, Math.max(selfWidth, childrenWidth));
			block.children.addAll(children);
			block.childSpacing = NODE_HORIZONTAL_SPACING;
			return block;
		}
		int selfWidth = measureNodeWidth(node, amount, chance);
		return new LayoutBlock(node, amount, chance, path, outlineColor, selfWidth, selfWidth);
	}

	private void placeLayoutBlock(LayoutBlock block, int left, int depth, Node parent) {
		int centerX = left + block.subtreeWidth / 2;
		Node render = new Node(block.node, block.amount, centerX, depth * NODE_VERTICAL_SPACING, block.chance, block.path, block.outlineColor);
		render.layoutX = render.x;
		render.layoutY = render.y;
		render.parent = parent;
		render.resolution = block.resolution;
		render.comparisonHead = block.comparisonHead;
		render.comparisonCandidate = block.comparisonCandidate;
		render.comparisonSelected = block.comparisonSelected;
		render.comparisonCost = block.comparisonCost;
		render.compareOwner = block.compareOwner;
		nodes.add(render);
		if (block.children.isEmpty()) {
			return;
		}
		int childrenWidth;
		if (block.equalChildLanes) {
			childrenWidth = block.childLaneWidth * block.children.size() + block.childSpacing * (block.children.size() - 1);
		} else {
			childrenWidth = block.children.stream().mapToInt(c -> c.subtreeWidth).sum() + block.childSpacing * (block.children.size() - 1);
		}
		int childLeft = left + (block.subtreeWidth - childrenWidth) / 2;
		for (LayoutBlock child : block.children) {
			int allocatedWidth = block.equalChildLanes ? block.childLaneWidth : child.subtreeWidth;
			int actualLeft = childLeft + (allocatedWidth - child.subtreeWidth) / 2;
			placeLayoutBlock(child, actualLeft, depth + 1, render);
			childLeft += allocatedWidth + block.childSpacing;
		}
	}

	private int measureNodeWidth(MaterialNode node, long amount, ChanceState chance) {
		return new Node(node, amount, 0, 0, chance, "", -1).width;
	}

	private int getNodeMinLeft() {
		return nodes.stream().mapToInt(Node::getLeft).min().orElse(0);
	}

	private int getNodeMaxRight() {
		return nodes.stream().mapToInt(Node::getRight).max().orElse(0);
	}

	private int getRenderedNodeHeight() {
		return nodes.stream().mapToInt(n -> n.y / NODE_VERTICAL_SPACING + 1).max().orElse(1);
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
					Hover hover = new Hover(node.node.ingredient, node.node, node.resolution, node);
					if (getAutoResolutions(hover, BoM.tree::addResolution)) {
						recalculateTree(node.path);
					}
				}
				case CLEAR -> {
					BoM.tree.addResolution(node.node.ingredient, null);
					node.node.clearComparisons();
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
		context.drawTextWithShadow(EmiPort.literal("RMB node: menu  |  LMB drag: move node  |  Ctrl+LMB drag: move branch  |  MMB drag background: pan", Formatting.DARK_GRAY),
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

	private static class LayoutBlock {
		public final MaterialNode node;
		public final long amount;
		public final ChanceState chance;
		public final String path;
		public final int outlineColor;
		public final int selfWidth;
		public final int subtreeWidth;
		public final List<LayoutBlock> children = Lists.newArrayList();
		public MaterialNode resolution = null;
		public boolean comparisonHead = false;
		public boolean comparisonCandidate = false;
		public boolean comparisonSelected = false;
		public long comparisonCost = 0;
		public MaterialNode compareOwner = null;
		public int childSpacing = NODE_HORIZONTAL_SPACING;
		public int childLaneWidth = 0;
		public boolean equalChildLanes = false;

		public LayoutBlock(MaterialNode node, long amount, ChanceState chance, String path, int outlineColor, int selfWidth, int subtreeWidth) {
			this.node = node;
			this.amount = amount;
			this.chance = chance;
			this.path = path;
			this.outlineColor = outlineColor;
			this.selfWidth = selfWidth;
			this.subtreeWidth = subtreeWidth;
		}
	}

	private enum ContextAction {
		COMPARE,
		AUTO,
		CLEAR,
		RECIPES,
		FOLD,
		UNFOLD
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
