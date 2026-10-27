# Android LLM play

The Android app uses the same provider, deck strategy, live decision validation and fallback code
as the desktop prototype. Both support actions, attacker/blocker declarations, visible single-entity
resolution choices, sacrifice/destroy resolution prompts, discarding to maximum hand size, and
scry/surveil in two-player Constructed games. Forge handles mana payment, mulligans, combat damage
division, banding and unsupported resolution choices.
Recent-turn memory remains deferred.

## Connect on the device

Choose **LLM Advisor** in the Classic Mode home menu (below Settings; scroll the menu if needed),
or in the match's **Game** menu. There is no floating button. The panel has
**Connect**, **Input**, **Output** and **Status** tabs. **Back to game** closes the panel.

For **ChatGPT subscription**, keep `gpt-6-luna` and `high`, then use **Continue with ChatGPT**.
Complete sign-in and consent in the device's browser, then return to Forge. Android allows 15
minutes for a sign-in attempt. After browser authorization, return to Forge within two minutes;
the app finishes the token exchange in the foreground so background network restrictions do not
interrupt it. **Cancel connection request** ends it immediately. This installation has
its own stable host identifier and tokens; it does not copy the Windows installation's rotating
credentials. The callback binds only to `127.0.0.1` and uses PKCE, state and ID-token validation.

For **OpenRouter**, choose the provider, enter an exact OpenRouter model ID and paste the API key
into the masked key field. **Save settings** stores it locally. A blank field keeps the saved key;
**Forget OpenRouter key** removes it. There is no provider switching on a request failure.

**Test connection** sends one small request to the selected model using its provider allowance.
Its exact instructions, input, final answer, latency and usage appear in the monitor. After a
successful test, **Enable LLM** checks model availability and remembers that LLM play is enabled.
Start a normal local Constructed match with one human and one AI. Deck analysis starts in the
background after deck setup, before play/draw selection, overlapping that choice and mulligans.
The first supported decision reuses the same analysis (waiting for it if still running), and the
resulting compact strategy accompanies later choices. If the advisor is enabled after startup,
analysis starts with the first supported choice. Suggested plan
lengths are soft targets; valid plans up to 12,000 total characters are accepted. If planning fails,
the same move and later moves still use the LLM without deck strategy context. Planning is attempted
once per deck in the current advisor session, so a failed plan does not repeatedly fill the turn list
or count toward the three failed decisions that pause the advisor.

**Pause / use Forge** cancels a pending model request and returns control to Forge. Three failed
decisions also pause the advisor. **Enable LLM** starts it again. Closing the panel leaves the
advisor enabled; closing the app stops the running advisor without clearing the user's preference.
A fresh app launch automatically reconnects in the background when the previous successful choice
was **Enable LLM**, using the saved provider, model, reasoning effort and encrypted credentials.
It checks model availability without making an inference/test-completion request or opening a
browser. A failed startup connection leaves Forge in control and keeps the preference for a later
launch; its failure is shown in Status. **Pause / use Forge**, disconnecting, forgetting a key, or
changing saved connection settings clears automatic enable until **Enable LLM** succeeds again.
Existing installations need to enable once after this update because earlier versions never saved
the enabled choice. Ordinary shutdown and automatic provider-error pausing do not erase that choice.

Startup preference verification used the Android emulator's saved ChatGPT connection: a successful
enable followed by a force-stop/cold launch restored the connection and enabled the advisor without
pressing Enable again. After an explicit Pause, another force-stop/cold launch remained paused.
The APK also passed compilation, alignment and signature verification. These checks did not send
a model test-completion request or start a match.

**Insights** summarizes the current advisor session on Android and in the Windows monitor. It shows
consultations, validated selections, engine action results, model errors, and reasons that observed
choices stayed with Forge. It also counts unsupported alternative ability evaluations by family;
these may still be selected through Forge's prepared move. Repeated evaluations count again, and
these counters do not cover every decision in the engine. A validated selection is distinct from
an action actually played. Combat reports separately whether Forge's declaration was preserved or
an alternative was applied.

Timing reports include the sample count, average, longest and total milliseconds for model replies,
deck preparation, the worker queue and game-thread waiting. Game waiting includes the other stages,
so timing rows overlap. Individual decision reports also record `queueMillis`. Insights contains
aggregate labels and durations, not hands, prompts or credentials. It resets with a new advisor
instance. The monitor throttles intermediate aggregate updates to avoid flooding the UI.

Visible card context includes `token`, visible `attachmentIds`, and `attachedToId` for an Aura or
Equipment attached to a visible card or player. These IDs link existing snapshot objects rather
than exposing hidden card identities.

Live requests set `strategyNotesRequested`. The model may return a conditional `strategyNotes`
memo (suggested 600 characters, hard maximum 2,000) alongside its action and reason. Input shows
accepted prior notes in `strategyMemory`; Output shows proposed notes and their status. Invalid
optional notes are ignored without rejecting a valid move. Empty notes clear memory after acceptance;
omitting notes retains the previous memo until it expires. Notes are isolated per AI player and
game, expire after more than four game turns, and reset with a new advisor instance. They are intent,
not an event history or proof that a spell resolved. Recent observed-event history remains future work.

Notes for played actions are committed only when the engine reports success; rejected selections
or declined plays discard the proposal. Passes and resolution choices commit at the validated
selection handoff. Combat commits only after its declaration is applied and revalidated, or Forge's
prepared declaration is retained. The next request never promotes an unconfirmed prior proposal.
Memory updates use the existing worker and do not send an extra model request.

Additional battlefield activation alternatives support draw, damage, life gain, pump, destroy,
tap, untap and attach effects. New alternatives must have ordinary costs with no X, no linked
sub-ability or modal selector, and either no targets or exactly one target. Timing, affordability,
targets and summoning sickness are engine-checked. This includes straightforward effects such as
Jayemdae Tome, Prodigal Pyromancer and Bonesplitter's equip. Mana payment and any remaining cost
choices stay with Forge. Double-faced sources, more complex activation structures and effects outside
these families retain the prior fallback behavior. Candidates now include structured source IDs,
costs, effect types and target IDs, in addition to their descriptions.

Single-card library searches such as Tinker also use the advisor at the engine's dedicated
`chooseSingleCardForZoneChange` callback. This callback receives the exact eligible pool and a
`DelayedReveal` describing what this search permits the chooser to inspect. Only eligible library
cards included in that explicit reveal (with the matching owner and library zone) can bypass the
normal hidden-card check. Other visible offered cards retain normal visibility rules. No global
viewing permission is changed, and ordinary snapshots still contain library counts only. Offered
candidates are sorted by name and ID rather than library order. Blind searches, missing reveal
permission and oversized pools preserve Forge's prepared choice. Subsequent picks in a multi-card
search without a fresh reveal may also stay with Forge; this is not general multi-card tutor support.

Combat alternatives are enumerated in scratch combat objects and validated using the engine's attack
constraints and block validators before applying them. No candidate changes the live declaration
during the request. Attack/block costs stay in Forge's prepared baseline; alternatives are cost-free.
Search is bounded to 256 declarations and 16,384 nodes; oversized or banding positions use the
unchanged Forge declaration with an explanation in Status. Card subsets are bounded to 128 choices;
scry/surveil cover up to six looked-at cards, preserving each listed pile's order. Hidden card choices
stay with Forge. Actions and these additional choices each have a 20-request per-turn budget, with
the existing shared 150-request per-game cap. Priority snapshots also include combat assignments
and current keywords so combat tricks have the relevant context.

The request selector retains up to 60 entries. **Input** shows the complete instructions and user
message, including the AI's hand. **Output** shows the final visible model answer and report;
**Status** shows validation, executed moves, cancellation and fallback. Hidden reasoning and
credentials are not displayed. The API-key field is masked, excludes autofill, and blocks screen
capture while focused.

Credentials and settings are encrypted with AES-256-GCM using a nonexportable Android Keystore
key. All LLM state lives beneath `Context.getNoBackupFilesDir()/llm`, outside shared storage and
Android backup. Recorded decisions and deck plans are owner-only private files. Reinstalling the
app after uninstall, or clearing its storage, requires connecting again.

For unexplained app closes, reopen Forge and use **Classic Mode → Settings → Files → Export Logs**.
On Android 11 and newer the ZIP also includes `forge.android-exits.log`, containing Android's
last 20 process-exit records and up to three available native-crash or ANR traces. The system
may already retain the previous failure, so reproducing sign-in is not required to collect it.

## Local Windows build

With the adjacent workspace Java 17, Maven 3.8.1, Android SDK 35/build tools 35.0.0 and local Maven
repository installed:

```powershell
.\tools\build-android-local.ps1
.\tools\start-android-local.ps1
```

The build helper temporarily uses free `F:`, `R:` and `S:` drive aliases to keep D8's command line
below Windows' limit. It releases the aliases afterward, then produces a debug-signed APK under
`forge-gui-android/target/signed-llm/`. Paths can be supplied explicitly as script parameters.
The start helper uses 8 GB RAM and an isolated temporary directory. Start the emulator with normal
network access when using a restricted development shell; otherwise provider connections fail.

The emulator needs the ordinary Forge asset pack, as for upstream Forge. Credential setup does
not put secrets in that pack or on the SD card. No credentials are bundled in the APK.

## Verified on October 25, 2026

The Android 15 emulator with 8 GB RAM completed its own ChatGPT registration and encrypted token
storage. GPT-6 Luna with high reasoning completed a native connection check in 1,797 ms. In a
real local human-versus-AI Constructed match, it analyzed the 60-card red demonstration deck,
played a Mountain (3,615 ms), then cast Goblin Arsonist (2,851 ms) where the baseline was pass.
The human passed priority, and the creature resolved onto the battlefield. Input, Output and
Status displayed the deck plan, decision context, visible answers, validation and execution.
The panel remained usable while deck analysis was pending. Pause/resume was exercised, and a
fresh app process loaded the encrypted connection and completed another inference without login.

All 36 shared LLM checks passed after the initial port. After adding the configurable sign-in
deadline, the 23 provider/authentication/live-play checks passed, including the new deadline
check. Both Android packaging and v2/v3 debug-signature verification passed. This smoke test
does not cover a complete game, an OpenRouter account, or physical Android hardware.

The menu/combat update passed 19 targeted live-play and combat/choice checks. These cover mandatory
attacks and blocks, menace, flying, additional blocks, bounded search, stale/invalid responses,
controller routing, card selection and hidden-information boundaries. The Android APK built and
passed signature verification. Both Classic Mode home and match Game menu entries opened the
advisor in the emulator, with the floating button removed and the saved ChatGPT connection retained.
In a live match GPT-6 Luna/high played Mountain, cast Raging Goblin, and reviewed its first-turn
attack declaration in 2,660 ms: it chose to attack for one damage into an empty defending battlefield.
Status recorded a validated `declare_attackers` selection and the human's life went from 20 to 19.
The updated blocking and resolution paths have engine tests; they were not all exercised against
the live provider in the emulator.

For an emulator sign-in completed in the Windows browser, forward the app's selected loopback
port with `adb forward tcp:PORT tcp:PORT` before opening its authorization URL. Remove that
forward after sign-in. The device browser on physical Android uses its own loopback directly.
Keep the emulator independent of a short-lived build process while authorization is pending.

References: [OpenAI registration and sign-in](https://developers.openai.com/siwc/token-sharing-open-source/sign-in),
[Android Keystore](https://developer.android.com/privacy-and-security/keystore).
