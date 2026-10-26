# Windows live-check continuation fix — 2026-10-26

Status: implemented and verified locally; not published.

## Corrected paths

- A stopped report with completed service measurements can enter manual checks. It remains incomplete; interrupted/error rows are not trial candidates.
- Scan reports retain the selected wizard services from creation. The current report is retained in memory, avoiding reuse of an older report after cancellation.
- Normal completion and user Stop both hand off to the configuration chooser. The manual stage has its own cancellation token; closing the detached wizard suppresses this handoff.
- Saved-report resume works after full and partial scans. Missing usable results and opening failures are displayed inside the wizard, without rebuilding its page.
- Missing strategies are reported instead of silently skipped. Discord voice checks no longer require browser-library installation.
- Compact Zapret has a fixed bottom “Результаты проверок” button outside profile scrolling, loading the saved report. The results page also offers “Очные проверки”.

## Evidence

- `wpf/tests/LiveWizard26Check.cs`: `LIVE_WIZARD_PASS 164`.
- Dark/light: actual Start and Resume events; normal and stopped scans; detached wizard ownership; disk-backed report resume; explicit manual entry from results; sequential real YouTube/Discord trial dialogs; skip does not record success; interrupted rows excluded; partial flags preserved; token/busy cleanup; closing during scan does not reopen dialogs; inline error feedback; empty/in-progress-only reports rejected; bottom results navigation.
- Synthetic scan/verification and offline browser/observer hooks prevent network tests, engine activation, and writes to the working profile. Only isolated test profiles under the build family were used. Trial screenshots are WPF offscreen renders, not desktop-compositor or live network evidence.
- Final test output and screenshots: `D:/MCRF/Builds/Windows/live-wizard-20261026/check02/results-final`.
- Ordinary full/modular build: `D:/MCRF/Builds/Windows/live-wizard-20261026/release02/mcrf.exe`.
- Product version: 1.2; length: 63096832 bytes.
- SHA-256: `d3428a4b802d601e8a25ce8094a753dba7e48056ee1df2f0d98d809ed7c83e59`.
- Build succeeded with existing warning categories CS4014/CS0414/CS0649, no compiler errors.
- No GitHub release, tag, or published asset was changed by this fix.

The earlier `SetupWizardRecheckPlanChecks` fixture now supplies report rows/service IDs to match its chosen configurations, rather than constructing an otherwise empty report.
