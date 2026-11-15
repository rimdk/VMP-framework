package org.framework.reconfigurationAlgorithm.nsga2;

import org.framework.Utils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure NSGA-II operators: constraint-domination, fast non-dominated sorting, crowding distance,
 * crowded-comparison tournament, elitist environmental selection and PM overload violation.
 * <p>
 * Dominance is Pareto dominance on the objective vector (no weighted sum). Constraints are handled
 * with the constrained-domination rule of Deb et al. (2002): a feasible member dominates an infeasible
 * one, and between infeasible members the smaller violation dominates.
 */
public final class Nsga2Sorting {

    private Nsga2Sorting() {
        // Utility class
    }

    /**
     * Constrained domination.
     *
     * @return true if {@code a} dominates {@code b}
     */
    public static boolean dominates(Nsga2Member a, Nsga2Member b) {
        if (a.isFeasible() && !b.isFeasible()) {
            return true;
        }
        if (!a.isFeasible() && b.isFeasible()) {
            return false;
        }
        if (!a.isFeasible()) {
            return a.getViolation() < b.getViolation();
        }
        boolean strictlyBetter = false;
        for (int i = 0; i < a.getObjectives().length; i++) {
            float x = a.getObjectives()[i];
            float y = b.getObjectives()[i];
            if (x > y) {
                return false;
            }
            if (x < y) {
                strictlyBetter = true;
            }
        }
        return strictlyBetter;
    }

    /**
     * Fast non-dominated sort (Deb et al. 2002). Sets {@link Nsga2Member#setRank(int)} on every member.
     *
     * @param population Members to sort (not modified)
     * @return Fronts, front 0 first
     */
    public static List<List<Nsga2Member>> fastNonDominatedSort(List<Nsga2Member> population) {
        int size = population.size();
        List<List<Integer>> dominatedBy = new ArrayList<>(size);
        int[] dominationCount = new int[size];
        for (int p = 0; p < size; p++) {
            dominatedBy.add(new ArrayList<>());
        }
        for (int p = 0; p < size; p++) {
            for (int q = p + 1; q < size; q++) {
                if (dominates(population.get(p), population.get(q))) {
                    dominatedBy.get(p).add(q);
                    dominationCount[q]++;
                } else if (dominates(population.get(q), population.get(p))) {
                    dominatedBy.get(q).add(p);
                    dominationCount[p]++;
                }
            }
        }

        List<List<Nsga2Member>> fronts = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        for (int p = 0; p < size; p++) {
            if (dominationCount[p] == 0) {
                current.add(p);
            }
        }
        int rank = 0;
        while (!current.isEmpty()) {
            List<Nsga2Member> front = new ArrayList<>();
            List<Integer> next = new ArrayList<>();
            for (int p : current) {
                population.get(p).setRank(rank);
                front.add(population.get(p));
                for (int q : dominatedBy.get(p)) {
                    dominationCount[q]--;
                    if (dominationCount[q] == 0) {
                        next.add(q);
                    }
                }
            }
            fronts.add(front);
            current = next;
            rank++;
        }
        return fronts;
    }

    /**
     * Crowding distance of every member of one front (Deb et al. 2002). Boundary members get
     * infinity; interior members get the normalised side length of the cuboid around them.
     * Sets {@link Nsga2Member#setCrowding(double)}.
     *
     * @param front One non-dominated front
     */
    public static void assignCrowding(List<Nsga2Member> front) {
        int size = front.size();
        for (Nsga2Member member : front) {
            member.setCrowding(0D);
        }
        if (size == 0) {
            return;
        }
        if (size <= 2) {
            for (Nsga2Member member : front) {
                member.setCrowding(Double.POSITIVE_INFINITY);
            }
            return;
        }
        int numberOfObjectives = front.get(0).getObjectives().length;
        for (int m = 0; m < numberOfObjectives; m++) {
            final int objective = m;
            List<Nsga2Member> sorted = new ArrayList<>(front);
            sorted.sort(Comparator.comparingDouble(member -> member.getObjectives()[objective]));
            double min = sorted.get(0).getObjectives()[m];
            double max = sorted.get(size - 1).getObjectives()[m];
            double range = max - min;
            if (range <= 0D) {
                // Objective is constant on this front: it contributes nothing (Deb et al. 2002).
                continue;
            }
            sorted.get(0).setCrowding(Double.POSITIVE_INFINITY);
            sorted.get(size - 1).setCrowding(Double.POSITIVE_INFINITY);
            for (int k = 1; k < size - 1; k++) {
                double distance = (sorted.get(k + 1).getObjectives()[m] - sorted.get(k - 1).getObjectives()[m]) / range;
                sorted.get(k).setCrowding(sorted.get(k).getCrowding() + distance);
            }
        }
    }

    /**
     * Crowded-comparison operator: lower rank wins; on equal rank the larger crowding distance wins.
     *
     * @return negative if {@code a} is preferred, positive if {@code b} is preferred, 0 if tied
     */
    public static int crowdedComparison(Nsga2Member a, Nsga2Member b) {
        if (a.getRank() != b.getRank()) {
            return Integer.compare(a.getRank(), b.getRank());
        }
        return Double.compare(b.getCrowding(), a.getCrowding());
    }

    /**
     * Binary tournament selection using the crowded-comparison operator.
     *
     * @param population Population with rank and crowding already assigned
     * @return Selected member
     */
    public static Nsga2Member binaryTournament(List<Nsga2Member> population) {
        Nsga2Member winner = population.get(Utils.getRandomtInt(population.size()));
        Nsga2Member candidate = population.get(Utils.getRandomtInt(population.size()));
        return crowdedComparison(candidate, winner) < 0 ? candidate : winner;
    }

    /**
     * Elitist environmental selection (NSGA-II (mu + lambda)): fronts are taken whole while they fit;
     * the last front is truncated by decreasing crowding distance. Ranks and crowding distances of the
     * survivors are set as a side effect.
     *
     * @param combined Parents and offspring
     * @param size     Size of the next population
     * @return Next population (survivors)
     */
    public static List<Nsga2Member> environmentalSelection(List<Nsga2Member> combined, int size) {
        List<List<Nsga2Member>> fronts = fastNonDominatedSort(combined);
        List<Nsga2Member> survivors = new ArrayList<>(size);
        for (List<Nsga2Member> front : fronts) {
            if (survivors.size() == size) {
                break;
            }
            assignCrowding(front);
            if (survivors.size() + front.size() <= size) {
                survivors.addAll(front);
            } else {
                List<Nsga2Member> sorted = new ArrayList<>(front);
                // Stable sort: ties keep the original order, which keeps the selection deterministic.
                sorted.sort((a, b) -> Double.compare(b.getCrowding(), a.getCrowding()));
                survivors.addAll(sorted.subList(0, size - survivors.size()));
            }
        }
        return survivors;
    }

    /**
     * Total capacity excess of all PMs and resources, normalised by PM capacity. Mirrors the overload test of
     * {@code Constraints.checkPMOverloaded}: a PM/resource is violated when its load is strictly greater than
     * its capacity (float arithmetic, as in the framework).
     *
     * @param loads      loads[pm][resource], accumulated as in {@code Constraints.checkPMOverloaded}
     * @param capacities capacities[pm][resource] (must be positive)
     * @return 0 if feasible, otherwise the sum of normalised excesses
     */
    public static double overloadViolation(float[][] loads, float[][] capacities) {
        double violation = 0D;
        for (int pm = 0; pm < loads.length; pm++) {
            for (int resource = 0; resource < loads[pm].length; resource++) {
                float excess = loads[pm][resource] - capacities[pm][resource];
                if (excess > 0F) {
                    violation += (double) excess / capacities[pm][resource];
                }
            }
        }
        return violation;
    }
}
