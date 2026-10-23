# One-app scrape sequencing draft — local only, not executable acceptance

Base ccd4c7ceaea553300f02fd7cf779d34c8d31ae86. No app rebuild, Docker operation,
download, server action, push or dispatch was performed.

retained_scan_image.py copies the reviewed678b14f public_image_ui loader,
artifact/run/ZIP/image/source pins, never-started inventory and scoped cleanup
chain, omitting its UI-only execution entry point. New inventory reads exactly
plugin-api, pf4j and kotlin-stdlib entries from the actual unfolded image
manifest Class-Path and exports bounded exact files for the existing tiny fixture
compiler. No dependency path is guessed; missing/ambiguous paths fail. These are
compile inputs only: no app or compiled UI resources are rewritten.

OwnedApp has a separate explicit retained_scan mode, validated against exact9ec
image/source, inventory JAR hash, H2 classpath path and complete original plugin
bundle hashes. Normal benchmark defaults are unchanged. The retained mode copies
the original bundle plus the separate metadata fixture, uses existing normal
132/924 seed, waits for actual readiness within existing180s, disables other
bundled providers and enables the fixture through normal THIRD_PARTY lifecycle.
Post-restart gate requires only direct+fixture provider STARTED. All original
owned mounts, caps, memory/CPU/JVM, source read-only flags, ledgers/watchdogs,
stopped-H2-only import, final gates and cleanup remain. No ui_only mode is added
or reinterpreted; that separate diagnostic branch's no-scan contract is untouched.

concurrent_scrape_probe.py is an injectable helper, NOT a new controller. It sends
exactly filtered+unfiltered reads after scan dispatch while immediately starting
the existing browser operation, using independent cookie-free no-redirect openers.
Each read has an absolute10s limit including open and bounded body read. CPython's
actual response socket is required; missing socket fails closed. Owned threads
are joined within11s and cannot produce success if still alive. No response body,
URL, cookies, raw exception text or credential is emitted. Timely active evidence
requires the whole request interval inside the browser interval; late/inactive/
missing remains unproven. This is not continuous overlap or a performance win.

Nine focused helper/adapter fixtures PASS0.018s, plus five actual controller
dependency-mocked failure fixtures PASS0.055s. They include exact manifest and
bundle checks, real fixture bootstrap gating, immediate browser scheduling,
late evidence rejection, partial thread-start and failed-dispatch lifetime,
prethrow probe proof, and cleanup status persistence. Seven changed/new Python
modules AST-parse successfully. These are not HTTP/Puppeteer/image/scan safety
or production resource proof. Initial lexical/import fixture errors were fixed
before this final check; no passing claim attaches to earlier failed attempts.

run_one_app_probe.py is now an explicit diagnostic controller: retained loader/inventory,
tiny fixture compilation with exact dependency/source/JAR/manifest hashes,
explicit scan adapter, actual normal browser authentication,2quiescent scrapes
and only2during scrapes, then scan completion/enrichment/image/download/source/
finalgate/cleanup. Original mounted plugin JAR hashes are checked before and
after the actual scan; a changed original or fixture JAR fails. Existing normal
limits and570s controller bound remain. New output/index/root preconditions
precede mutation; all owned root/marker/index creation is inside cleanup/receipt
finally. Uncertain cleanup remains unknown-retained, never complete.

Concurrent diagnostic callback persists fixed timing/status/category evidence
before browser/scan/join failures propagate; scan failure never initiates probes.
Partial thread-start failure releases/joins any started workers. Browser failure
does not discard already finished probe evidence or become acceptance.

Parent integration review added six transport-boundary fixtures and a temporary
NEVER MERGE workflow. Twenty new focused checks plus five existing final-gate
checks pass locally; these are not runtime acceptance. The20-minute job envelope
reserves setup/upload/cleanup margin above938 seconds of step caps. The570-second
controller,598-second outer command, browser/scan deadlines and resource caps
remain unchanged. Manual activation requires the exact reviewed commit.

No tiny fixture has actually been compiled and no retained artifact downloaded
or loaded by this draft. No application image is rebuilt, published or deployed;
no production or local Docker execution is supported. A full six-arm rerun remains
unwarranted while the measurement failure is unexplained.
