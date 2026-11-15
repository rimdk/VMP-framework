package org.framework.experiment;

import org.domain.APrioriValue;
import org.domain.Placement;
import org.domain.VirtualMachine;
import org.framework.common.Parameter;
import org.framework.reconfigurationAlgorithm.memeticAlgorithm.MASettings;
import org.framework.reconfigurationAlgorithm.nsga2.Nsga2;

import java.util.List;
import java.util.function.Supplier;

/**
 * Runs one memetic reconfiguration with the optimizer selected by {@code VMPr_OPTIMIZER} and records it.
 * <ul>
 *   <li>{@code NSGA2}: {@link Nsga2}.</li>
 *   <li>otherwise (default, {@code MOMAVMP}): the original MoMaVMP call, supplied by the caller, unchanged.</li>
 * </ul>
 * Both paths return a placement with the same contract, so the managers are unaffected.
 */
public final class OptimizerSelector {

    private OptimizerSelector() {
        // Utility class
    }

    /**
     * @param actualPlacement Placement at the reconfiguration time (not modified)
     * @param aPrioriValues   Apriori values for the placement score
     * @param settings        MA settings (population size, generations, crossover probability, ...)
     * @param event           Record of this reconfiguration event (see {@link ExperimentResults#newEvent()})
     * @param momavmpCall     The original MoMaVMP call of the caller, used when NSGA2 is not selected
     * @return Placement to apply
     */
    public static Placement run(Placement actualPlacement, List<APrioriValue> aPrioriValues, MASettings settings,
                                ExperimentResults.Event event, Supplier<Placement> momavmpCall) {
        List<VirtualMachine> previousVMs = VirtualMachine.cloneVMsList(actualPlacement.getVirtualMachineList());
        try {
            long start = System.nanoTime();
            if ("NSGA2".equals(Parameter.VMPR_OPTIMIZER)) {
                Nsga2.Result result = new Nsga2().reconfiguration(actualPlacement, aPrioriValues, settings);
                event.recordNsga2(result, previousVMs, System.nanoTime() - start);
                return result.getPlacement();
            }
            Placement placement = momavmpCall.get();
            event.recordMomavmp(placement, previousVMs, settings, System.nanoTime() - start);
            return placement;
        } finally {
            // Always release the scenario's wait, even if the optimizer failed (the event is then not exported).
            event.fail();
        }
    }
}
