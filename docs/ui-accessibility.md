# Interface refinement, accessibility, and performance audit

Status: the changes below are written. Nothing has been compiled or run, because this environment has no working JDK, Android SDK, or Gradle, and no device or emulator. The Compose tests and the ViewModel test are written and have not executed. Treat every item as unverified until `./gradlew testDebugUnitTest connectedDebugAndroidTest` runs on a machine with those tools.

## Audit results and changes

| Area | Finding | Change |
| --- | --- | --- |
| Colors | The theme used Material's default palettes and ignored the wallpaper. | From Android 12 (API 31), dynamic light and dark schemes come from the wallpaper. Earlier versions keep the standard palettes. There is no in-app switch. |
| Dark mode | Dark mode followed the system setting. A search found no hard-coded colors in `presentation/`, so no text color is fixed to a light background. | No change. The dark scheme is checked by reading the code, not by rendering it. |
| Content descriptions | Every icon button had a label, but the step controls read "Move step up", "Move step down", and "Remove step" for every step. A screen reader cannot tell them apart. | Labels now name the step: "Move step 2 up", "Remove step 3". |
| Headings | Section titles were plain text, so screen readers could not jump between sections. | The editor's Steps section, and the planning screen's section titles, are marked as headings. |
| Live status | Sync phase changes and the busy state were not announced. | The sync phase and the busy indicator are polite live regions. Other messages are not yet (see Limits). |
| Large text | No text sits in a fixed-height box, so text can grow without clipping. | No layout change. The large-text tests check that key controls stay visible at 2× font scale. |
| Keyboard | Text fields had no next or done actions, so moving between fields meant tapping each one. | Editor: title, then step name, then description, then duration move on Next, and duration closes the keyboard on Done. Sign-in: email moves to password on Next, and Done on password signs in when the form is ready. |
| Loading | The editor showed an empty form while the routine loaded. The planning screen was blank until its settings arrived. The account screen gave no sign of work in progress. | Editor shows a progress indicator in place of the form while loading. Planning shows one until settings arrive. The account screen shows a progress bar while busy. |
| Responsive layout | Content stretched across tablets. | `CenteredContent` limits content to 640 dp and centers it on wide screens. Applied to Home, the editor, the account screen, and the planning screen. |
| Motion | Step rows jumped when a step was moved or removed. | Keyed step rows use `animateItem()`, so moves and removals ease into place. No other new animations were added. |
| Destructive actions | Routine delete, schedule stop, and calendar removal already confirm. Step removal and home-screen delete offer Undo. The two account conflict choices did not confirm. | "Delete anyway" (which also removes the other device's change) and "Discard the change" (which drops unsent edits) now ask first. Keep, restore, and planning removal do not destroy data, so they run directly. |

## Lifecycle and recomposition

- Every screen reads state with `collectAsStateWithLifecycle()`. The only other collector is an event channel inside a keyed `LaunchedEffect`.
- The planning screen computed its title map on every recomposition. It is now remembered on the planned and candidate lists.
- The Next keyboard actions in the editor and sign-in form are remembered on the focus manager. The sign-in Done action is rebuilt on each composition, because it reads the form's current values.
- **Planning race fixed.** Each replan was a separate coroutine. A slow, older replan could finish after a newer one and overwrite its result. A new replan now cancels the previous one. Apply waits for a replan that is still running before it reads the result, so a pin changed just before Apply is checked first. The unit test `PlanningViewModelTest` holds one replan open, requests Apply, and checks that the conflicting pin blocks the write.

## Performance

- **Queries.** The tables that screens read most already have indexes: scheduled occurrences by time, routine, and task; routine steps and tasks by routine; execution sessions and step events by session, routine, and date. A search of the DAO queries for the common patterns that defeat an index (functions on a column, `LIKE`, and `IN` subqueries) found none. No schema change was made, because nothing measured needs one.
- **Widget wakeups.** The run-widget refresher woke every minute for the whole life of the process, even when no routine was running. It now starts ticking when a run starts and stops when the run ends. The refresh rate during a run is unchanged.
- **Startup.** Most work in `Application.onCreate` is already launched on the application scope. The synchronous part is object creation and scheduling jobs, which is small.
- **Not measured.** No device was available, so startup time, query times, and frame timing are not reported. A macrobenchmark would be the right measurement and needs a device.

## Tests added

- `test/.../presentation/planning/PlanningViewModelTest.kt`: a conflicting pin blocks Apply while a replan is running, Apply writes a valid plan, and an older replan cannot overwrite a newer one.
- `androidTest/.../presentation/routine/editor/RoutineEditorScreenTest.kt`: numbered step labels, loading state, heading marking, every clickable control labelled, and large-text reachability (extended).
- `androidTest/.../presentation/account/AccountScreenTest.kt`: sign-in enabled only with email and password, trimmed input, confirmation for "Delete anyway" and "Discard the change", no confirmation for "Restore it", busy state, live-region phase, labels, and large text.
- `androidTest/.../presentation/planning/PlanningScreenTest.kt`: loading state, a conflicting pin explained and blocking Apply, heading marking, labels, and large text.
- `androidTest/.../testing/Accessibility.kt`: `assertEveryClickableIsLabelled()` checks the merged semantics tree, the same tree a screen reader reads. `WithFontScale` renders at a chosen font scale.

The accessibility check is a custom check on the semantics tree. It is not Google's accessibility scanner. Compose's own accessibility-validation API was not used, because its documentation could not be confirmed here.

## Limits and what was not verified

- **Not run.** No test, build, or lint step has executed. Compile errors, Hilt wiring, and the Compose tests themselves are all unknown.
- **No TalkBack or device check.** Labels and live regions are asserted in the semantics tree. Actual announcements, focus order on a hardware keyboard, and reading order are not checked.
- **Keyboard actions** (Next and Done) are set but not exercised by a test, because the test helper for IME actions was not confirmed.
- **Large text** is tested with a density override. The system font-scale setting itself is not changed in a test.
- **Tablets** get a centered column only. There is no two-pane layout. Agenda, schedule list, statistics, calendar settings, and the run screen are not wrapped yet.
- **Dynamic color** has no in-app opt-out. Contrast of wallpaper-derived palettes was not audited.
- **Reduced motion** has no app-level handling. The only new animation is `animateItem()` on the editor steps.
- **Messages** on the account screen are not live regions. Only the sync phase and the busy indicator are.
- **Glance widgets** use their own theming and were not reviewed here.
- **Performance** was audited by reading code and query text. Nothing was measured.
