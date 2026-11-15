# Experiment guide: MoMaVMP vs NSGA-II (VMP-framework)

All paths are relative to the project root `C:\Users\Rim Doukha\Documents\new phd\VMP-framework`.

## 1. Build

PowerShell:

```powershell
cd "C:\Users\Rim Doukha\Documents\new phd\VMP-framework"
mvn clean package
```

This compiles the code, runs the unit tests (`src/test/java`, 23 quick tests, no simulation) and creates `target\DynamicVMPFramework.jar`. To skip the tests: `mvn clean package -DskipTests`.

IntelliJ IDEA: open the project, then Maven panel > `DynamicVMPFramework` > Lifecycle > `clean` and `package`.

## 2. Configuration files and keys

The program takes four arguments: `inputs/ivmp.conf inputs/vmpr.conf inputs/scenarios.conf outputs/`. The three `.conf` files are read as one list of `key=value` lines plus scenario file names. Keep these rules:

- A line with `=` is a setting. A line without `=` is a scenario file name (`SCENARIOS` is the header).
- Do not add `#` comment lines: they would be read as scenario files.

| Key | File | Values | Default when absent |
|---|---|---|---|
| `VMPr_OPTIMIZER` | `inputs\vmpr.conf` | `MOMAVMP`, `NSGA2` | `MOMAVMP` (original behaviour) |
| `experiment.maxScenarios` | `inputs\scenarios.conf` | `3`, `5`, `10`, ... or `all` | all scenarios |
| `experiment.seed` | `inputs\vmpr.conf` | an integer | unseeded (original behaviour) |
| `experiment.runId` | `inputs\vmpr.conf` | any text without spaces or commas | `run-<start time>` |

`inputs\vmpr.conf` currently contains no settings. `inputs\scenarios.conf` already ends with `experiment.maxScenarios=3`.

Note: when `experiment.seed` is set, each scenario is seeded with `seed + scenario position` (position in `scenarios.conf`, starting at 0). A scenario therefore gets the same random stream whether it runs alone or inside a longer list.

The other settings (`iVMP`, `VMPr_TRIGGERING`, `PM_CONFIG`, `POPULATION_SIZE`, `NUMBER_GENERATIONS`, ...) are read from `inputs\ivmp.conf` and are shared by both optimizers.

## 3. Select the optimizer

Edit `inputs\vmpr.conf` (only one line is needed):

```text
VMPr_OPTIMIZER=MOMAVMP
```

or

```text
VMPr_OPTIMIZER=NSGA2
```

Then run the same command (below). Delete the line to get MoMaVMP again.

## 4. Commands (three scenarios)

Current state of the inputs: `scenarios.conf` ends with `experiment.maxScenarios=3`, so these two commands run pppp-00, pppp-01 and pppp-02.

MoMaVMP:

```powershell
# inputs\vmpr.conf must contain: VMPr_OPTIMIZER=MOMAVMP (or no VMPr_OPTIMIZER line)
java -jar target\DynamicVMPFramework.jar inputs\ivmp.conf inputs\vmpr.conf inputs\scenarios.conf outputs\
```

NSGA-II (same scenarios, same settings):

```powershell
# inputs\vmpr.conf must contain: VMPr_OPTIMIZER=NSGA2
java -jar target\DynamicVMPFramework.jar inputs\ivmp.conf inputs\vmpr.conf inputs\scenarios.conf outputs\
```

IntelliJ IDEA: Run > Edit Configurations > Application, main class `org.framework.DynamicVMP`, Program arguments `inputs/ivmp.conf inputs/vmpr.conf inputs/scenarios.conf outputs/`, Working directory = the project root. Run once per optimizer after changing `inputs\vmpr.conf`.

Both runs should use the same `experiment.seed` and `experiment.runId` in `inputs\vmpr.conf`. Example for a fair pair:

```text
VMPr_OPTIMIZER=NSGA2
experiment.seed=1
experiment.runId=pilot3
```

## 5. Making the two algorithms identical

Both runs read the same files, so the following are identical by construction:

- workload scenarios (`inputs\scenarios.conf` and the CSV traces in `inputs\`);
- PM configuration (`PM_CONFIG`), iVMP heuristic (`iVMP=FF` in `ivmp.conf`), and VMPr triggering (`VMPr_TRIGGERING`, `VMPr`, `VMPr_RECOVERING` in the same files);
- objectives (4 minimised objectives), placement score, protection factors, penalty factors and constraints;
- population size and number of generations (`POPULATION_SIZE`, `NUMBER_GENERATIONS`, default 100 and 100), crossover probability (1.0) and mutation (uniform, 1/N per VM);
- the acceptance rule of the managers (a reconfiguration is applied only if its `placement_score` is better).

Caveats (see the final report): the number of reconfigurations differs between the two runs because it depends on which migrations were accepted; MoMaVMP's objective evaluations are derived, not counted.

## 6. Where the CSV files are saved

`outputs\comparison\`, one set of files per process:

```text
outputs\comparison\<ALGORITHM>_<RUN_ID>_<YYYYMMDD_HHMMSS>_scenarios.csv
outputs\comparison\<ALGORITHM>_<RUN_ID>_<YYYYMMDD_HHMMSS>_reconfigurations.csv
outputs\comparison\<ALGORITHM>_<RUN_ID>_<YYYYMMDD_HHMMSS>_pareto_front.csv   (NSGA2 only)
```

Files are created with "create new" semantics: an existing file is never overwritten.

Columns:

- `_scenarios.csv` (one row per algorithm, scenario and run): `algorithm, scenario, run_id, seed, ivmp_heuristic, vmpr_triggering, pm_config, population_size, number_of_generations, reconfiguration_events, objective_evaluations, power_consumption_norm_avg, economical_revenue_norm_avg, wasted_resources_norm_avg, selected_memory_migrated_sum, fallback_events, final_placement_score, optimization_time_ms, total_simulation_time_ms`
- `_reconfigurations.csv` (one row per reconfiguration event): `algorithm, scenario, run_id, seed, reconfiguration_event_id, objective_evaluations, optimization_time_ms, selected_power_consumption, selected_economical_revenue, selected_wasted_resources, selected_memory_migrated, selected_placement_score, selected_feasible, pareto_front_size, feasible_front_size, fallback_to_current`
- `_pareto_front.csv` (NSGA-II only, rank-0 members of the final population of each event): `algorithm, scenario, run_id, seed, reconfiguration_event_id, front_member_index, feasible, violation, power_consumption, economical_revenue, wasted_resources, memory_migrated, crowding_distance, selected`

How to tell which CSV belongs to which algorithm: the file name starts with `MOMAVMP_` or `NSGA2_`, and the `algorithm` column is in every row. `run_id` and `seed` identify the run.

Metric definitions (unchanged framework definitions):

- `power_consumption_norm_avg`, `economical_revenue_norm_avg`, `wasted_resources_norm_avg`: the scenario averages written to the existing files `power_consumption_*`, `economical_revenue_*`, `wasted_resources_*`.
- `final_placement_score`: the existing `scenarios_scores_*` value.
- `selected_*` columns in `_reconfigurations.csv`: the objective vector and score of the placement the optimizer returned (power in framework units, wasted resources as ratio, memory in the framework's migrated-memory units).
- `optimization_time_ms`: time inside the optimizer only. `total_simulation_time_ms`: wall time of the whole scenario.
- `selected_feasible`: `true` if the returned placement passes the framework's own PM overload test (`Constraints.checkPMOverloaded`) on every PM.

The original output files (`power_consumption_*`, `f1_f2_f3_results_*`, ...) are still written to `outputs\` unchanged.

## 7. Full experiment (all scenarios) later

Remove the line `experiment.maxScenarios=3` from `inputs\scenarios.conf` (or change it to `experiment.maxScenarios=all`), then run the same command. In the smoke tests on one scenario, a run took about 40-60 s per scenario, so 80 scenarios take roughly one to two hours per optimizer. Run the two optimizers one after the other (not in parallel with a shared output folder).

## 8. Quick checks

- `NSGA2` rows should have `fallback_to_current=false` and `selected_feasible=true` in most events. A `false` in `selected_feasible` for MoMaVMP is expected in some events (see the final report).
- `objective_evaluations` for NSGA-II is `POPULATION_SIZE x (NUMBER_GENERATIONS + 1)` per event (100 x 101 = 10100 with the default settings). MoMaVMP uses the same number by construction.
