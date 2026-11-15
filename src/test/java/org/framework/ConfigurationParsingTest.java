package org.framework;

import org.framework.common.Parameter;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Parses the real configuration files (inputs/ivmp.conf, vmpr.conf, scenarios.conf) with extra lines appended,
 * exactly as the command line does. No simulation is run.
 */
public class ConfigurationParsingTest {

    /** Scenario list without key=value lines, so each test controls the experiment keys it sets. */
    /**
     * Fixture built from the shared inputs, minus the experiment keys (VMPr_OPTIMIZER, experiment.*) and minus
     * key=value lines in scenarios.conf. Each test then sets exactly the keys it needs via extraLines, so the
     * result does not depend on what the experimenter has written in inputs/vmpr.conf or scenarios.conf.
     */
    private static List<String> scenariosAfterParsing(String... extraLines) throws Exception {
        List<String> ivmp = withoutExperimentKeys(Files.readAllLines(Paths.get("inputs/ivmp.conf")));
        List<String> vmpr = withoutExperimentKeys(Files.readAllLines(Paths.get("inputs/vmpr.conf")));
        List<String> scenarios = new ArrayList<>();
        for (String line : Files.readAllLines(Paths.get("inputs/scenarios.conf"))) {
            if (!line.contains("=")) {
                scenarios.add(line);
            }
        }
        Stream<String> all = Stream.of(ivmp, vmpr, scenarios, Arrays.asList(extraLines))
                .flatMap(List::stream);
        List<String> scenarioFiles = new ArrayList<>();
        Utils.loadParameter(scenarioFiles, all);
        return scenarioFiles;
    }

    private static List<String> withoutExperimentKeys(List<String> lines) {
        List<String> kept = new ArrayList<>();
        for (String line : lines) {
            if (!line.startsWith("VMPr_OPTIMIZER=") && !line.startsWith("experiment.")) {
                kept.add(line);
            }
        }
        return kept;
    }

    @Test
    public void absentOptimizerAndLimitKeepOriginalBehaviour() throws Exception {
        List<String> scenarios = scenariosAfterParsing();

        assertEquals("MOMAVMP", Parameter.VMPR_OPTIMIZER);
        assertNull(Parameter.EXPERIMENT_SEED);
        assertEquals(80, scenarios.size());
        assertEquals("pppp-00.csv", scenarios.get(0));
    }

    @Test
    public void nsga2WithMaxScenariosSelectsFirstThreeInFileOrder() throws Exception {
        List<String> scenarios = scenariosAfterParsing("VMPr_OPTIMIZER=NSGA2", "experiment.maxScenarios=3");

        assertEquals("NSGA2", Parameter.VMPR_OPTIMIZER);
        assertEquals(Arrays.asList("pppp-00.csv", "pppp-01.csv", "pppp-02.csv"), scenarios);
    }

    @Test
    public void explicitMomavmpAndAllScenarios() throws Exception {
        List<String> scenarios = scenariosAfterParsing("VMPr_OPTIMIZER=MOMAVMP", "experiment.maxScenarios=all");

        assertEquals("MOMAVMP", Parameter.VMPR_OPTIMIZER);
        assertEquals(80, scenarios.size());
    }

    @Test
    public void seedIsParsed() throws Exception {
        scenariosAfterParsing("experiment.seed=42", "experiment.runId=R1");

        assertEquals(Long.valueOf(42L), Parameter.EXPERIMENT_SEED);
        assertEquals("R1", Parameter.EXPERIMENT_RUN_ID);
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownOptimizerIsRejected() throws Exception {
        scenariosAfterParsing("VMPr_OPTIMIZER=GA");
    }
}
