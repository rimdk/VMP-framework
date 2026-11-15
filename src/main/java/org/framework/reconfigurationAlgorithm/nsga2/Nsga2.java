package org.framework.reconfigurationAlgorithm.nsga2;

import org.domain.APrioriValue;
import org.domain.PhysicalMachine;
import org.domain.Placement;
import org.domain.VirtualMachine;
import org.framework.ObjectivesFunctions;
import org.framework.Utils;
import org.framework.common.Parameter;
import org.framework.reconfigurationAlgorithm.memeticAlgorithm.EvaluationByScalarizationMethod;
import org.framework.reconfigurationAlgorithm.memeticAlgorithm.Individual;
import org.framework.reconfigurationAlgorithm.memeticAlgorithm.Initialization;
import org.framework.reconfigurationAlgorithm.memeticAlgorithm.MASettings;
import org.framework.reconfigurationAlgorithm.memeticAlgorithm.Mutation;
import org.framework.reconfigurationAlgorithm.memeticAlgorithm.OnePointCrossover;
import org.framework.reconfigurationAlgorithm.memeticAlgorithm.Population;
import org.framework.reconfigurationAlgorithm.memeticAlgorithm.Reparation;
import org.framework.reconfigurationAlgorithm.memeticAlgorithm.UniformMutation;

import java.util.ArrayList;
import java.util.List;

/**
 * NSGA-II for VM-to-PM placement reconfiguration (alternative to {@code MoMaVMP}, selected with
 * {@code VMPr_OPTIMIZER=NSGA2}).
 * <p>
 * Reuses the framework's chromosome and operators so that the search space, objectives and placement
 * constraints are identical to MoMaVMP:
 * <ul>
 *   <li>Chromosome: {@link Individual} (solution[i] = PM id of VM i).</li>
 *   <li>Initialization: {@link Initialization}; repair of PM overload: {@link Reparation}.</li>
 *   <li>Crossover: {@link OnePointCrossover} with the MA crossover probability; mutation: {@link UniformMutation}.</li>
 *   <li>Objectives: {@link EvaluationByScalarizationMethod#loadObjectiveFunctions} (4 minimised objectives).</li>
 *   <li>Constraint: no PM overload with the framework's protection factors (same test as {@code Constraints.checkPMOverloaded}).</li>
 * </ul>
 * NSGA-II specific parts (no scalarization for dominance): {@link Nsga2Sorting}.
 * <p>
 * <b>Final selection rule (deterministic):</b> take the rank-0 members of the final population that are feasible;
 * select the one with the smallest {@code Utils.calcPlacemenScore} (the framework's placement score);
 * ties are broken by the lexicographically smaller objective vector, then by the earlier position in the
 * population. If no feasible rank-0 member exists, the current placement is kept unchanged (fallback).
 */
public class Nsga2 {

    /**
     * Result of one NSGA-II reconfiguration.
     */
    public static final class Result {
        private final Placement placement;
        private final List<Nsga2Member> finalFront;
        private final Nsga2Member selected;
        private final int evaluations;

        Result(Placement placement, List<Nsga2Member> finalFront, Nsga2Member selected, int evaluations) {
            this.placement = placement;
            this.finalFront = finalFront;
            this.selected = selected;
            this.evaluations = evaluations;
        }

        /** Placement to apply (same contract as MoMaVMP.reconfiguration). */
        public Placement getPlacement() {
            return placement;
        }

        /** Rank-0 members of the final population (feasible and infeasible). */
        public List<Nsga2Member> getFinalFront() {
            return finalFront;
        }

        /** Selected feasible member, or null when the fallback (keep current placement) was used. */
        public Nsga2Member getSelected() {
            return selected;
        }

        /** Objective evaluations: initial population plus one population per generation. */
        public int getEvaluations() {
            return evaluations;
        }

        public boolean isFallback() {
            return selected == null;
        }
    }

    /**
     * @param actualPlacement  Current placement (not modified)
     * @param aPrioriValueList Apriori values used for the placement score
     * @param settings         MA settings (population size must be even)
     * @return Result with the selected placement
     */
    public Result reconfiguration(Placement actualPlacement, List<APrioriValue> aPrioriValueList, MASettings settings) {
        List<VirtualMachine> virtualMachines = actualPlacement.getVirtualMachineList();
        List<VirtualMachine> derivedVMs = actualPlacement.getDerivedVMs();
        List<PhysicalMachine> physicalMachines = actualPlacement.getPhysicalMachines();
        int numberOfResources = settings.getNumberOfResources();
        int populationSize = settings.getPopulationSize();
        if (populationSize < 2 || populationSize % 2 != 0) {
            throw new IllegalArgumentException("NSGA2 needs an even POPULATION_SIZE >= 2, was: " + populationSize);
        }

        OnePointCrossover crossover = new OnePointCrossover(settings.getCrossoverProb());
        Mutation mutation = new UniformMutation();
        EvaluationByScalarizationMethod evaluator = new EvaluationByScalarizationMethod();

        Population initial = new Initialization().initialize(virtualMachines.size(), physicalMachines.size(), settings);
        int evaluations = initial.size();
        List<Nsga2Member> population = Nsga2Sorting.environmentalSelection(
                evaluate(initial, virtualMachines, derivedVMs, physicalMachines, numberOfResources, evaluator),
                populationSize);

        for (int generation = 0; generation < settings.getNumberOfGenerations(); generation++) {
            List<Individual> parents = new ArrayList<>(populationSize);
            for (int i = 0; i < populationSize; i++) {
                parents.add(Nsga2Sorting.binaryTournament(population).getIndividual());
            }
            Population offspring = crossover.crossover(parents, populationSize);
            offspring = mutation.mutate(offspring);
            List<Nsga2Member> offspringMembers =
                    evaluate(offspring, virtualMachines, derivedVMs, physicalMachines, numberOfResources, evaluator);
            evaluations += offspringMembers.size();

            List<Nsga2Member> combined = new ArrayList<>(population);
            combined.addAll(offspringMembers);
            population = Nsga2Sorting.environmentalSelection(combined, populationSize);
        }

        List<Nsga2Member> finalFront = new ArrayList<>();
        List<Nsga2Member> feasibleFront = new ArrayList<>();
        for (Nsga2Member member : population) {
            if (member.getRank() == 0) {
                finalFront.add(member);
                if (member.isFeasible()) {
                    feasibleFront.add(member);
                }
            }
        }

        Nsga2Member selected = selectFinal(feasibleFront, aPrioriValueList);
        Placement placement;
        if (selected == null) {
            placement = keepCurrentPlacement(actualPlacement, aPrioriValueList);
        } else {
            placement = buildPlacement(actualPlacement, selected, aPrioriValueList, numberOfResources);
        }
        return new Result(placement, finalFront, selected, evaluations);
    }

    /**
     * Deterministic choice from the feasible front: lowest placement score, then lexicographically smallest
     * objective vector, then earliest position.
     *
     * @param feasibleFront Feasible rank-0 members, in population order
     * @param aPrioriValues Apriori values
     * @return Selected member, or null if the front is empty
     */
    public static Nsga2Member selectFinal(List<Nsga2Member> feasibleFront, List<APrioriValue> aPrioriValues) {
        Nsga2Member best = null;
        Float bestScore = null;
        for (Nsga2Member member : feasibleFront) {
            Float score = Utils.calcPlacemenScore(member.getObjectives(), aPrioriValues);
            if (best == null) {
                best = member;
                bestScore = score;
                continue;
            }
            int comparison = Float.compare(score, bestScore);
            if (comparison == 0) {
                comparison = compareObjectives(member.getObjectives(), best.getObjectives());
            }
            if (comparison < 0) {
                best = member;
                bestScore = score;
            }
        }
        return best;
    }

    private static int compareObjectives(Float[] a, Float[] b) {
        for (int i = 0; i < a.length; i++) {
            int comparison = Float.compare(a[i], b[i]);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    /**
     * Evaluates a population: utilisation, repair (as MoMaVMP), objectives and constraint violation.
     */
    private static List<Nsga2Member> evaluate(Population population, List<VirtualMachine> virtualMachines,
                                              List<VirtualMachine> derivedVMs, List<PhysicalMachine> physicalMachines,
                                              int numberOfResources, EvaluationByScalarizationMethod evaluator) {
        evaluator.loadUtilization(population, virtualMachines, numberOfResources);
        population = Reparation.repairPopulation(population, virtualMachines, physicalMachines, numberOfResources);

        List<VirtualMachine> individualVMs = VirtualMachine.cloneVMsList(virtualMachines);
        List<PhysicalMachine> individualPMs = PhysicalMachine.clonePMsList(physicalMachines);
        List<VirtualMachine> individualDerivedVMs = VirtualMachine.cloneVMsList(derivedVMs);
        float[][] capacities = capacities(physicalMachines, numberOfResources);

        List<Nsga2Member> members = new ArrayList<>(population.size());
        for (Individual individual : population.getIndividuals()) {
            individualVMs = individual.convertToVMList(individualVMs);
            individualPMs = individual.convertToPMList(individualPMs, numberOfResources);
            evaluator.loadObjectiveFunctions(individual, individualVMs, virtualMachines, individualDerivedVMs,
                    individualPMs, numberOfResources);
            float[][] loads = loads(individualVMs, physicalMachines.size(), numberOfResources);
            double violation = Nsga2Sorting.overloadViolation(loads, capacities);
            members.add(new Nsga2Member(individual, individual.getObjectiveFunctions().clone(), violation));
        }
        return members;
    }

    /**
     * Load of each PM and resource. Computed in float with the same expression, order of accumulation and strict
     * comparison as {@code Constraints.checkPMOverloaded}, so that the feasibility decided here is exactly the one
     * the framework applies to the final placement (double accumulation can flip PMs packed at capacity).
     */
    static float[][] loads(List<VirtualMachine> virtualMachines, int numberOfPMs, int numberOfResources) {
        float[][] loads = new float[numberOfPMs][numberOfResources];
        for (VirtualMachine vm : virtualMachines) {
            Integer pmId = vm.getPhysicalMachine();
            if (pmId == null || pmId < 1 || pmId > numberOfPMs) {
                continue;
            }
            for (int resource = 0; resource < numberOfResources; resource++) {
                Float resourceSize = vm.getResources().get(resource);
                Float utilization = vm.getUtilization().get(resource);
                Float protection = Parameter.PROTECTION_FACTOR.get(resource);
                loads[pmId - 1][resource] += (resourceSize * utilization / 100)
                        + (resourceSize * (1 - utilization / 100) * protection);
            }
        }
        return loads;
    }

    /**
     * Capacity of each PM and resource, indexed by PM id - 1 (the same indexing as {@link #loads}). PMs are not
     * necessarily stored in id order, so the list position must not be used.
     */
    private static float[][] capacities(List<PhysicalMachine> physicalMachines, int numberOfResources) {
        float[][] capacities = new float[physicalMachines.size()][numberOfResources];
        for (PhysicalMachine pm : physicalMachines) {
            for (int resource = 0; resource < numberOfResources; resource++) {
                capacities[pm.getId() - 1][resource] = pm.getResources().get(resource);
            }
        }
        return capacities;
    }

    /**
     * Builds the placement exactly as {@code MoMaVMP.reconfiguration} does for its selected individual.
     */
    private static Placement buildPlacement(Placement actualPlacement, Nsga2Member selected,
                                            List<APrioriValue> aPrioriValues, int numberOfResources) {
        List<VirtualMachine> newVirtualMachines = VirtualMachine.cloneVMsList(actualPlacement.getVirtualMachineList());
        newVirtualMachines = selected.getIndividual().convertToVMList(newVirtualMachines);
        List<VirtualMachine> newDerivedVMs = VirtualMachine.cloneVMsList(actualPlacement.getDerivedVMs());
        Utils.updateDerivedVMs(newVirtualMachines, newDerivedVMs);
        List<PhysicalMachine> newPhysicalMachines = PhysicalMachine.clonePMsList(actualPlacement.getPhysicalMachines());
        newPhysicalMachines = selected.getIndividual().convertToPMList(newPhysicalMachines, numberOfResources);
        Placement placement = new Placement(newPhysicalMachines, newVirtualMachines, newDerivedVMs);
        placement.setPlacementScore(Utils.calcPlacemenScore(selected.getObjectives(), aPrioriValues));
        return placement;
    }

    /**
     * Fallback when no feasible rank-0 member exists: the current placement is kept (copy).
     */
    private static Placement keepCurrentPlacement(Placement actualPlacement, List<APrioriValue> aPrioriValues) {
        List<VirtualMachine> vms = VirtualMachine.cloneVMsList(actualPlacement.getVirtualMachineList());
        List<VirtualMachine> derivedVMs = VirtualMachine.cloneVMsList(actualPlacement.getDerivedVMs());
        List<PhysicalMachine> pms = PhysicalMachine.clonePMsList(actualPlacement.getPhysicalMachines());
        Float score = Utils.calcPlacemenScore(ObjectivesFunctions.loadObjectiveFunctions(vms, derivedVMs, pms),
                aPrioriValues);
        return new Placement(pms, vms, derivedVMs, score);
    }
}
