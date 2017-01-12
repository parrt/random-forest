/*
 * Copyright (c) 2017 Terence Parr. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE file in the project root.
 */

package us.parr.animl.classifiers;

import us.parr.animl.AniStats;
import us.parr.animl.data.DataTable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static us.parr.animl.AniStats.majorityVote;

/** A Random Forest classifier operating on categorical and numerical
 *  values. Predicts integer categories only. -1 is an invalid predicted
 *  category value.
 */
public class RandomForest implements Classifier {
	/** How many trees to create in the forest */
	protected int numEstimators;

	/** The forest of trees */
	protected List<DecisionTree> trees;

	/** Which observations (indexes) were out-of-bag for each tree trained on data? */
	protected List<Set<Integer>> treeOutOfBagSampleIndexes;

	public RandomForest(int numEstimators) {
		this.numEstimators = numEstimators;
		this.trees = new ArrayList<>(numEstimators);
		this.treeOutOfBagSampleIndexes = new ArrayList<>(numEstimators);
	}

	/** Train numEstimators trees using 2D matrix data as training. */
	public static RandomForest train(DataTable data, int numEstimators, int minLeafSize) {
		if ( data==null || data.size()==0 || numEstimators==0 ) return null;
		RandomForest forest = new RandomForest(numEstimators);
		int M = data.getNumberOfPredictorVar();
		// Number of variables to select at random at each decision node to find best split
		int m = (int)Math.round(Math.sqrt(M));
		for (int i = 1; i<=numEstimators; i++) {
			if ( DecisionTree.debug ) System.out.println("Estimator "+i+" ------------------");
			Set<Integer> outOfBagSamples = new HashSet<>(); // gets filled in
			List<int[]> bootstrap = AniStats.bootstrapWithRepl(data.getRows(), outOfBagSamples);
			DataTable table = new DataTable(data, bootstrap);
			DecisionTree tree = DecisionTree.build(table, m, minLeafSize);
			forest.trees.add(tree);
			forest.treeOutOfBagSampleIndexes.add(outOfBagSamples);
		}
		return forest;
	}

	public int classify(int[] unknown) {
		return classify(trees, unknown);
	}

	/*
	TODO: from http://scikit-learn.org/stable/modules/ensemble.html#forest
	"In contrast to the original publication [B2001], the scikit-learn
	 implementation combines classifiers by averaging their probabilistic
	  prediction, instead of letting each classifier vote for a single class."
	 */
	public static int classify(Collection<DecisionTree> trees, int[] unknown) {
		if ( unknown==null ) {
			return DecisionTree.INVALID_CATEGORY;
		}
		List<Integer> predictions = new ArrayList<>();
		for (DecisionTree tree : trees) {
			predictions.add( tree.classify(unknown) );
		}
		return majorityVote(predictions);
	}

	/** Return the out-of-bag error estimate */
	public double getErrorEstimate(DataTable data) {
		int mismatches = 0;
		int n = 0; // how many rows had oob estimators?
		Set<DecisionTree>[] outOfBagEstimators = getOutOfBagEstimatorSets(data);
		List<Integer> misses = new ArrayList<>();
		for (int i = 0; i<data.size(); i++) {
			if ( outOfBagEstimators[i]==null ) {
				continue; // for small number of trees, some data rows might not appear in oob set
			}
			n++;
			int[] row = data.getRow(i);
			int oobPrediction = classify(outOfBagEstimators[i], row);
			int actualCategory = row[data.getPredictedCol()];
			if ( oobPrediction!=actualCategory ) {
				mismatches++;
			}
		}
		return ((float)mismatches) / n;
	}

	/** For each observation in data, (X_i,y_i), compute set of trees that were not
	 *  trained on (X_i,y_i).  Each bootstrap leaves out about 1/3 of data rows
	 *  and is called the out-of-bag sample for that tree.  What we need to
	 *  estimate OOB error is a classifier for (X_i,y_i) that combines all
	 *  trees that were NOT trained on (X_i,y_i).
	 *
	 *  The ith element of the result array is the set of trees not trained on ith data row.
	 *
	 *  We rely on the default System identity hash for DecisionTree here
	 *  for the set so all trees are different and can coexist in set.
	 */
	public Set<DecisionTree>[] getOutOfBagEstimatorSets(DataTable data) {
		Set<DecisionTree>[] outOfBagEstimators = new HashSet[data.size()];
		int numEstimators = treeOutOfBagSampleIndexes.size();
		for (int k = 0; k<numEstimators; k++) { //
			Set<Integer> oobIndexes = treeOutOfBagSampleIndexes.get(k);
			for (Integer i : oobIndexes) { // for each observation not used to build tree k
				if ( outOfBagEstimators[i]==null ) {
					outOfBagEstimators[i] = new HashSet<>();
				}
				// add kth tree to oob estimator set for data row i
				outOfBagEstimators[i].add(trees.get(k));
			}
		}
		return outOfBagEstimators;
	}

	public DecisionTree getTree(int i) {
		return trees.get(i);
	}
}
