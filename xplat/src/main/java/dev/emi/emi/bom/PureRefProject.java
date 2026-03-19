package dev.emi.emi.bom;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.google.common.collect.Lists;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.emi.emi.EmiPort;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.util.JsonHelper;

public class PureRefProject {
	public final int slot;
	public String name;
	public double offX;
	public double offY;
	public int zoom;
	public final List<Object> objects = Lists.newArrayList();

	public PureRefProject(int slot, String name, double offX, double offY, int zoom) {
		this.slot = slot;
		this.name = name;
		this.offX = offX;
		this.offY = offY;
		this.zoom = zoom;
	}

	public static PureRefProject empty(int slot) {
		return new PureRefProject(slot, "", 0, 0, 0);
	}

	public static PureRefProject workingCopy() {
		return new PureRefProject(-1, "Untitled Project", 0, 0, 0);
	}

	public boolean isEmpty() {
		return objects.isEmpty() && (name == null || name.isBlank());
	}

	public PureRefProject copy() {
		PureRefProject project = new PureRefProject(slot, name, offX, offY, zoom);
		for (Object object : objects) {
			project.objects.add(object.copy());
		}
		return project;
	}

	public JsonObject save() {
		JsonObject json = new JsonObject();
		json.addProperty("slot", slot);
		json.addProperty("name", name);
		json.addProperty("off_x", offX);
		json.addProperty("off_y", offY);
		json.addProperty("zoom", zoom);
		JsonArray arr = new JsonArray();
		for (Object object : objects) {
			arr.add(object.save());
		}
		json.add("objects", arr);
		return json;
	}

	public static PureRefProject load(JsonObject json) {
		int slot = JsonHelper.getInt(json, "slot", -1);
		String name = JsonHelper.getString(json, "name", "");
		double offX = JsonHelper.getDouble(json, "off_x", 0);
		double offY = JsonHelper.getDouble(json, "off_y", 0);
		int zoom = JsonHelper.getInt(json, "zoom", 0);
		PureRefProject project = new PureRefProject(slot, name, offX, offY, zoom);
		JsonArray objects = JsonHelper.getArray(json, "objects", new JsonArray());
		for (JsonElement el : objects) {
			if (el.isJsonObject()) {
				Object object = Object.load(el.getAsJsonObject());
				if (object != null) {
					project.objects.add(object);
				}
			}
		}
		return project;
	}

	public abstract static class Object {
		public final String id;
		public int x;
		public int y;

		public Object(String id, int x, int y) {
			this.id = id;
			this.x = x;
			this.y = y;
		}

		public abstract Type getType();

		public abstract Object copy();

		protected abstract void saveData(JsonObject json);

		public JsonObject save() {
			JsonObject json = new JsonObject();
			json.addProperty("type", getType().name());
			json.addProperty("id", id);
			json.addProperty("x", x);
			json.addProperty("y", y);
			saveData(json);
			return json;
		}

		public static @Nullable Object load(JsonObject json) {
			Type type;
			try {
				type = Type.valueOf(JsonHelper.getString(json, "type"));
			} catch (Exception e) {
				return null;
			}
			String id = JsonHelper.getString(json, "id", "obj");
			int x = JsonHelper.getInt(json, "x", 0);
			int y = JsonHelper.getInt(json, "y", 0);
			return switch (type) {
				case TREE -> TreeObject.load(id, x, y, json);
				case NOTE -> NoteObject.load(id, x, y, json);
				case SHAPE -> ShapeObject.load(id, x, y, json);
			};
		}
	}

	public static class TreeObject extends Object {
		public int width;
		public int height;
		public String title;
		public EmiIngredient thumbnail;
		public @Nullable SavedRecipeTree.RecipeTreeSnapshot snapshot;

		public TreeObject(String id, int x, int y, int width, int height, String title, EmiIngredient thumbnail,
				@Nullable SavedRecipeTree.RecipeTreeSnapshot snapshot) {
			super(id, x, y);
			this.width = width;
			this.height = height;
			this.title = title;
			this.thumbnail = thumbnail;
			this.snapshot = snapshot;
		}

		@Override
		public Type getType() {
			return Type.TREE;
		}

		@Override
		public Object copy() {
			return new TreeObject(id, x, y, width, height, title, thumbnail, snapshot);
		}

		public @Nullable SavedRecipeTree.TreeBuildResult buildTree() {
			if (snapshot == null) {
				return null;
			}
			return snapshot.buildTree();
		}

		@Override
		protected void saveData(JsonObject json) {
			json.addProperty("width", width);
			json.addProperty("height", height);
			json.addProperty("title", title);
			JsonElement thumb = dev.emi.emi.api.stack.serializer.EmiIngredientSerializer.getSerialized(thumbnail);
			if (thumb != null) {
				json.add("thumbnail", thumb);
			}
			if (snapshot != null) {
				json.add("snapshot", snapshot.save());
			}
		}

		public static TreeObject load(String id, int x, int y, JsonObject json) {
			int width = JsonHelper.getInt(json, "width", 220);
			int height = JsonHelper.getInt(json, "height", 92);
			String title = JsonHelper.getString(json, "title", "Tree");
			EmiIngredient thumbnail = EmiStack.EMPTY;
			if (JsonHelper.hasElement(json, "thumbnail")) {
				thumbnail = dev.emi.emi.api.stack.serializer.EmiIngredientSerializer.getDeserialized(json.get("thumbnail"));
			}
			SavedRecipeTree.RecipeTreeSnapshot snapshot = null;
			if (JsonHelper.hasJsonObject(json, "snapshot")) {
				snapshot = SavedRecipeTree.RecipeTreeSnapshot.load(JsonHelper.getObject(json, "snapshot"));
			}
			return new TreeObject(id, x, y, width, height, title, thumbnail, snapshot);
		}
	}

	public static class NoteObject extends Object {
		public int width;
		public int height;
		public String title;
		public String body;
		public int color;

		public NoteObject(String id, int x, int y, int width, int height, String title, String body, int color) {
			super(id, x, y);
			this.width = width;
			this.height = height;
			this.title = title;
			this.body = body;
			this.color = color;
		}

		@Override
		public Type getType() {
			return Type.NOTE;
		}

		@Override
		public Object copy() {
			return new NoteObject(id, x, y, width, height, title, body, color);
		}

		@Override
		protected void saveData(JsonObject json) {
			json.addProperty("width", width);
			json.addProperty("height", height);
			json.addProperty("title", title);
			json.addProperty("body", body);
			json.addProperty("color", color);
		}

		public static NoteObject load(String id, int x, int y, JsonObject json) {
			int width = JsonHelper.getInt(json, "width", 180);
			int height = JsonHelper.getInt(json, "height", 100);
			String title = JsonHelper.getString(json, "title", "Note");
			String body = JsonHelper.getString(json, "body", "");
			int color = JsonHelper.getInt(json, "color", 0xFFF0D992);
			return new NoteObject(id, x, y, width, height, title, body, color);
		}
	}

	public static class ShapeObject extends Object {
		public ShapeType shapeType;
		public int x2;
		public int y2;
		public int color;
		public int thickness;

		public ShapeObject(String id, int x, int y, int x2, int y2, ShapeType shapeType, int color, int thickness) {
			super(id, x, y);
			this.x2 = x2;
			this.y2 = y2;
			this.shapeType = shapeType;
			this.color = color;
			this.thickness = thickness;
		}

		@Override
		public Type getType() {
			return Type.SHAPE;
		}

		@Override
		public Object copy() {
			return new ShapeObject(id, x, y, x2, y2, shapeType, color, thickness);
		}

		@Override
		protected void saveData(JsonObject json) {
			json.addProperty("shape", shapeType.name());
			json.addProperty("x2", x2);
			json.addProperty("y2", y2);
			json.addProperty("color", color);
			json.addProperty("thickness", thickness);
		}

		public static ShapeObject load(String id, int x, int y, JsonObject json) {
			ShapeType shape = ShapeType.LINE;
			if (JsonHelper.hasString(json, "shape")) {
				try {
					shape = ShapeType.valueOf(JsonHelper.getString(json, "shape"));
				} catch (Exception e) {
					shape = ShapeType.LINE;
				}
			}
			int x2 = JsonHelper.getInt(json, "x2", x + 80);
			int y2 = JsonHelper.getInt(json, "y2", y + 40);
			int color = JsonHelper.getInt(json, "color", 0xFF89B8E8);
			int thickness = JsonHelper.getInt(json, "thickness", 2);
			return new ShapeObject(id, x, y, x2, y2, shape, color, thickness);
		}
	}

	public static enum Type {
		TREE,
		NOTE,
		SHAPE
	}

	public static enum ShapeType {
		LINE,
		ARROW,
		BOX
	}

	public static String nextObjectId() {
		return EmiPort.id("pure_ref", Long.toString(System.nanoTime())).toString();
	}
}
