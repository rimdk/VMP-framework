package org.framework.reconfigurationAlgorithm.nsga2;

import org.framework.reconfigurationAlgorithm.memeticAlgorithm.Individual;

/**
 * One NSGA-II population member: the VM-to-PM chromosome plus its evaluated state.
 * <p>
 * Objectives are the four minimised objectives of the framework, in the same order as
 * {@code EvaluationByScalarizationMethod.loadObjectiveFunctions}:
 * [0] power consumption, [1] economical revenue (lost revenue), [2] wasted resources,
 * [3] migrated memory.
 * <p>
 * {@code violation} is 0 for a feasible member; otherwise it is the total PM capacity excess
 * (normalised by PM capacity), see {@link Nsga2Sorting#overloadViolation(double[][], double[][])}.
 */
public class Nsga2Member {

    private final Individual individual;
    private final Float[] objectives;
    private final double violation;
    private int rank;
    private double crowding;

    public Nsga2Member(Individual individual, Float[] objectives, double violation) {
        this.individual = individual;
        this.objectives = objectives;
        this.violation = violation;
    }

    public Individual getIndividual() {
        return individual;
    }

    public Float[] getObjectives() {
        return objectives;
    }

    public double getViolation() {
        return violation;
    }

    public boolean isFeasible() {
        return violation <= 0D;
    }

    public int getRank() {
        return rank;
    }

    public void setRank(int rank) {
        this.rank = rank;
    }

    public double getCrowding() {
        return crowding;
    }

    public void setCrowding(double crowding) {
        this.crowding = crowding;
    }
}
