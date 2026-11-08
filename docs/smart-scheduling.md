# Smart daily scheduling

Status: the planner, its data layer, the planning screen, and the unit tests are written. Nothing has been compiled or run, because the environment has no working JDK, Android SDK, or Gradle. The planner's logic was checked separately in a reference model (see Verification).

## What it does

The assistant recommends start times for flexible tasks on one day. It only recommends. A plan changes nothing until the user applies it, and applying replaces the recommendations for that day. The feature is optional: it appears behind the "Plan" button on the schedules screen, and it changes no existing schedule, routine, or task.

## Inputs

For one day the planner works from:

- **Working hours** and **breaks**, stored on the device. Breaks are the user's own.
- **Fixed commitments**: pending scheduled occurrences that day. A routine lasts as long as its steps together. A scheduled task uses its planning duration. Completed and skipped occurrences do not block time.
- **Planned tasks**, each with a duration, a priority from 1 (low) to 5 (high), an optional finish-by time, and optional prerequisites. Completed tasks are not planned.
- **Manual pins**: start times the user chose.
- **Previous recommendations**: the starts from the last applied plan, used to keep a plan steady.

## Algorithm

Implemented in `domain/planning/SmartScheduler.kt`. It is deterministic. The result depends only on the request, never on the order tasks were listed, the clock, or chance.

1. Commitments, breaks, and the working hours are unavailable.
2. Pins are placed first, in start order, and never move. A pin is reported as a conflict instead of placed when it:
   - is not on the 15-minute grid,
   - falls outside working hours,
   - overlaps a commitment, a break, or an earlier pin,
   - finishes after its deadline,
   - starts before a pinned prerequisite has finished.
3. A task starts only after each of its prerequisites ends. Its latest finish is then tightened so that every dependent can still finish by its own latest finish. A pinned dependent fixes that bound at its pinned start.
4. The remaining tasks are placed one at a time in this order: earliest latest finish, then higher priority, then longer duration, then id. Priority therefore decides between tasks that compete for the same time, and deadlines decide what is urgent.
5. A task keeps its previous start if that start is still free and valid. Otherwise it takes the earliest free slot.
6. Durations round up to the 15-minute grid.

Each task that cannot be placed reports every reason found, not only the first.

## Why a task cannot be scheduled

| Reason | What the user sees |
| --- | --- |
| Not enough free time before its finish time | Needed minutes, free minutes before the finish time, and what blocks the window |
| Enough total time, but no single stretch long enough | Needed minutes and the largest free stretch |
| Its window closed before it could start | The earliest start and latest finish |
| A prerequisite could not be placed | Which prerequisite, and that it waits for it |
| Prerequisites depend on each other in a loop | The tasks in the loop |

## Manual overrides

A pin is the user's decision. The planner keeps it, and a pin that cannot be honored is reported in a separate section, with the reasons, rather than moved. Pins never change on their own:

- Adding, removing, or changing another task does not move a pinned block.
- A pin that now conflicts blocks **Apply** until the user unpins it or moves it.
- A pin for a task that is no longer planned is reported and also blocks **Apply**.

Nudging a recommendation (Earlier or Later) creates a pin at the new time. Unpinning returns the task to automatic placement.

## Preview and apply

The screen re-plans whenever the settings, the planned tasks, or the pins change. **Preview again** re-plans on demand. **Apply plan** saves the recommendations for the day, and the pinned blocks are saved with them. Nothing is applied while a pin conflicts.

## Storage

- Working hours and breaks: DataStore, device-local.
- Planned tasks and their prerequisites: Room tables `planning_task` and `planning_dependency`, migration `7 to 8`.
- The applied plan for a day: `planned_block`, with a pinned flag.

Planning data is device-local and is not synced. It is not part of the sync outbox.

## Known limits

- The planner covers one day at a time, from the device's current date.
- Durations are in whole minutes and are rounded up to 15 minutes.
- Fixed commitments that start before the working day are clipped to it.
- Routine lengths come from the sum of step durations. A routine without durations uses 30 minutes.
- The screen lets the user plan a task and its prerequisites. Editing the working hours requires a valid "HH:mm" time, and the start must be on the quarter hour.
- Planned tasks that are later completed drop out of the request, so a dependency on them is satisfied.
- Recommendations for a day are not refreshed in the background. They change when the user opens the screen or presses Preview.

## Verification

- **Reference model.** The planner's rules were first checked in an independent Python model of the same algorithm. Fifteen scenarios passed: basic placement, conflicting deadlines, insufficient time, fragmented time, dependency order, back-propagated deadlines, unplaced prerequisites, cycles, authoritative pins, pins that overlap or miss a deadline, stable rescheduling, input-order independence, grid rounding, stale pins, and a pin that does not block automatic placement. The model is not part of the repository.
- **Unit tests.** `SmartSchedulerTest` covers the same cases in Kotlin, plus adjustments (nudge, unpin, remember), misaligned and out-of-hours pins, pins that conflict with a prerequisite, and invalid input. They have not been run.
- **Not run.** No Kotlin compiler, JDK, Android SDK, or Gradle was available. The Room migration and the schema check have not run.
