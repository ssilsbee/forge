# Local rules reference

The advisor can optionally call `search_rules(query)` and `read_rules(rule_number)` before returning its final decision. These tools read a downloaded, user-owned copy of the Magic Comprehensive Rules. The document is not included in Forge source, JARs, APKs, or test fixtures.

## Download and updates

On Android and desktop advisor startup, `RulesRepository` loads and validates any local cache, then checks for updates in the background. The cache is under the advisor data directory's `rules/rules-cache.json` on Android and desktop live play. The standalone CLI uses `rules` within its connection data directory.

The updater reads Wizards' official download page:
https://magic.wizards.com/en/rules

It discovers the dated Comprehensive Rules TXT link on `media.wizards.com`; there is no hardcoded current edition or fallback mirror. Wizards identifies editions using an effective date in the document and a date in the download filename. Both dates must agree. Document structure and index size are validated before use.

- A missing cache triggers a first download. Tools remain unavailable until it succeeds.
- With a valid cache, update checks are limited to once per day, including across application restarts. A failed first download is retried on the next launch.
- An unchanged edition needs only the download-page check, not another full document download.
- A new document and its metadata replace the old cache atomically, after validation. Interrupted or invalid downloads never replace a working copy.
- If an update fails, the last valid cached edition remains available. The model receives its actual edition date.
- If no valid local copy exists, the provider request includes no rules tool definitions or rules reference prompt. Gameplay continues using the normal advisor and engine validation.
- Downloads run separately from model decisions, use only official Wizards HTTPS hosts, and have size, time, and redirect limits. A decision keeps one reference edition throughout its tool loop, even if an update finishes in the background.

The raw document retains its original notices and credits in the local cache. Downloaded documents and cache filenames are ignored by Git. See Wizards' [Fan Content Policy](https://company.wizards.com/en/legal/fancontentpolicy), which does not grant general permission to copy and repost its materials verbatim. Downloading directly avoids shipping the rulebook; this is not a claim of a broader redistribution license.

## During a decision

- Tools are supported by the ChatGPT and OpenRouter paths when a valid cache is available and the chosen model supports function calling.
- The prompt encourages lookups for unfamiliar keywords or uncertain interactions, rather than routine decisions.
- At most two lookup rounds and four calls are allowed. The next inference disables tool calling and requests the final answer. All rounds share the original decision deadline.
- Search returns up to eight matching rules, headings, or glossary entries. Reading includes subrules and examples. Each result contains at most 10,000 characters of rule text and flags truncation.
- A bounded query cache avoids repeated searches. Up to three short excerpts retrieved with `read_rules` can appear as public reference context on subsequent requests. These excerpts are cleared when the reference edition changes.
- Status shows each lookup; the final Output report includes its arguments, edition, source, and returned excerpts. Successful deck plan reports include the same trace. Usage totals include all inference rounds. Provider reasoning items are replayed for continuation but are not written to these reports.

Desktop can explicitly disable lookup tools with `-Dforge.llm.rules.disabled=true`.

## Verification

`RulesReferenceTest`, `RulesRepositoryTest`, `RulesToolsTest`, and `ProviderClientTest` use invented reference text and local HTTP fixtures. They cover cache persistence, unchanged/new editions, offline startup, corrupt cache, malformed downloads, failed updates, missing-reference tool suppression on both providers, deadlines, call limits, and usage aggregation.

`LiveRulesToolsTest` opts into downloading the real rules from Wizards and exercising a ChatGPT subscription lookup with `-Dforge.llm.liveRulesEvaluation=true` and `-Dforge.llm.dataDir=...` (the directory containing `connections`). No copyrighted rulebook fixture is checked into the repository.

Lookups do not guarantee that a model recognizes its uncertainty or applies every rule correctly. Card-specific rulings are not included.
