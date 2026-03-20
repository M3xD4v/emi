package dev.emi.emi.bom;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.google.common.collect.Lists;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;

public class MaterialNode {
	public final EmiIngredient ingredient;
	public final EmiStack remainder;
	public @Nullable EmiRecipe recipe;
	public @Nullable List<MaterialNode> children;
	public float consumeChance = 1, produceChance = 1;
	public long amount = 1;
	public long divisor = 1;
	public long remainderAmount = 0;
	public boolean catalyst = false;
	public boolean missing = false;
	public @Nullable List<Comparison> comparisons = null;
	// Should these be decoupled from material nodes?
	public FoldState state = FoldState.EXPANDED;
	public ProgressState progress = ProgressState.UNSTARTED;
	public long neededBatches = 0, totalNeeded = 0;

	public MaterialNode(EmiIngredient ingredient) {
		this.amount = ingredient.getAmount();
		this.ingredient = ingredient.copy().setAmount(1).setChance(1);
		if (this.ingredient.getEmiStacks().size() == 1) {
			this.remainder = this.ingredient.getEmiStacks().get(0).getRemainder();
			this.remainderAmount = remainder.getAmount();
			this.remainder.setAmount(1);
		} else {
			this.remainder = EmiStack.EMPTY;
		}
		out: {
			for (EmiStack stack : ingredient.getEmiStacks()) {
				if (!stack.getRemainder().equals(stack)) {
					break out;
				}
			}
			catalyst = true;
		}
	}

	public MaterialNode(MaterialNode node) {
		this.ingredient = node.ingredient;
		this.remainder = node.remainder;
		this.recipe = node.recipe;
		this.amount = node.amount;
		this.divisor = node.divisor;
		this.remainderAmount = node.remainderAmount;
	}

	public boolean hasComparisons() {
		return comparisons != null && !comparisons.isEmpty();
	}

	public void clearComparisons() {
		comparisons = null;
	}

	public void recalculate(MaterialTree tree) {
		recalculate(tree, Lists.newArrayList());
	}

	private void recalculate(MaterialTree tree, List<EmiRecipe> used) {
		EmiRecipe recipe = this.recipe;
		if (!used.isEmpty()) {
			recipe = tree.getRecipe(ingredient);
		}
		if (recipe != null) {
			if (used.contains(recipe)) {
				return;
			}
			used.add(recipe);
			defineRecipe(recipe);
			for (MaterialNode node : children) {
				node.recalculate(tree, used);
			}
			used.remove(used.size() - 1);
		}
	}

	public void defineRecipe(EmiRecipe recipe) {
		produceChance = 1;
		missing = false;
		if (recipe == null) {
			this.recipe = null;
			this.children = Lists.newArrayList();
			this.divisor = 1;
			return;
		}
		this.recipe = recipe;
		divisor = 0;
		for (EmiStack stack : recipe.getOutputs()) {
			if (stack.equals(ingredient)) {
				if (divisor > 0) {
					if (produceChance != 1 || stack.getChance() != 1) {
						produceChance = (stack.getAmount() * stack.getChance() + divisor * produceChance) / (divisor + stack.getAmount());
					}
					divisor += stack.getAmount();
				} else {
					divisor = stack.getAmount();
					produceChance = stack.getChance();
				}
			}
		}
		if (divisor <= 0) {
			divisor = 1;
		}
		this.children = Lists.newArrayList();
		outer:
		for (EmiIngredient i : recipe.getInputs()) {
			EmiStack remainder = EmiStack.EMPTY;
			if (i.getEmiStacks().size() == 1) {
				remainder = i.getEmiStacks().get(0).getRemainder();
			}
			for (MaterialNode node : children) {
				if (EmiIngredient.areEqual(i, node.ingredient) && EmiIngredient.areEqual(remainder, node.remainder)) {
					node.amount += i.getAmount();
					node.remainderAmount += remainder.getAmount();
					continue outer;
				}
			}
			if (!i.isEmpty()) {
				MaterialNode node = new MaterialNode(i);
				node.consumeChance = i.getChance();
				children.add(node);
			}
		}
	}

	public static class Comparison {
		public final EmiRecipe recipe;
		public final MaterialNode node;
		public final long estimatedCost;
		public final int estimatedSteps;
		public final int missingInputs;
		public final int estimatedInputTypes;
		public boolean selected;
		public boolean cheapest;
		public boolean fastest;
		public boolean preferredForMode;

		public Comparison(EmiRecipe recipe, MaterialNode node, long estimatedCost, int estimatedSteps, int missingInputs, int estimatedInputTypes, boolean selected) {
			this.recipe = recipe;
			this.node = node;
			this.estimatedCost = estimatedCost;
			this.estimatedSteps = estimatedSteps;
			this.missingInputs = missingInputs;
			this.estimatedInputTypes = estimatedInputTypes;
			this.selected = selected;
		}
	}
}
