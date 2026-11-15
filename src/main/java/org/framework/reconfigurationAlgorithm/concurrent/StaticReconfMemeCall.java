package org.framework.reconfigurationAlgorithm.concurrent;

import org.domain.APrioriValue;
import org.domain.Placement;
import org.framework.experiment.ExperimentResults;
import org.framework.experiment.OptimizerSelector;
import org.framework.reconfigurationAlgorithm.memeticAlgorithm.MASettings;
import org.framework.reconfigurationAlgorithm.memeticAlgorithm.MoMaVMP;

import java.util.List;
import java.util.concurrent.Callable;

/**
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
        // VMPr_OPTIMIZER=NSGA2 selects NSGA-II; otherwise the original MoMaVMP call below runs.
        return OptimizerSelector.run(actualPlacement, aPrioriValuesList, settings, event, () -> {
            MoMaVMP memetic = new MoMaVMP();
            return memetic.reconfiguration(actualPlacement, aPrioriValuesList, settings);
        });
    }
}
