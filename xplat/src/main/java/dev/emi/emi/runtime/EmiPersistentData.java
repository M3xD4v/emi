package dev.emi.emi.runtime;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import dev.emi.emi.bom.BoM;
import dev.emi.emi.bom.PureRefProject;
import net.minecraft.util.JsonHelper;

public class EmiPersistentData {
	public static final File FILE = new File("emi.json");
	public static final Gson GSON = new Gson().newBuilder().setPrettyPrinting().create();
	
	public static void save() {
		try {
			JsonObject json = new JsonObject();
			json.add("favorites", EmiFavorites.save());
			EmiSidebars.save(json);
			json.add("recipe_defaults", BoM.saveAdded());
			json.add("recipe_trees", BoM.saveTrees());
			json.add("pure_ref_projects", BoM.savePureRefProjects());
			json.add("hidden_stacks", EmiHidden.save());
			try (FileWriter writer = new FileWriter(FILE)) {
				GSON.toJson(json, writer);
			}
		} catch (Exception e) {
			EmiLog.error("Failed to write persistent data", e);
		}
		saveWorldProject();
	}

	public static void load() {
		try {
			if (FILE.exists()) {
				JsonObject json = GSON.fromJson(new FileReader(FILE), JsonObject.class);
				if (json != null && JsonHelper.hasArray(json, "favorites")) {
					EmiFavorites.load(JsonHelper.getArray(json, "favorites"));
				}
				if (json != null) {
					EmiSidebars.load(json);
					if (JsonHelper.hasJsonObject(json, "recipe_defaults")) {
						BoM.loadAdded(JsonHelper.getObject(json, "recipe_defaults"));
					}
					if (JsonHelper.hasArray(json, "recipe_trees")) {
						BoM.loadTrees(JsonHelper.getArray(json, "recipe_trees"));
					}
					if (JsonHelper.hasArray(json, "pure_ref_projects")) {
						BoM.loadPureRefProjects(JsonHelper.getArray(json, "pure_ref_projects"));
					}
					if (JsonHelper.hasArray(json, "hidden_stacks")) {
						EmiHidden.load(JsonHelper.getArray(json, "hidden_stacks"));
					}
				}
			}
		} catch (Exception e) {
			EmiLog.error("Failed to parse persistent data", e);
		}
		loadWorldProject();
	}

	public static void saveWorldProject() {
		try {
			File file = BoM.getWorldProjectFile();
			File parent = file.getParentFile();
			if (parent != null && !parent.exists()) {
				parent.mkdirs();
			}
			JsonObject json = BoM.snapshotWorldProject().save();
			try (FileWriter writer = new FileWriter(file)) {
				GSON.toJson(json, writer);
			}
		} catch (Exception e) {
			EmiLog.error("Failed to write world board project", e);
		}
	}

	public static void loadWorldProject() {
		try {
			File file = BoM.getWorldProjectFile();
			if (!file.exists()) {
				BoM.setWorldProject(PureRefProject.workingCopy());
				return;
			}
			try (FileReader reader = new FileReader(file)) {
				JsonObject json = GSON.fromJson(reader, JsonObject.class);
				if (json == null) {
					BoM.setWorldProject(PureRefProject.workingCopy());
					return;
				}
				BoM.setWorldProject(PureRefProject.load(json));
			}
		} catch (Exception e) {
			EmiLog.error("Failed to parse world board project", e);
			BoM.setWorldProject(PureRefProject.workingCopy());
		}
	}
}
