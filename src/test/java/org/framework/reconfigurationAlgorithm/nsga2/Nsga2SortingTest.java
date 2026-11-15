package org.framework.reconfigurationAlgorithm.nsga2;

import org.domain.APrioriValue;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class Nsga2SortingTest {

    /** Feasible member with objectives (a, b, 0, 0). */
    private static Nsga2Member point(float a, float b) {
        return new Nsga2Member(null, new Float[]{a, b, 0F, 0F}, 0D);
    }

    private static Nsga2Member infeasible(float a, float b, double violation) {
        return new Nsga2Member(null, new Float[]{a, b, 0F, 0F}, violation);
    }

    @Test
    public void dominanceIsParetoOnObjectivesForFeasibleMembers() {
        assertTrue(Nsga2Sorting.dominates(point(1, 1), point(2, 2)));
        assertTrue(Nsga2Sorting.dominates(point(1, 2), point(1, 3)));
        assertFalse(Nsga2Sorting.dominates(point(1, 4), point(4, 1)));   // incomparable
        assertFalse(Nsga2Sorting.dominates(point(2, 2), point(2, 2)));   // equal vectors do not dominate
    }

    @Test
    public void constrainedDominanceFeasibleBeatsInfeasibleAndSmallerViolationWins() {
        assertTrue(Nsga2Sorting.dominates(point(9, 9), infeasible(0, 0, 0.1)));
        assertFalse(Nsga2Sorting.dominates(infeasible(0, 0, 0.1), point(9, 9)));
        assertTrue(Nsga2Sorting.dominates(infeasible(9, 9, 0.1), infeasible(0, 0, 0.5)));
        assertFalse(Nsga2Sorting.dominates(infeasible(0, 0, 0.5), infeasible(9, 9, 0.1)));
    }

    @Test
    public void fastNonDominatedSortAssignsFrontsAndRanks() {
        Nsga2Member a = point(1, 4);
        Nsga2Member b = point(2, 2);
        Nsga2Member c = point(4, 1);
        Nsga2Member d = point(3, 3);   // dominated by b
        Nsga2Member e = point(4, 4);   // dominated by d
        List<Nsga2Member> population = new ArrayList<>(Arrays.asList(e, d, c, b, a));

        List<List<Nsga2Member>> fronts = Nsga2Sorting.fastNonDominatedSort(population);

        assertEquals(3, fronts.size());
        assertEquals(3, fronts.get(0).size());
        assertTrue(fronts.get(0).containsAll(Arrays.asList(a, b, c)));
        assertEquals(Arrays.asList(d), fronts.get(1));
        assertEquals(Arrays.asList(e), fronts.get(2));
        assertEquals(0, a.getRank());
        assertEquals(0, c.getRank());
        assertEquals(1, d.getRank());
        assertEquals(2, e.getRank());
    }

    @Test
    public void crowdingDistanceMarksBoundariesInfiniteAndRewardsSpread() {
        Nsga2Member p1 = point(1, 4);
        Nsga2Member p2 = point(2, 3);
        Nsga2Member p3 = point(3, 2);
        Nsga2Member p4 = point(4, 1);
        List<Nsga2Member> front = Arrays.asList(p1, p2, p3, p4);

        Nsga2Sorting.assignCrowding(front);

        assertEquals(Double.POSITIVE_INFINITY, p1.getCrowding(), 0D);
        assertEquals(Double.POSITIVE_INFINITY, p4.getCrowding(), 0D);
        // Interior: (3-1)/3 + (4-2)/3 = 4/3 for both interior points on this linear front.
        assertEquals(4D / 3D, p2.getCrowding(), 1e-9);
        assertEquals(4D / 3D, p3.getCrowding(), 1e-9);
    }

    @Test
    public void elitistSelectionKeepsWholeBetterFrontsFirst() {
        Nsga2Member a = point(1, 4);
        Nsga2Member b = point(2, 2);
        Nsga2Member c = point(4, 1);
        Nsga2Member d = point(3, 3);
        Nsga2Member e = point(4, 4);
        List<Nsga2Member> combined = new ArrayList<>(Arrays.asList(e, d, c, b, a));

        List<Nsga2Member> survivors = Nsga2Sorting.environmentalSelection(combined, 3);

        assertEquals(3, survivors.size());
        assertTrue(survivors.containsAll(Arrays.asList(a, b, c)));
        assertFalse(survivors.contains(d));
        assertFalse(survivors.contains(e));
    }

    @Test
    public void elitistTruncationOfLastFrontUsesCrowdingDistance() {
        // Front 0 has four points; keeping two must keep the two boundary points (infinite crowding).
        Nsga2Member p1 = point(1, 4);
        Nsga2Member p2 = point(2, 3);
        Nsga2Member p3 = point(3, 2);
        Nsga2Member p4 = point(4, 1);
        List<Nsga2Member> combined = new ArrayList<>(Arrays.asList(p2, p3, p1, p4));

        List<Nsga2Member> survivors = Nsga2Sorting.environmentalSelection(combined, 2);

        assertEquals(2, survivors.size());
        assertTrue(survivors.containsAll(Arrays.asList(p1, p4)));
    }

    @Test
    public void feasibleSurvivorsAreNotLostToInfeasibleOnes() {
        Nsga2Member feasible = point(5, 5);
        Nsga2Member infeasibleBetter = infeasible(0, 0, 0.2);
        List<Nsga2Member> combined = new ArrayList<>(Arrays.asList(infeasibleBetter, feasible));

        List<Nsga2Member> survivors = Nsga2Sorting.environmentalSelection(combined, 1);

        assertEquals(1, survivors.size());
        assertSame(feasible, survivors.get(0));
    }

    @Test
    public void crowdedComparisonPrefersLowerRankThenLargerCrowding() {
        Nsga2Member low = point(0, 0);
        Nsga2Member high = point(0, 0);
        low.setRank(0);
        low.setCrowding(0.1);
        high.setRank(1);
        high.setCrowding(9.0);
        assertTrue(Nsga2Sorting.crowdedComparison(low, high) < 0);

        Nsga2Member sparse = point(0, 0);
        Nsga2Member dense = point(0, 0);
        sparse.setRank(0);
        sparse.setCrowding(2.0);
        dense.setRank(0);
        dense.setCrowding(0.5);
        assertTrue(Nsga2Sorting.crowdedComparison(sparse, dense) < 0);
    }

    @Test
    public void overloadViolationIsZeroOnlyWhenNoPmExceedsCapacity() {
        float[][] capacities = {{100F, 100F, 100F}, {100F, 100F, 100F}};

        assertEquals(0D, Nsga2Sorting.overloadViolation(new float[][]{{50F, 100F, 0F}, {0F, 0F, 99F}}, capacities), 0D);
        assertEquals(0.2D, Nsga2Sorting.overloadViolation(new float[][]{{120F, 0F, 0F}, {0F, 0F, 0F}}, capacities), 1e-6);
    }

    @Test
    public void selectFinalIsDeterministicLowestScoreThenFirst() {
        List<APrioriValue> aPriori = Arrays.asList(
                new APrioriValue(0F, 10F), new APrioriValue(0F, 10F),
                new APrioriValue(0F, 1F), new APrioriValue(0F, 10F));
        Nsga2Member worse = new Nsga2Member(null, new Float[]{8F, 8F, 0.8F, 0F}, 0D);
        Nsga2Member better = new Nsga2Member(null, new Float[]{2F, 2F, 0.2F, 0F}, 0D);
        Nsga2Member betterDuplicate = new Nsga2Member(null, new Float[]{2F, 2F, 0.2F, 0F}, 0D);

        assertSame(better, Nsga2.selectFinal(Arrays.asList(worse, better), aPriori));
        assertSame(better, Nsga2.selectFinal(Arrays.asList(better, betterDuplicate), aPriori));
        assertNull(Nsga2.selectFinal(new ArrayList<Nsga2Member>(), aPriori));
    }
}
