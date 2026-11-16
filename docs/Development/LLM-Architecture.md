# Shared advisor controller

Windows and Android use `forge.llm.AdvisorController` in `forge-ai` for connection
jobs and ownership of the live advisor. Native views provide a serialized UI
executor (`SwingUtilities.invokeLater` or `Activity.runOnUiThread`) and implement
the controller's `View` callbacks.

| Responsibility | Owner |
| --- | --- |
| Provider selection, model validation, sign-in/out, model refresh and connection tests | `AdvisorController` |
| Background connection worker, cancellation, stale callback rejection, pause/resume and session replacement | `AdvisorController` |
| Model, effort, timeout and credential identity validation | `AdvisorSettings` |
| Gameplay snapshots, request budgets, deck plans, strategy memory and decision reports | `LiveAdvisor` and the game bridges |
| Browser opening, Android foreground handoff, settings encryption and file locations | Native adapters |
| Native widgets, activity/history display and UI lifecycle | Native adapters |

`AdvisorSettings` is an in-memory configuration. Its string representation excludes
the OpenRouter key. Platform adapters persist their existing settings formats;
desktop preferences remain non-secret and Android settings remain encrypted.

## Lifecycle

- Enabling cancels any older connection job and pauses an existing advisor while
  checking the selected model in the background.
- Successful checks return to the UI thread. `beforeEnable` must save the platform's
  settings successfully before the advisor is installed.
- Enabling unchanged settings resumes the same advisor on both platforms, retaining
  deck plans, strategy memory, budgets, diagnostics and provider rule excerpts.
  Changing provider, model, effort, timeout or credentials replaces that session.
- Pausing immediately cancels pending connection/gameplay work. The UI persists its
  paused preference even when a subsequent action supersedes the status message.
- Model refresh and connection tests do not implicitly pause an already running
  advisor. Sign-in and sign-out explicitly pause first.
- Every connection job has a generation. Results queued before cancellation,
  replacement or close cannot enable an advisor or update an obsolete UI.
- Closing cancels connection work, closes the owned advisor and removes its game
  bridge only if it still owns that bridge. Closing an older controller cannot
  uninstall a replacement controller's advisor.

Startup policy stays native: Android can restore its saved Enable preference;
Windows starts paused. Closing the setup panel leaves the controller alive.
Destroying the Android activity closes its controller and rules subscription.

`AdvisorControllerTest` exercises these contracts with a fake HTTP provider and a
queued UI executor. It covers resuming versus replacement, credential rotation,
cancelled and delayed callbacks, old-controller teardown, model refresh, invalid
settings, failed persistence, sign-in cancellation and connection-test validation.
