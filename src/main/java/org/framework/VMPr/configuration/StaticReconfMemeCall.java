package org.framework.VMPr.configuration;

import org.domain.APrioriValue;
import org.domain.Placement;
import org.framework.VMPr.memeticAlgorithm.MASettings;
import org.framework.VMPr.memeticAlgorithm.MoMaVMP;
import org.framework.experiment.ExperimentResults;
import org.framework.experiment.OptimizerSelector;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * Used by the managers CleverReconfiguration, OpenStack, PeriodicMigration and StateOfArt.
 * <p>
 * {@code VMPr_OPTIMIZER=NSGA2} selects NSGA-II; otherwise the original MoMaVMP of this package runs unchanged.
 *
 * @author Leonardo Benitez
 */
public class StaticReconfMemeCall implements Callable<Placement> {

    private List<APrioriValue> aPrioriValuesList;
    private Placement actualPlacement;
    private MASettings settings;
    private final ExperimentResults.Event event;

    public StaticReconfMemeCall(Placement actualPlacement, List<APrioriValue> aPrioriValuesList, MASettings settings) {
        this.actualPlacement = actualPlacement;
        this.aPrioriValuesList = aPrioriValuesList;
        this.settings = settings;
        // Created on the submitting thread so that event ids follow the simulation order.
        this.event = ExperimentResults.newEvent();
    }

    @Override
    public Placement call() throws Exception {
        return OptimizerSelector.run(actualPlacement, aPrioriValuesList, settings, event, () -> {
            MoMaVMP memetic = new MoMaVMP();
            return memetic.reconfiguration(actualPlacement, aPrioriValuesList, settings);
        });
    }
}
