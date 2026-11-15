package org.framework.experiment;

import org.domain.APrioriValue;
import org.domain.PhysicalMachine;
import org.domain.Placement;
import org.domain.Revenue;
import org.domain.VirtualMachine;
import org.framework.Utils;
import org.framework.common.Constraints;
import org.framework.common.Parameter;
import org.framework.reconfigurationAlgorithm.concurrent.StaticReconfMemeCall;
import org.framework.reconfigurationAlgorithm.memeticAlgorithm.MASettings;
import org.framework.reconfigurationAlgorithm.memeticAlgorithm.MoMaVMP;
import org.framework.reconfigurationAlgorithm.nsga2.Nsga2;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end checks on a small synthetic placement (no simulation, no workload files):
 * both optimizer selections, feasibility of the NSGA-II result, reproducibility with a seed, and the CSV export.
 */
public class ComparisonExperimentTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @BeforeClass
    public static void setUpFrameworkParameters() {
        Parameter.PROTECTION_FACTOR = Arrays.asList(0.75F, 0.75F, 0.75F);
        Parameter.SCALARIZATION_METHOD = "ED";
        Parameter.FAULT_TOLERANCE = false;
        Parameter.POPULATION_SIZE = 20;
        Parameter.NUMBER_GENERATIONS = 15;
    }

    @Before
    public void resetState() {
        ExperimentResults.reset();
        Parameter.VMPR_OPTIMIZER = "MOMAVMP";
    }

    @After
    public void restoreState() {
        ExperimentResults.reset();
        Parameter.VMPR_OPTIMIZER = "MOMAVMP";
    }

    private static List<Float> floats(float a, float b, float c) {
        return new ArrayList<>(Arrays.asList(a, b, c));
    }

    /** 4 PMs (100 per resource) and 10 VMs (30 per resource, 50% used), round-robin placed. */
    private static Placement syntheticPlacement() {
        List<PhysicalMachine> pms = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            pms.add(new PhysicalMachine(i, 250, floats(100F, 100F, 100F)));
        }
        List<VirtualMachine> vms = new ArrayList<>();
        for (int v = 0; v < 10; v++) {
            vms.add(new VirtualMachine(v + 1, floats(30F, 30F, 30F), new Revenue(1F, 1F, 1F), 0, 100,
                    floats(50F, 50F, 50F), 1, 1, v % 4 + 1));
        }
        return new Placement(pms, vms, new ArrayList<VirtualMachine>());
    }

    private static List<APrioriValue> aPriori() {
        return Arrays.asList(new APrioriValue(0F, 1000F), new APrioriValue(0F, 100F),
                new APrioriValue(0F, 1F), new APrioriValue(0F, 500F));
    }

    private static MASettings settings() {
        MASettings settings = new MASettings();
        settings.setPopulationSize(20);
        settings.setNumberOfGenerations(15);
        settings.setNumberOfResources(3);
        settings.setNumberOfObjFunctions(4);
        settings.setCrossoverProb(1.0);
        return settings;
    }

    private static List<Integer> pmIdsOf(Placement placement) {
        List<Integer> ids = new ArrayList<>();
        for (VirtualMachine vm : placement.getVirtualMachineList()) {
            ids.add(vm.getPhysicalMachine());
        }
        return ids;
    }

    @Test
    public void nsga2ReturnsFeasibleScoredPlacementAndCountsEvaluations() {
        Placement actual = syntheticPlacement();

        Nsga2.Result result = new Nsga2().reconfiguration(actual, aPriori(), settings());

        assertNotNull(result.getPlacement().getPlacementScore());
        assertEquals(20 * (15 + 1), result.getEvaluations());
        assertFalse(result.getFinalFront().isEmpty());
        for (PhysicalMachine pm : result.getPlacement().getPhysicalMachines()) {
            List<VirtualMachine> vmsInPm = Utils.filterVMsByPM(result.getPlacement().getVirtualMachineList(), pm.getId());
            assertFalse("PM " + pm.getId() + " overloaded",
                    Constraints.checkPMOverloaded(pm, vmsInPm, Parameter.PROTECTION_FACTOR));
        }
        if (!result.isFallback()) {
            assertTrue(result.getSelected().isFeasible());
        }
    }

    /** PMs are not always stored in id order (the framework addresses them by id): feasibility must still agree. */
    @Test
    public void nsga2FeasibilityAgreesWithFrameworkCheckWhenPmListIsNotInIdOrder() {
        Placement actual = syntheticPlacement();
        // Heterogeneous capacity on PM id 1 (so a misaligned capacity array would change the outcome), then reverse.
        actual.getPhysicalMachines().get(0).getResources().set(0, 40F);
        java.util.Collections.reverse(actual.getPhysicalMachines());

        Nsga2.Result result = new Nsga2().reconfiguration(actual, aPriori(), settings());

        if (!result.isFallback()) {
            assertTrue(result.getSelected().isFeasible());
            for (PhysicalMachine pm : result.getPlacement().getPhysicalMachines()) {
                List<VirtualMachine> vmsInPm = Utils.filterVMsByPM(result.getPlacement().getVirtualMachineList(), pm.getId());
                assertFalse("PM " + pm.getId() + " overloaded",
                        Constraints.checkPMOverloaded(pm, vmsInPm, Parameter.PROTECTION_FACTOR));
            }
        }
    }

    @Test
    public void nsga2IsReproducibleWithTheSameSeed() {
        Placement actual = syntheticPlacement();

        Utils.setRandomSeed(123L);
        Nsga2.Result first = new Nsga2().reconfiguration(actual, aPriori(), settings());
        Utils.setRandomSeed(123L);
        Nsga2.Result second = new Nsga2().reconfiguration(actual, aPriori(), settings());

        assertEquals(pmIdsOf(first.getPlacement()), pmIdsOf(second.getPlacement()));
        assertEquals(first.getPlacement().getPlacementScore(), second.getPlacement().getPlacementScore());
    }

    @Test
    public void originalMomavmpStillRunsAndReturnsScoredPlacement() {
        Placement result = new MoMaVMP().reconfiguration(syntheticPlacement(), aPriori(), settings());

        assertNotNull(result.getPlacementScore());
        assertEquals(10, result.getVirtualMachineList().size());
    }

    @Test
    public void staticCallDispatchesToSelectedOptimizerAndRecordsTheEvent() throws Exception {
        Parameter.VMPR_OPTIMIZER = "NSGA2";
        ExperimentResults.beginScenario("pppp-00.csv", 0);
        Placement nsga2 = new StaticReconfMemeCall(syntheticPlacement(), aPriori(), settings()).call();
        ExperimentResults.endScenario("FF", "PM_CONFIG_INS_LOAD", 0.5F, 0.1F, 0.2F, 1.0F);

        assertNotNull(nsga2.getPlacementScore());
        Path dir = folder.newFolder("comparison").toPath();
        List<Path> files = ExperimentResults.writeCsv(dir.toString());
        assertEquals(3, files.size());

        List<String> scenarios = Files.readAllLines(files.get(0), StandardCharsets.UTF_8);
        List<String> events = Files.readAllLines(files.get(1), StandardCharsets.UTF_8);
        List<String> pareto = Files.readAllLines(files.get(2), StandardCharsets.UTF_8);
        assertEquals(ExperimentResults.SCENARIOS_HEADER, scenarios.get(0));
        assertEquals(2, scenarios.size());
        assertTrue(scenarios.get(1).startsWith("NSGA2,pppp-00.csv,"));
        assertEquals(ExperimentResults.RECONFIGURATIONS_HEADER, events.get(0));
        assertEquals(2, events.size());
        assertEquals(ExperimentResults.PARETO_HEADER, pareto.get(0));
        assertTrue(pareto.size() > 1);
        assertColumnsMatchHeader(scenarios);
        assertColumnsMatchHeader(events);
        assertColumnsMatchHeader(pareto);
    }

    /** The VMPr copy is the one used by PeriodicMigration/StateOfArt/OpenStack/CleverReconfiguration. */
    @Test
    public void vmprStaticCallCopyAlsoDispatchesToNsga2() throws Exception {
        Parameter.VMPR_OPTIMIZER = "NSGA2";
        org.framework.VMPr.memeticAlgorithm.MASettings vmprSettings = new org.framework.VMPr.memeticAlgorithm.MASettings();
        vmprSettings.setPopulationSize(20);
        vmprSettings.setNumberOfGenerations(15);
        vmprSettings.setNumberOfResources(3);
        vmprSettings.setNumberOfObjFunctions(4);
        vmprSettings.setCrossoverProb(1.0);

        ExperimentResults.beginScenario("pppp-03.csv", 3);
        Placement result = new org.framework.VMPr.configuration.StaticReconfMemeCall(
                syntheticPlacement(), aPriori(), vmprSettings).call();
        ExperimentResults.endScenario("FF", "PM_CONFIG_INS_LOAD", 0.5F, 0.1F, 0.2F, 1.0F);

        assertNotNull(result.getPlacementScore());
        Path dir = folder.newFolder("vmpr").toPath();
        List<Path> files = ExperimentResults.writeCsv(dir.toString());
        List<String> events = Files.readAllLines(files.get(1), StandardCharsets.UTF_8);
        assertEquals(2, events.size());
        assertTrue(Files.readAllLines(files.get(0), StandardCharsets.UTF_8).get(1).startsWith("NSGA2,pppp-03.csv,"));
    }

    @Test
    public void momavmpSelectionRecordsEventWithoutParetoFile() throws Exception {
        Parameter.VMPR_OPTIMIZER = "MOMAVMP";
        ExperimentResults.beginScenario("pppp-01.csv", 1);
        new StaticReconfMemeCall(syntheticPlacement(), aPriori(), settings()).call();
        ExperimentResults.endScenario("FF", "PM_CONFIG_INS_LOAD", 0.5F, 0.1F, 0.2F, 1.0F);

        Path dir = folder.newFolder("momavmp").toPath();
        List<Path> files = ExperimentResults.writeCsv(dir.toString());
        assertEquals(2, files.size());
        List<String> scenarios = Files.readAllLines(files.get(0), StandardCharsets.UTF_8);
        assertTrue(scenarios.get(1).startsWith("MOMAVMP,pppp-01.csv,"));
        // MoMaVMP evaluations are derived: population x (generations + 1) = 20 x 16 = 320.
        assertTrue(scenarios.get(1).contains(",320,"));
        assertColumnsMatchHeader(scenarios);
    }

    @Test
    public void seedIsAppliedPerScenarioIndex() throws Exception {
        Parameter.EXPERIMENT_SEED = 1000L;
        Parameter.VMPR_OPTIMIZER = "NSGA2";
        try {
            ExperimentResults.beginScenario("a.csv", 2);
            Placement first = new StaticReconfMemeCall(syntheticPlacement(), aPriori(), settings()).call();
            ExperimentResults.endScenario("FF", "PM_CONFIG_INS_LOAD", 0F, 0F, 0F, 0F);

            // Same scenario index => same seed (1000 + 2), so the same result even though it is a new "run".
            ExperimentResults.beginScenario("b.csv", 2);
            Placement second = new StaticReconfMemeCall(syntheticPlacement(), aPriori(), settings()).call();
            ExperimentResults.endScenario("FF", "PM_CONFIG_INS_LOAD", 0F, 0F, 0F, 0F);

            assertEquals(pmIdsOf(first), pmIdsOf(second));
        } finally {
            Parameter.EXPERIMENT_SEED = null;
        }
    }

    private static void assertColumnsMatchHeader(List<String> lines) {
        int columns = lines.get(0).split(",", -1).length;
        for (String line : lines) {
            assertEquals(line, columns, line.split(",", -1).length);
        }
    }
}
