package dev.emi.emi.bom;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.google.common.collect.Lists;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import dev.emi.emi.EmiPort;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiResolutionRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.serializer.EmiIngredientSerializer;
import net.minecraft.util.Identifier;
import net.minecraft.util.JsonHelper;

public class SavedRecipeTree {
	public final int slot;
	public String name;
	public EmiIngredient thumbnail;
	public @Nullable RecipeTreeSnapshot snapshot;

	public SavedRecipeTree(int slot, String name, EmiIngredient thumbnail, @Nullable RecipeTreeSnapshot snapshot) {
		this.slot = slot;
		this.name = name;
		this.thumbnail = thumbnail;
		this.snapshot = snapshot;
	}

	public static SavedRecipeTree empty(int slot) {
		return new SavedRecipeTree(slot, "", EmiStack.EMPTY, null);
	}

	public boolean isEmpty() {
		return snapshot == null;
	}

	public boolean hasMissingData() {
		return snapshot != null && snapshot.hasMissingData();
	}

	public JsonObject save() {
		JsonObject json = new JsonObject();
		json.addProperty("slot", slot);
		json.addProperty("name", name);
		JsonElement thumb = EmiIngredientSerializer.getSerialized(thumbnail);
		if (thumb != null) {
			json.add("thumbnail", thumb);
		}
		if (snapshot != null) {
			json.add("snapshot", snapshot.save());
		}
		return json;
	}

	public static SavedRecipeTree load(JsonObject json) {
		int slot = JsonHelper.getInt(json, "slot", 0);
		String name = JsonHelper.getString(json, "name", "");
		EmiIngredient thumbnail = EmiStack.EMPTY;
		if (JsonHelper.hasElement(json, "thumbnail")) {
			thumbnail = EmiIngredientSerializer.getDeserialized(json.get("thumbnail"));
		}
		RecipeTreeSnapshot snapshot = null;
		if (JsonHelper.hasJsonObject(json, "snapshot")) {
			snapshot = RecipeTreeSnapshot.load(JsonHelper.getObject(json, "snapshot"));
		}
		return new SavedRecipeTree(slot, name, thumbnail, snapshot);
	}

	public static class LoadResult {
		public final boolean loaded;
		public final boolean missingData;

		public LoadResult(boolean loaded, boolean missingData) {
			this.loaded = loaded;
			this.missingData = missingData;
		}
	}

	public static class RecipeTreeSnapshot {
		public final Identifier rootRecipeId;
		public final long batches;
		public final boolean craftingMode;
		public final double offX;
		public final double offY;
		public final int zoom;
		public final NodeState root;

		public RecipeTreeSnapshot(Identifier rootRecipeId, long batches, boolean craftingMode, double offX, double offY, int zoom, NodeState root) {
			this.rootRecipeId = rootRecipeId;
			this.batches = batches;
			this.craftingMode = craftingMode;
			this.offX = offX;
			this.offY = offY;
			this.zoom = zoom;
			this.root = root;
		}

		public static @Nullable RecipeTreeSnapshot capture(MaterialTree tree, double offX, double offY, int zoom) {
			if (tree == null || tree.goal == null || tree.goal.recipe == null || tree.goal.recipe.getId() == null) {
				return null;
			}
			return new RecipeTreeSnapshot(tree.goal.recipe.getId(), tree.batches, BoM.craftingMode, offX, offY, zoom,
				NodeState.capture(tree.goal, tree, "0"));
		}

		public JsonObject save() {
			JsonObject json = new JsonObject();
			json.addProperty("root_recipe", rootRecipeId.toString());
			json.addProperty("batches", batches);
			json.addProperty("crafting_mode", craftingMode);
			json.addProperty("off_x", offX);
			json.addProperty("off_y", offY);
			json.addProperty("zoom", zoom);
			json.add("root", root.save());
			return json;
		}

		public static RecipeTreeSnapshot load(JsonObject json) {
			Identifier rootRecipeId = EmiPort.id(JsonHelper.getString(json, "root_recipe"));
			long batches = JsonHelper.getLong(json, "batches", 1);
			boolean craftingMode = JsonHelper.getBoolean(json, "crafting_mode", false);
			double offX = JsonHelper.getDouble(json, "off_x", 0);
			double offY = JsonHelper.getDouble(json, "off_y", 0);
			int zoom = JsonHelper.getInt(json, "zoom", 0);
			NodeState root = NodeState.load(JsonHelper.getObject(json, "root"));
			return new RecipeTreeSnapshot(rootRecipeId, batches, craftingMode, offX, offY, zoom, root);
		}

		public LoadResult loadIntoBoM() {
			EmiRecipe rootRecipe = EmiApi.getRecipeManager().getRecipe(rootRecipeId);
			if (rootRecipe == null) {
				return new LoadResult(false, true);
			}
			MaterialTree tree = new MaterialTree(rootRecipe);
			ApplyResult result = root.apply(tree.goal, tree, "0");
			tree.batches = Math.max(1, batches);
			tree.snapshotOffX = offX;
			tree.snapshotOffY = offY;
			tree.snapshotZoom = zoom;
			BoM.tree = tree;
			BoM.craftingMode = craftingMode;
			return new LoadResult(true, result.missingData);
		}

		public boolean hasMissingData() {
			if (EmiApi.getRecipeManager().getRecipe(rootRecipeId) == null) {
				return true;
			}
			return root.hasMissingData();
		}
	}

	public static class ApplyResult {
		public boolean missingData = false;
	}

	public static class NodeState {
		public SelectionType selection = SelectionType.NONE;
		public @Nullable String recipeId;
		public @Nullable JsonElement resolutionStack;
		public boolean expanded = true;
		public int offsetX = 0;
		public int offsetY = 0;
		public final List<NodeState> children = Lists.newArrayList();

		public JsonObject save() {
			JsonObject json = new JsonObject();
			json.addProperty("selection", selection.name());
			if (recipeId != null) {
				json.addProperty("recipe", recipeId);
			}
			if (resolutionStack != null) {
				json.add("resolution_stack", resolutionStack);
			}
			json.addProperty("expanded", expanded);
			json.addProperty("offset_x", offsetX);
			json.addProperty("offset_y", offsetY);
			JsonArray arr = new JsonArray();
			for (NodeState child : children) {
				arr.add(child.save());
			}
			json.add("children", arr);
			return json;
		}

		public static NodeState load(JsonObject json) {
			NodeState state = new NodeState();
			if (JsonHelper.hasString(json, "selection")) {
				try {
					state.selection = SelectionType.valueOf(JsonHelper.getString(json, "selection"));
				} catch (Exception e) {
					state.selection = SelectionType.NONE;
				}
			}
			if (JsonHelper.hasString(json, "recipe")) {
				state.recipeId = JsonHelper.getString(json, "recipe");
			}
			if (JsonHelper.hasElement(json, "resolution_stack")) {
				state.resolutionStack = json.get("resolution_stack");
			}
			state.expanded = JsonHelper.getBoolean(json, "expanded", true);
			state.offsetX = JsonHelper.getInt(json, "offset_x", 0);
			state.offsetY = JsonHelper.getInt(json, "offset_y", 0);
			JsonArray children = JsonHelper.getArray(json, "children", new JsonArray());
			for (JsonElement child : children) {
				if (child.isJsonObject()) {
					state.children.add(load(child.getAsJsonObject()));
				}
			}
			return state;
		}

		public static NodeState capture(MaterialNode node, MaterialTree tree, String path) {
			NodeState state = new NodeState();
			state.expanded = node.state == FoldState.EXPANDED;
			MaterialTree.NodeOffset offset = tree.nodeOffsets.get(path);
			if (offset != null) {
				state.offsetX = offset.x();
				state.offsetY = offset.y();
			}
			if (node.recipe instanceof EmiResolutionRecipe resolution) {
				state.selection = SelectionType.RESOLUTION;
				state.resolutionStack = EmiIngredientSerializer.getSerialized(resolution.stack);
			} else if (node.recipe != null && node.recipe.getId() != null) {
				state.selection = SelectionType.RECIPE;
				state.recipeId = node.recipe.getId().toString();
			} else {
				state.selection = SelectionType.NONE;
			}
			if (node.children != null) {
				for (int i = 0; i < node.children.size(); i++) {
					state.children.add(capture(node.children.get(i), tree, path + "/" + i));
				}
			}
			return state;
		}

		public ApplyResult apply(MaterialNode node, MaterialTree tree, String path) {
			ApplyResult result = new ApplyResult();
			node.missing = false;
			switch (selection) {
				case NONE -> node.defineRecipe(null);
				case RECIPE -> {
					EmiRecipe recipe = recipeId == null ? null : EmiApi.getRecipeManager().getRecipe(EmiPort.id(recipeId));
					if (recipe != null) {
						node.defineRecipe(recipe);
					} else {
						node.defineRecipe(null);
						node.missing = true;
						result.missingData = true;
					}
				}
				case RESOLUTION -> {
					EmiIngredient ingredient = resolutionStack == null ? EmiStack.EMPTY : EmiIngredientSerializer.getDeserialized(resolutionStack);
					if (ingredient.getEmiStacks().size() == 1 && node.ingredient.getEmiStacks().containsAll(ingredient.getEmiStacks())) {
						node.defineRecipe(new EmiResolutionRecipe(node.ingredient, ingredient.getEmiStacks().get(0)));
					} else {
						node.defineRecipe(null);
						node.missing = true;
						result.missingData = true;
					}
				}
			}
			node.state = expanded ? FoldState.EXPANDED : FoldState.COLLAPSED;
			if (offsetX != 0 || offsetY != 0) {
				tree.nodeOffsets.put(path, new MaterialTree.NodeOffset(offsetX, offsetY));
			} else {
				tree.nodeOffsets.remove(path);
			}
			if (node.children != null) {
				for (int i = 0; i < node.children.size() && i < children.size(); i++) {
					ApplyResult childResult = children.get(i).apply(node.children.get(i), tree, path + "/" + i);
					if (childResult.missingData) {
						result.missingData = true;
					}
				}
			}
			return result;
		}

		public boolean hasMissingData() {
			if (selection == SelectionType.RECIPE && recipeId != null && EmiApi.getRecipeManager().getRecipe(EmiPort.id(recipeId)) == null) {
				return true;
			}
			if (selection == SelectionType.RESOLUTION) {
				EmiIngredient ingredient = resolutionStack == null ? EmiStack.EMPTY : EmiIngredientSerializer.getDeserialized(resolutionStack);
				if (ingredient.isEmpty()) {
					return true;
				}
			}
			for (NodeState child : children) {
				if (child.hasMissingData()) {
					return true;
				}
			}
			return false;
		}
	}

	public static enum SelectionType {
		NONE,
		RECIPE,
		RESOLUTION
	}
}
