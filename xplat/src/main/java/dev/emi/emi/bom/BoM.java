package dev.emi.emi.bom;

import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
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
import dev.emi.emi.api.stack.TagEmiIngredient;
import dev.emi.emi.api.stack.serializer.EmiIngredientSerializer;
import dev.emi.emi.data.RecipeDefaults;
import dev.emi.emi.runtime.EmiPersistentData;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import net.minecraft.util.JsonHelper;

public class BoM {
	public static final int TREE_SLOT_COUNT = 50;
	public static final int PURE_REF_SLOT_COUNT = 50;
	private static RecipeDefaults defaults = new RecipeDefaults();
	public static MaterialTree tree;
	public static Map<EmiIngredient, EmiRecipe> defaultRecipes = Maps.newHashMap();
	public static Map<EmiIngredient, EmiRecipe> addedRecipes = Maps.newHashMap();
	public static Set<EmiRecipe> disabledRecipes = Sets.newHashSet();
	public static boolean craftingMode = false;
	public static java.util.List<SavedRecipeTree> savedTrees = IntStream.range(0, TREE_SLOT_COUNT)
		.mapToObj(SavedRecipeTree::empty).collect(java.util.stream.Collectors.toList());
	public static java.util.List<PureRefProject> savedPureRefProjects = IntStream.range(0, PURE_REF_SLOT_COUNT)
		.mapToObj(PureRefProject::empty).collect(java.util.stream.Collectors.toList());
	public static PureRefProject pureRefProject = PureRefProject.workingCopy();

	public static void setDefaults(RecipeDefaults defaults) {
		BoM.defaults = defaults;
		MinecraftClient.getInstance().execute(() -> reload());
	}

	public static JsonObject saveAdded() {
		JsonArray added = new JsonArray();
		JsonObject addedTags = new JsonObject();
		JsonObject resolutions = new JsonObject();
		Set<Identifier> placed = Sets.newHashSet();
		for (Map.Entry<EmiIngredient, EmiRecipe> entry : addedRecipes.entrySet()) {
			EmiRecipe recipe = entry.getValue();
			if (recipe instanceof EmiResolutionRecipe err) {
				if (err.ingredient instanceof TagEmiIngredient tei) {
					JsonElement el = EmiIngredientSerializer.getSerialized(tei.copy().setAmount(1).setChance(1));
					JsonElement val = EmiIngredientSerializer.getSerialized(err.stack);
					if (el != null && JsonHelper.isString(el) && val != null) {
						addedTags.add(el.getAsString(), val);
					}
				}
			} else if (recipe != null && recipe.getId() != null && !placed.contains(recipe.getId())) {
				DefaultStatus status = getRecipeStatus(recipe);
				placed.add(recipe.getId());
				if (status == DefaultStatus.FULL) {
					added.add(recipe.getId().toString());
				} else if (status == DefaultStatus.PARTIAL) {
					JsonArray arr = new JsonArray();
					for (EmiStack stack : recipe.getOutputs()) {
						if (getRecipe(stack) == recipe) {
							JsonElement el = EmiIngredientSerializer.getSerialized(stack);
							if (el != null) {
								arr.add(el);
							}
						}
					}
					if (!arr.isEmpty()) {
						resolutions.add(recipe.getId().toString(), arr);
					}
				}
			}
		}
		JsonArray disabled = new JsonArray();
		for (EmiRecipe recipe : disabledRecipes) {
			if (recipe != null && recipe.getId() != null) {
				disabled.add(recipe.getId().toString());
			}
		}
		JsonObject obj = new JsonObject();
		obj.add("added", added);
		obj.add("tags", addedTags);
		obj.add("resolutions", resolutions);
		obj.add("disabled", disabled);
		return obj;
	}

	public static JsonArray saveTrees() {
		JsonArray arr = new JsonArray();
		for (SavedRecipeTree tree : savedTrees) {
			if (!tree.isEmpty()) {
				arr.add(tree.save());
			}
		}
		return arr;
	}

	public static JsonArray savePureRefProjects() {
		JsonArray arr = new JsonArray();
		for (PureRefProject project : savedPureRefProjects) {
			if (!project.isEmpty()) {
				arr.add(project.save());
			}
		}
		return arr;
	}

	public static void loadAdded(JsonObject object) {
		addedRecipes.clear();
		disabledRecipes.clear();
		JsonArray disabled = JsonHelper.getArray(object, "disabled", new JsonArray());
		for (JsonElement el : disabled) {
			Identifier id = EmiPort.id(el.getAsString());
			EmiRecipe recipe = EmiApi.getRecipeManager().getRecipe(id);
			disabledRecipes.add(recipe);
		}
		JsonArray added = JsonHelper.getArray(object, "added", new JsonArray());
		for (JsonElement el : added) {
			Identifier id = EmiPort.id(el.getAsString());
			EmiRecipe recipe = EmiApi.getRecipeManager().getRecipe(id);
			if (recipe != null && !disabledRecipes.contains(recipe)) {
				for (EmiStack output : recipe.getOutputs()) {
					addedRecipes.put(output, recipe);
				}
			}
		}
		JsonObject resolutions = JsonHelper.getObject(object, "resolutions", new JsonObject());
		for (String key : resolutions.keySet()) {
			Identifier id = EmiPort.id(key);
			EmiRecipe recipe = EmiApi.getRecipeManager().getRecipe(id);
			if (recipe != null && JsonHelper.hasArray(resolutions, key)) {
				JsonArray arr = JsonHelper.getArray(resolutions, key);
				for (JsonElement el : arr) {
					EmiIngredient stack = EmiIngredientSerializer.getDeserialized(el);
					if (!stack.isEmpty()) {
						addedRecipes.put(stack, recipe);
					}
				}
			}
		}
		JsonObject addedTags = JsonHelper.getObject(object, "tags", new JsonObject());
		for (String key : addedTags.keySet()) {
			EmiIngredient tag = EmiIngredientSerializer.getDeserialized(new JsonPrimitive(key));
			EmiIngredient stack = EmiIngredientSerializer.getDeserialized(addedTags.get(key));
			if (!tag.isEmpty() && !stack.isEmpty() && stack.getEmiStacks().size() == 1 && tag.getEmiStacks().containsAll(stack.getEmiStacks())) {
				addedRecipes.put(tag, new EmiResolutionRecipe(tag, stack.getEmiStacks().get(0)));
			}
		}
	}

	public static void loadTrees(JsonArray array) {
		savedTrees = IntStream.range(0, TREE_SLOT_COUNT).mapToObj(SavedRecipeTree::empty).collect(java.util.stream.Collectors.toList());
		for (JsonElement el : array) {
			if (el.isJsonObject()) {
				SavedRecipeTree tree = SavedRecipeTree.load(el.getAsJsonObject());
				if (tree.slot >= 0 && tree.slot < savedTrees.size()) {
					savedTrees.set(tree.slot, tree);
				}
			}
		}
	}

	public static void loadPureRefProjects(JsonArray array) {
		savedPureRefProjects = IntStream.range(0, PURE_REF_SLOT_COUNT).mapToObj(PureRefProject::empty)
			.collect(java.util.stream.Collectors.toList());
		for (JsonElement el : array) {
			if (el.isJsonObject()) {
				PureRefProject project = PureRefProject.load(el.getAsJsonObject());
				if (project.slot >= 0 && project.slot < savedPureRefProjects.size()) {
					savedPureRefProjects.set(project.slot, project);
				}
			}
		}
	}

	public static void reload() {
		defaultRecipes = defaults.bake();
	}

	public static boolean isRecipeEnabled(EmiRecipe recipe) {
		return !disabledRecipes.contains(recipe) && (defaultRecipes.values().contains(recipe) || addedRecipes.values().contains(recipe));
	}

	public static DefaultStatus getRecipeStatus(EmiRecipe recipe) {
		int found = 0;
		for (EmiStack stack : recipe.getOutputs()) {
			if (recipe.equals(getRecipe(stack))) {
				found++;
			}
		}
		if (found == 0) {
			return DefaultStatus.EMPTY;
		} else if (found >= recipe.getOutputs().size()) {
			return DefaultStatus.FULL;
		} else {
			return DefaultStatus.PARTIAL;
		}
	}

	public static EmiRecipe getRecipe(EmiIngredient stack) {
		EmiRecipe recipe = addedRecipes.get(stack);
		if (recipe == null) {
			recipe = defaultRecipes.get(stack);
			if (recipe != null && disabledRecipes.contains(recipe)) {
				return null;
			}
		}
		return recipe;
	}

	public static void setGoal(EmiRecipe recipe) {
		tree = new MaterialTree(recipe);
		craftingMode = false;
	}

	public static SavedRecipeTree getSavedTree(int slot) {
		if (slot < 0 || slot >= savedTrees.size()) {
			return SavedRecipeTree.empty(slot);
		}
		return savedTrees.get(slot);
	}

	public static void saveTree(int slot, SavedRecipeTree tree) {
		if (slot < 0 || slot >= savedTrees.size()) {
			return;
		}
		savedTrees.set(slot, tree);
		EmiPersistentData.save();
	}

	public static void renameSavedTree(int slot, String name) {
		if (slot < 0 || slot >= savedTrees.size()) {
			return;
		}
		SavedRecipeTree tree = savedTrees.get(slot);
		if (!tree.isEmpty()) {
			tree.name = name;
			EmiPersistentData.save();
		}
	}

	public static void deleteSavedTree(int slot) {
		if (slot < 0 || slot >= savedTrees.size()) {
			return;
		}
		savedTrees.set(slot, SavedRecipeTree.empty(slot));
		EmiPersistentData.save();
	}

	public static SavedRecipeTree.LoadResult loadSavedTree(int slot) {
		if (slot < 0 || slot >= savedTrees.size()) {
			return new SavedRecipeTree.LoadResult(false, false);
		}
		SavedRecipeTree tree = savedTrees.get(slot);
		if (tree.snapshot == null) {
			return new SavedRecipeTree.LoadResult(false, false);
		}
		return tree.snapshot.loadIntoBoM();
	}

	public static PureRefProject getSavedPureRefProject(int slot) {
		if (slot < 0 || slot >= savedPureRefProjects.size()) {
			return PureRefProject.empty(slot);
		}
		return savedPureRefProjects.get(slot);
	}

	public static void savePureRefProject(int slot, PureRefProject project) {
		if (slot < 0 || slot >= savedPureRefProjects.size()) {
			return;
		}
		PureRefProject copy = project.copy();
		PureRefProject stored = new PureRefProject(slot, copy.name, copy.offX, copy.offY, copy.zoom);
		stored.objects.addAll(copy.objects);
		savedPureRefProjects.set(slot, stored);
		EmiPersistentData.save();
	}

	public static void renameSavedPureRefProject(int slot, String name) {
		if (slot < 0 || slot >= savedPureRefProjects.size()) {
			return;
		}
		PureRefProject project = savedPureRefProjects.get(slot);
		project.name = name;
		EmiPersistentData.save();
	}

	public static void deleteSavedPureRefProject(int slot) {
		if (slot < 0 || slot >= savedPureRefProjects.size()) {
			return;
		}
		savedPureRefProjects.set(slot, PureRefProject.empty(slot));
		EmiPersistentData.save();
	}

	public static boolean loadSavedPureRefProject(int slot) {
		if (slot < 0 || slot >= savedPureRefProjects.size()) {
			return false;
		}
		PureRefProject project = savedPureRefProjects.get(slot);
		pureRefProject = project.copy();
		return true;
	}

	public static void addResolution(EmiIngredient ingredient, EmiRecipe recipe) {
		tree.addResolution(ingredient, recipe);
	}

	public static boolean isDefaultRecipe(EmiIngredient stack, EmiRecipe recipe) {
		if (recipe instanceof EmiResolutionRecipe err) {
			if (getRecipe(err.ingredient) instanceof EmiResolutionRecipe res) {
				return stack.equals(res.stack);
			}
		}
		return getRecipe(stack) == recipe;
	}

	public static void addRecipe(EmiRecipe recipe) {
		disabledRecipes.remove(recipe);
		for (EmiStack stack : recipe.getOutputs()) {
			addedRecipes.put(stack, recipe);
		}
		EmiPersistentData.save();
		recalculate();
	}

	public static void addRecipe(EmiIngredient stack, EmiRecipe recipe) {
		if (recipe instanceof EmiResolutionRecipe err) {
			stack = err.ingredient;
		}
		addedRecipes.put(stack, recipe);
		EmiPersistentData.save();
		recalculate();
	}

	public static void removeRecipe(EmiRecipe recipe) {
		for (EmiStack stack : recipe.getOutputs()) {
			addedRecipes.remove(stack, recipe);
		}
		if (getRecipeStatus(recipe) != DefaultStatus.EMPTY) {
			disabledRecipes.add(recipe);
		}
		EmiPersistentData.save();
		recalculate();
	}

	public static void removeRecipe(EmiIngredient stack, EmiRecipe recipe) {
		if (recipe instanceof EmiResolutionRecipe err) {
			if (addedRecipes.get(err.ingredient) instanceof EmiResolutionRecipe res && stack.equals(res.stack)) {
				addedRecipes.remove(err.ingredient);
			}
		} else {
			addedRecipes.remove(stack, recipe);
		}
		if (getRecipeStatus(recipe) != DefaultStatus.EMPTY) {
			disabledRecipes.add(recipe);
		}
		EmiPersistentData.save();
		recalculate();
	}

	private static void recalculate() {
		if (tree != null) {
			tree.recalculate();
		}
	}

	public static enum DefaultStatus {
		EMPTY,
		PARTIAL,
		FULL
	}
}
