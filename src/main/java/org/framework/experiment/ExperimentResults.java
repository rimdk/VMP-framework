package org.framework.experiment;

import org.domain.PhysicalMachine;
import org.domain.Placement;
import org.domain.VirtualMachine;
import org.framework.ObjectivesFunctions;
import org.framework.Utils;
import org.framework.common.Constraints;
import org.framework.common.Parameter;
import org.framework.reconfigurationAlgorithm.memeticAlgorithm.MASettings;
import org.framework.reconfigurationAlgorithm.nsga2.Nsga2;
import org.framework.reconfigurationAlgorithm.nsga2.Nsga2Member;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Collects the results of a comparison run (MoMaVMP or NSGA-II) and writes them as CSV files into
 * {@code <output>/comparison/}. The original output files are not touched.
 * <p>
 * Files written per process (prefix = {@code <ALGORITHM>_<RUN_ID>_<TIMESTAMP>}, files are created with
 * CREATE_NEW so an existing file is never overwritten):
 * <ul>
 *   <li>{@code _scenarios.csv}: one row per scenario (algorithm, scenario, run) with the scenario metrics.</li>
 *   <li>{@code _reconfigurations.csv}: one row per reconfiguration event (selected objective vector, evaluations, time).</li>
 *   <li>{@code _pareto_front.csv}: NSGA-II only, final rank-0 population of every event with objectives.</li>
 * </ul>
 * Metric definitions are the framework's own (same as the files {@code power_consumption},
 * {@code economical_revenue}, {@code wasted_resources}, {@code scenarios_scores}); see EXPERIMENT_GUIDE.md.
 */
public final class ExperimentResults {

    public static final String SCENARIOS_HEADER = String.join(",",
            "algorithm", "scenario", "run_id", "seed", "ivmp_heuristic", "vmpr_triggering", "pm_config",
            "population_size", "number_of_generations", "reconfiguration_events", "objective_evaluations",
            "power_consumption_norm_avg", "economical_revenue_norm_avg", "wasted_resources_norm_avg",
            "selected_memory_migrated_sum", "fallback_events", "final_placement_score",
            "optimization_time_ms", "total_simulation_time_ms");

    public static final String RECONFIGURATIONS_HEADER = String.join(",",
            "algorithm", "scenario", "run_id", "seed", "reconfiguration_event_id", "objective_evaluations",
            "optimization_time_ms", "selected_power_consumption", "selected_economical_revenue",
            "selected_wasted_resources", "selected_memory_migrated", "selected_placement_score",
            "selected_feasible", "pareto_front_size", "feasible_front_size", "fallback_to_current");

    public static final String PARETO_HEADER = String.join(",",
            "algorithm", "scenario", "run_id", "seed", "reconfiguration_event_id", "front_member_index",
            "feasible", "violation", "power_consumption", "economical_revenue", "wasted_resources",
            "memory_migrated", "crowding_distance", "selected");

    private static final String RUN_STAMP = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

    private static String currentScenario = "";
    private static Long currentSeed = null;
    private static long scenarioStartNanos = 0L;
    private static int eventCounter = 0;
    private static final List<Event> events = new ArrayList<>();
    private static final List<String> scenarioRows = new ArrayList<>();
    private static final List<String> reconfigurationRows = new ArrayList<>();
    private static final List<String> paretoRows = new ArrayList<>();

    private ExperimentResults() {
        // Utility class
    }

    /**
     * Start of a scenario (called by the main loop, before the scenario is simulated). If
     * {@code experiment.seed} is set, the random generator is re-seeded with {@code seed + scenarioIndex},
     * so each scenario gets the same random stream whatever scenarios are selected with experiment.maxScenarios.
     *
     * @param scenarioFile  Scenario file name
     * @param scenarioIndex Position of the scenario in scenarios.conf (0-based)
     */
    public static void beginScenario(String scenarioFile, int scenarioIndex) {
        // Discard anything left from an earlier scenario that exited without endScenario().
        waitForPendingEvents();
        events.clear();
        currentScenario = scenarioFile;
        eventCounter = 0;
        currentSeed = null;
        if (Parameter.EXPERIMENT_SEED != null) {
            currentSeed = Parameter.EXPERIMENT_SEED + scenarioIndex;
            Utils.setRandomSeed(currentSeed);
        }
        scenarioStartNanos = System.nanoTime();
    }

    /**
     * Creates the record of one reconfiguration event. Must be called on the thread that submits the event
     * (the simulation loop), so event ids follow the simulation order.
     *
     * @return Event record, filled by the optimizer call
     */
    public static Event newEvent() {
        Event event = new Event(++eventCounter, currentScenario, currentSeed);
        events.add(event);
        return event;
    }

    /**
     * End of a scenario: waits for all reconfiguration events of the scenario, then stores the scenario row.
     *
     * @param ivmpHeuristic Heuristic code
     * @param pmConfig      PM configuration
     * @param power         Average normalised power consumption (as written to power_consumption)
     * @param revenue       Average normalised economical revenue (as written to economical_revenue)
     * @param wasted        Average wasted resources ratio (as written to wasted_resources)
     * @param score         Scenario score (as written to scenarios_scores)
     */
    public static void endScenario(String ivmpHeuristic, String pmConfig, Float power, Float revenue,
                                   Float wasted, Float score) {
        waitForPendingEvents();
        long totalMs = (System.nanoTime() - scenarioStartNanos) / 1_000_000L;

        long evaluations = 0;
        long optimizationMs = 0;
        double memoryMigrated = 0D;
        int fallbacks = 0;
        int count = 0;
        for (Event event : events) {
            if (!event.succeeded) {
                continue;
            }
            count++;
            evaluations += event.evaluations;
            optimizationMs += event.optimizationNanos / 1_000_000L;
            memoryMigrated += event.selectedObjectives[3];
            fallbacks += event.fallback ? 1 : 0;
            reconfigurationRows.add(event.toReconfigurationRow());
            paretoRows.addAll(event.toParetoRows());
        }

        scenarioRows.add(csv(
                Parameter.VMPR_OPTIMIZER, currentScenario, runId(), currentSeed, ivmpHeuristic,
                Parameter.VMPR_TRIGGERING, pmConfig, Parameter.POPULATION_SIZE, Parameter.NUMBER_GENERATIONS,
                count, evaluations, power, revenue, wasted, memoryMigrated, fallbacks, score,
                optimizationMs, totalMs));
        events.clear();
    }

    /**
     * Writes the CSV files of this process into {@code directory} (created if missing).
     *
     * @param directory Output directory, e.g. {@code outputs/comparison/}
     * @return Paths of the files written
     * @throws IOException if a file cannot be written
     */
    public static List<Path> writeCsv(String directory) throws IOException {
        Path dir = Paths.get(directory);
        Files.createDirectories(dir);
        String prefix = Parameter.VMPR_OPTIMIZER + "_" + runId() + "_" + RUN_STAMP;
        List<Path> written = new ArrayList<>();
        written.add(writeFile(dir.resolve(prefix + "_scenarios.csv"), SCENARIOS_HEADER, scenarioRows));
        written.add(writeFile(dir.resolve(prefix + "_reconfigurations.csv"), RECONFIGURATIONS_HEADER,
                reconfigurationRows));
        if ("NSGA2".equals(Parameter.VMPR_OPTIMIZER)) {
            written.add(writeFile(dir.resolve(prefix + "_pareto_front.csv"), PARETO_HEADER, paretoRows));
        }
        return written;
    }

    /**
     * @return Run identifier: experiment.runId if set, otherwise {@code run-<start timestamp>}
     */
    public static String runId() {
        return Parameter.EXPERIMENT_RUN_ID != null ? Parameter.EXPERIMENT_RUN_ID : "run-" + RUN_STAMP;
    }

    /**
     * Clears all collected rows (used by tests so that each test starts from an empty state).
     */
    static void reset() {
        events.clear();
        scenarioRows.clear();
        reconfigurationRows.clear();
        paretoRows.clear();
        eventCounter = 0;
        currentScenario = "";
        currentSeed = null;
    }

    private static void waitForPendingEvents() {
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        for (Event event : events) {
            pending.add(event.done);
        }
        if (!pending.isEmpty()) {
            CompletableFuture.allOf(pending.toArray(new CompletableFuture[0])).join();
        }
    }

    private static Path writeFile(Path file, String header, List<String> rows) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            writer.write(header);
            writer.newLine();
            for (String row : rows) {
                writer.write(row);
                writer.newLine();
            }
        }
        return file;
    }

    /**
     * Objective vector {power, economical revenue, wasted resources, memory migrated} of a placement, computed
     * with the framework's objective functions and the same previous placement as the optimizer.
     */
    static Float[] objectivesOf(Placement placement, List<VirtualMachine> previousVMs) {
        List<VirtualMachine> vms = VirtualMachine.cloneVMsList(placement.getVirtualMachineList());
        List<VirtualMachine> derivedVMs = VirtualMachine.cloneVMsList(placement.getDerivedVMs());
        List<PhysicalMachine> pms = PhysicalMachine.clonePMsList(placement.getPhysicalMachines());
        Float[] threeObjectives = ObjectivesFunctions.loadObjectiveFunctions(vms, derivedVMs, pms);
        Float memoryMigrated = ObjectivesFunctions.migratedMemoryBtwPM(previousVMs, vms, pms.size());
        return new Float[]{threeObjectives[0], threeObjectives[1], threeObjectives[2], memoryMigrated};
    }

    /**
     * Feasibility of a placement with the framework's own PM overload test (Constraints.checkPMOverloaded).
     */
    static boolean isFeasible(Placement placement) {
        for (PhysicalMachine pm : placement.getPhysicalMachines()) {
            List<VirtualMachine> vmsInPm = Utils.filterVMsByPM(placement.getVirtualMachineList(), pm.getId());
            if (Constraints.checkPMOverloaded(pm, vmsInPm, Parameter.PROTECTION_FACTOR)) {
                return false;
            }
        }
        return true;
    }

    private static String csv(Object... values) {
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                row.append(',');
            }
            Object value = values[i];
            if (value == null) {
                continue;
            }
            if (value instanceof Double && ((Double) value).isInfinite()) {
                row.append("inf");
            } else {
                row.append(value);
            }
        }
        return row.toString();
    }

    /**
     * One reconfiguration event. Filled by {@code StaticReconfMemeCall} on the worker thread; {@link #done}
     * completes when the optimizer call has finished (successfully or not).
     */
    public static final class Event {
        private final int eventId;
        private final String scenario;
        private final Long seed;
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private volatile boolean succeeded;
        private long evaluations;
        private long optimizationNanos;
        private Float[] selectedObjectives;
        private Float placementScore;
        private boolean fallback;
        private boolean selectedFeasible;
        private List<Nsga2Member> front = new ArrayList<>();
        private Nsga2Member selected;

        private Event(int eventId, String scenario, Long seed) {
            this.eventId = eventId;
            this.scenario = scenario;
            this.seed = seed;
        }

        /**
         * Records a MoMaVMP call. Evaluations are derived from the settings (population x (generations + 1)),
         * because MoMaVMP does not expose its evaluation counter.
         */
        public void recordMomavmp(Placement result, List<VirtualMachine> previousVMs, MASettings settings,
                                  long optimizationNanos) {
            this.optimizationNanos = optimizationNanos;
            this.evaluations = (long) settings.getPopulationSize() * (settings.getNumberOfGenerations() + 1);
            this.placementScore = result.getPlacementScore();
            this.selectedObjectives = objectivesOf(result, previousVMs);
            this.selectedFeasible = isFeasible(result);
            this.succeeded = true;
            done.complete(null);
        }

        /**
         * Records an NSGA-II call (evaluations are counted by the algorithm).
         */
        public void recordNsga2(Nsga2.Result result, List<VirtualMachine> previousVMs, long optimizationNanos) {
            this.optimizationNanos = optimizationNanos;
            this.evaluations = result.getEvaluations();
            this.placementScore = result.getPlacement().getPlacementScore();
            this.selectedObjectives = objectivesOf(result.getPlacement(), previousVMs);
            this.selectedFeasible = isFeasible(result.getPlacement());
            this.fallback = result.isFallback();
            this.front = result.getFinalFront();
            this.selected = result.getSelected();
            this.succeeded = true;
            done.complete(null);
        }

        /**
         * Marks the event as finished without a result (optimizer threw). Keeps the scenario waiting logic correct.
         */
        public void fail() {
            done.complete(null);
        }

        private String toReconfigurationRow() {
            int feasible = 0;
            for (Nsga2Member member : front) {
                feasible += member.isFeasible() ? 1 : 0;
            }
            return csv(Parameter.VMPR_OPTIMIZER, scenario, runId(), seed, eventId, evaluations,
                    optimizationNanos / 1_000_000L, selectedObjectives[0], selectedObjectives[1],
                    selectedObjectives[2], selectedObjectives[3], placementScore, selectedFeasible,
                    front.isEmpty() ? "" : front.size(), front.isEmpty() ? "" : feasible, fallback);
        }

        private List<String> toParetoRows() {
            List<String> rows = new ArrayList<>();
            if (!"NSGA2".equals(Parameter.VMPR_OPTIMIZER)) {
                return rows;
            }
            for (int index = 0; index < front.size(); index++) {
                Nsga2Member member = front.get(index);
                Float[] objectives = member.getObjectives();
                rows.add(csv(Parameter.VMPR_OPTIMIZER, scenario, runId(), seed, eventId, index,
                        member.isFeasible(), member.getViolation(), objectives[0], objectives[1], objectives[2],
                        objectives[3], member.getCrowding(), member == selected));
            }
            return rows;
        }
    }
}
