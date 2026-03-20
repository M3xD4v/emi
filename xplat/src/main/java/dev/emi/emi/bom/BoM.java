package dev.emi.emi.bom;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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
	private static final long PURE_REF_AUTOSAVE_DELAY_MS = 500L;
	private static RecipeDefaults defaults = new RecipeDefaults();
	private static final ScheduledExecutorService PURE_REF_AUTOSAVE_EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread thread = new Thread(r, "EMI Pure Ref Autosave");
		thread.setDaemon(true);
		return thread;
	});
	private static final Object PURE_REF_AUTOSAVE_LOCK = new Object();
	private static volatile PureRefSaveState pureRefSaveState = PureRefSaveState.SAVED;
	private static volatile long lastPureRefSaveMillis = 0L;
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
	private static volatile boolean pureRefAutosaveDirty = false;
	private static volatile ScheduledFuture<?> pureRefAutosaveFuture = null;

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

	public static void markPureRefDirty() {
		synchronized (PURE_REF_AUTOSAVE_LOCK) {
			pureRefAutosaveDirty = true;
			pureRefSaveState = PureRefSaveState.UNSAVED;
			if (pureRefAutosaveFuture != null) {
				pureRefAutosaveFuture.cancel(false);
			}
			pureRefAutosaveFuture = PURE_REF_AUTOSAVE_EXECUTOR.schedule(BoM::flushPureRefAutosave, PURE_REF_AUTOSAVE_DELAY_MS, TimeUnit.MILLISECONDS);
		}
	}

	public static void flushPureRefAutosave() {
		boolean shouldSave;
		synchronized (PURE_REF_AUTOSAVE_LOCK) {
			shouldSave = pureRefAutosaveDirty;
			pureRefAutosaveDirty = false;
			if (pureRefAutosaveFuture != null) {
				pureRefAutosaveFuture.cancel(false);
				pureRefAutosaveFuture = null;
			}
		}
		if (shouldSave) {
			pureRefSaveState = PureRefSaveState.SAVING;
			MinecraftClient client = MinecraftClient.getInstance();
			if (client != null) {
				client.execute(dev.emi.emi.runtime.EmiPersistentData::saveWorldProject);
			} else {
				dev.emi.emi.runtime.EmiPersistentData.saveWorldProject();
			}
		}
	}

	public static void markPureRefSaved() {
		pureRefSaveState = PureRefSaveState.SAVED;
		lastPureRefSaveMillis = System.currentTimeMillis();
	}

	public static void markPureRefSaveFailed() {
		pureRefSaveState = PureRefSaveState.ERROR;
	}

	public static PureRefSaveState getPureRefSaveState() {
		return pureRefSaveState;
	}

	public static String getPureRefSaveStatusText() {
		return switch (pureRefSaveState) {
			case UNSAVED -> "Unsaved changes";
			case SAVING -> "Saving...";
			case ERROR -> "Save failed";
			case SAVED -> "Saved";
		};
	}

	public static long getLastPureRefSaveMillis() {
		return lastPureRefSaveMillis;
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

	public static void setWorldProject(PureRefProject project) {
		if (project == null) {
			pureRefProject = PureRefProject.workingCopy();
			pureRefSaveState = PureRefSaveState.SAVED;
			return;
		}
		PureRefProject copy = project.copy();
		PureRefProject stored = new PureRefProject(-1, copy.name, copy.offX, copy.offY, copy.zoom);
		stored.objects.addAll(copy.objects);
		if (stored.name == null || stored.name.isBlank() || "Untitled Project".equals(stored.name)) {
			stored.name = "World Project";
		}
		pureRefProject = stored;
		pureRefSaveState = PureRefSaveState.SAVED;
	}

	public static PureRefProject snapshotWorldProject() {
		PureRefProject copy = pureRefProject.copy();
		PureRefProject stored = new PureRefProject(-1, copy.name, copy.offX, copy.offY, copy.zoom);
		stored.objects.addAll(copy.objects);
		if (stored.name == null || stored.name.isBlank() || "Untitled Project".equals(stored.name)) {
			stored.name = "World Project";
		}
		return stored;
	}

	public static String getWorldProjectKey() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) {
			return "global";
		}
		try {
			String serverAddress = getCurrentServerAddress(client);
			if (serverAddress != null && !serverAddress.isBlank()) {
				return sanitizeWorldKey("server-" + serverAddress);
			}
			if (Boolean.TRUE.equals(invokeBoolean(client, "isInSingleplayer"))) {
				String saveName = getSingleplayerSaveName(client);
				if (saveName != null && !saveName.isBlank()) {
					return sanitizeWorldKey("singleplayer-" + saveName);
				}
			}
			if (client.world != null && client.world.getRegistryKey() != null) {
				return sanitizeWorldKey("dimension-" + client.world.getRegistryKey().getValue());
			}
		} catch (Throwable t) {
			// Fall through to the generic key below.
		}
		return "global";
	}

	public static File getWorldProjectFile() {
		return new File(new File(EmiPersistentData.FILE.getParentFile() == null ? new File(".") : EmiPersistentData.FILE.getParentFile(), "emi_world_projects"),
			getWorldProjectKey() + ".json");
	}

	private static String getCurrentServerAddress(MinecraftClient client) {
		Object entry = invoke(client, "getCurrentServerEntry");
		if (entry == null) {
			return null;
		}
		String address = readString(entry, "address");
		if (address == null) {
			address = invokeString(entry, "getAddress");
		}
		if (address == null) {
			address = entry.toString();
		}
		return address;
	}

	private static String getSingleplayerSaveName(MinecraftClient client) {
		Object server = invoke(client, "getServer");
		if (server == null) {
			return null;
		}
		Object saveProperties = invoke(server, "getSaveProperties");
		if (saveProperties != null) {
			String levelName = invokeString(saveProperties, "getLevelName");
			if (levelName == null) {
				levelName = readString(saveProperties, "levelName");
			}
			if (levelName != null) {
				return levelName;
			}
		}
		return readString(server, "saveName");
	}

	private static Object invoke(Object target, String methodName) {
		if (target == null) {
			return null;
		}
		try {
			Method method = target.getClass().getMethod(methodName);
			method.setAccessible(true);
			return method.invoke(target);
		} catch (Throwable t) {
			return null;
		}
	}

	private static String invokeString(Object target, String methodName) {
		Object result = invoke(target, methodName);
		return result instanceof String string ? string : result == null ? null : result.toString();
	}

	private static Boolean invokeBoolean(Object target, String methodName) {
		Object result = invoke(target, methodName);
		return result instanceof Boolean bool ? bool : null;
	}

	private static String readString(Object target, String fieldName) {
		if (target == null) {
			return null;
		}
		try {
			java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
			field.setAccessible(true);
			Object value = field.get(target);
			return value == null ? null : value.toString();
		} catch (Throwable t) {
			return null;
		}
	}

	private static String sanitizeWorldKey(String key) {
		StringBuilder sanitized = new StringBuilder();
		for (int i = 0; i < key.length(); i++) {
			char c = key.charAt(i);
			if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.') {
				sanitized.append(c);
			} else {
				sanitized.append('_');
			}
		}
		return sanitized.toString();
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

	public static enum PureRefSaveState {
		SAVED,
		SAVING,
		UNSAVED,
		ERROR
	}
}
