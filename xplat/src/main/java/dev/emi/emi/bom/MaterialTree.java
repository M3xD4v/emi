package dev.emi.emi.bom;

import java.util.Set;
import java.util.Map;
import java.util.stream.Stream;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;

public class MaterialTree {
	public MaterialNode goal;
	public TreeCost cost = new TreeCost();
	public Map<EmiIngredient, EmiRecipe> resolutions = Maps.newHashMap();
	public Map<String, NodeOffset> nodeOffsets = Maps.newHashMap();
	public long batches = 1;
	public double snapshotOffX = Double.NaN;
	public double snapshotOffY = Double.NaN;
	public int snapshotZoom = Integer.MIN_VALUE;

	public MaterialTree(EmiRecipe recipe) {
		EmiStack output = recipe.getOutputs().get(0);
		goal = new MaterialNode(output);
		goal.defineRecipe(recipe);
		recalculate();
	}

	public MaterialTree(MaterialNode goal) {
		this.goal = goal;
	}

	public EmiRecipe getRecipe(EmiIngredient stack) {
		EmiRecipe recipe = resolutions.get(stack);
		if (recipe == null && !resolutions.containsKey(stack)) {
			recipe = BoM.getRecipe(stack);
		}
		return recipe;
	}

	public void addResolution(EmiIngredient ingredient, EmiRecipe recipe) {
		resolutions.put(ingredient, recipe);
		if (ingredient.equals(goal.ingredient)) {
			goal.defineRecipe(recipe);
			goal.amount = ingredient.getAmount();
		}
		recalculate();
	}

	public void recalculate() {
		goal.recalculate(this);
	}

	public void calculateProgress(EmiPlayerInventory inventory) {
		cost.calculateProgress(goal, batches, inventory);
	}

	public void calculateCost() {
		cost.calculate(goal, batches);
	}

	public MaterialNode createComparisonNode(MaterialNode source, EmiRecipe recipe) {
		MaterialNode goal = new MaterialNode(source.ingredient.copy().setAmount(source.amount));
		goal.state = FoldState.EXPANDED;
		goal.defineRecipe(recipe);
		MaterialTree branch = new MaterialTree(goal);
		branch.batches = batches;
		branch.resolutions.putAll(resolutions);
		branch.recalculate();
		return branch.goal;
	}

	public long estimateCost(MaterialNode node) {
		TreeCost cost = new TreeCost();
		cost.calculate(node, batches);
		return Stream.concat(cost.costs.values().stream(), cost.chanceCosts.values().stream())
			.mapToLong(FlatMaterialCost::getEffectiveAmount)
			.sum();
	}

	public int estimateSteps(MaterialNode node) {
		return estimateSteps(node, Sets.newIdentityHashSet());
	}

	public int countMissingNodes(MaterialNode node) {
		return countMissingNodes(node, Sets.newIdentityHashSet());
	}

	public int estimateInputTypes(MaterialNode node) {
		TreeCost cost = new TreeCost();
		cost.calculate(node, batches);
		return cost.costs.size() + cost.chanceCosts.size();
	}

	private int estimateSteps(MaterialNode node, Set<MaterialNode> visited) {
		if (node == null || !visited.add(node)) {
			return 0;
		}
		int steps = node.recipe != null ? 1 : 0;
		if (node.children == null || node.children.isEmpty() || node.state != FoldState.EXPANDED) {
			return steps;
		}
		int childMax = 0;
		for (MaterialNode child : node.children) {
			childMax = Math.max(childMax, estimateSteps(child, visited));
		}
		return steps + childMax;
	}

	private int countMissingNodes(MaterialNode node, Set<MaterialNode> visited) {
		if (node == null || !visited.add(node)) {
			return 0;
		}
		int total = node.missing ? 1 : 0;
		if (node.children == null || node.children.isEmpty()) {
			return total;
		}
		for (MaterialNode child : node.children) {
			total += countMissingNodes(child, visited);
		}
		return total;
	}

	public static record NodeOffset(int x, int y) {
	}
}
