"""Draft actual hosted transport for one retained-image public UI case; no workflow."""
import argparse
import os
from pathlib import Path
import signal
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts' / 'browser-benchmark'))
from public_image_ui import IMAGE, SOURCE, input_root, write, download, inventory, cleanup_all
from owned_app import OwnedApp, hosted_only
from ownership_index import create, job_identity
from fixture_case import verify_sources
from throughput_case import metrics
from owned_public_policy import OwnedPublicPolicy, EXPECTED_SOURCE, EXPECTED_IMAGE
from hosted_browser_case1 import HostedBrowser, validate_case1_receipt

SCOPE = 'Retained21cce original UI anonymous public browse to login checkpoint only; not complete154, OIDC, setup, token flows, performance or cutover acceptance'


def execute(browser_image, output):
    hosted_only()
    if SOURCE != EXPECTED_SOURCE or IMAGE != EXPECTED_IMAGE:
        raise ValueError('Reviewed retained public artifact changed')
    root = input_root()
    root.mkdir(mode=0o700)
    info = root.stat()
    write(root / 'OWNER.json', {'job': job_identity(), 'device': info.st_dev, 'inode': info.st_ino})
    create()
    report = {'scope': SCOPE, 'applicationSource': SOURCE, 'applicationImage': IMAGE,
              'harnessSource': os.environ.get('GITHUB_SHA'), 'complete': False,
              'fullIssue154AcceptanceProven': False, 'performanceProven': False,
              'cleanupConfirmed': False, 'effectivePublicPolicyRestored': False}
    stage = 'artifact'
    def deadline(*_):
        raise TimeoutError('Controller deadline')
    signal.signal(signal.SIGTERM, deadline)
    signal.signal(signal.SIGALRM, deadline)
    signal.alarm(570)  # Same outer public-image controller bound.
    try:
        report['artifact'] = download(root)
        stage = 'image-inventory'
        evidence, direct, plugins = inventory(root)
        report['imageInventory'] = evidence
        stage = 'application'
        with OwnedApp(IMAGE, direct, None, expected_jar_sha=evidence['jarSha256'],
                      ui_only=True, h2_jar=evidence['h2Jar'], original_plugins=plugins,
                      watchdog_seconds=480, diagnostic_output=output.with_name('application.json')) as app:
            before = metrics(app.client)
            if any(before[k] for k in ('active', 'draining', 'completed', 'failed')):
                raise ValueError('UI-only case must not scan')
            policy = OwnedPublicPolicy(app)
            try:
                stage = 'owned-public-policy'
                with policy:
                    stage = 'fresh-anonymous-browser'
                    cover_id = int(app.cover_path.rsplit('/', 1)[1])
                    with HostedBrowser(app.owner, browser_image, app.network, app.ip, cover_id,
                                       app.fixture['image']['sha256'], app_id=app.id,
                                       app_image=IMAGE, fixture_root=app.root, public_case1=True,
                                       diagnostic_output=output.with_name('browser.json')) as browser:
                        report['case1'] = validate_case1_receipt(browser.public_auth_case1())
                    report['browserCleanupConfirmed'] = True
            finally:
                # Context-manager restoration runs even if browser creation/read/cleanup fails.
                # Never replace a failed readback with an inferred restoration claim.
                report['effectivePublicPolicyRestored'] = policy.effective_policy_restored is True
            stage = 'final-gates'
            if report['effectivePublicPolicyRestored'] is not True:
                raise ValueError('Public policy restoration unproved')
            verify_sources(app.root / 'fixture', app.fixture['manifest'])
            after = metrics(app.client)
            if any(after[k] != before[k] for k in ('active', 'draining', 'completed', 'failed')):
                raise ValueError('UI-only scan counters changed')
            report['sourceIntegrityUnchanged'] = True
            report['noScansStarted'] = True
            report['health'] = app.final_gate()
        report['applicationCleanupConfirmed'] = True
    except BaseException as error:
        # Only fixed stage/category, never exception text, endpoint bodies or credentials.
        report['failure'] = {'stage': stage, 'category': type(error).__name__ if type(error).__name__ in
                             ('ValueError', 'RuntimeError', 'TimeoutError', 'OSError') else 'other'}
        raise
    finally:
        try:
            cleanup_all()
            report['cleanupConfirmed'] = True
        finally:
            signal.alarm(0)
            report['complete'] = 'failure' not in report and all(report.get(k) is True for k in
                ('browserCleanupConfirmed', 'applicationCleanupConfirmed', 'cleanupConfirmed',
                 'effectivePublicPolicyRestored', 'sourceIntegrityUnchanged', 'noScansStarted')) and 'case1' in report
            output.parent.mkdir(parents=True, exist_ok=True)
            write(output, report)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--browser-image')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--parent-reviewed-case1', action='store_true')
    parser.add_argument('--cleanup-only', action='store_true')
    args = parser.parse_args()
    hosted_only()
    if args.cleanup_only:
        if args.browser_image or args.output or args.parent_reviewed_case1:
            parser.error('Cleanup-only has no runtime inputs')
        cleanup_all()
    else:
        if not args.parent_reviewed_case1 or not args.browser_image or not args.output:
            parser.error('Explicit parent review, immutable browser image and output required')
        execute(args.browser_image, args.output)


if __name__ == '__main__':
    try:
        main()
    except BaseException:
        raise SystemExit('Draft public case1 failed; only scoped sanitized receipt/owned cleanup') from None
