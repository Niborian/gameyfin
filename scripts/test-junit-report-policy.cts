// Read-only fork JUnit policy; native Node24 erasable TypeScript, no extra runtime.
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const workflow: string = path.resolve(__dirname, '../.github/workflows/package.yml');
function workflowText(): string {return fs.readFileSync(workflow, 'utf8').replaceAll('\r\n', '\n');}
function after(text: string, delimiter: string): string {
    const index=text.indexOf(delimiter);
    assert.notEqual(index,-1,`Required policy section missing: ${delimiter.trim()}`);
    return text.slice(index+delimiter.length);
}
function before(text: string, delimiter: string): string {return text.split(delimiter,1)[0];}

test('JUnit report uses supported annotation-only inputs',()=>{
    const text=workflowText();
    // Scope inputs to this action step, not subsequently added artifact steps.
    const report=before(before(after(text,'      - name: Publish test report\n'),'\n      - '),'\n  package:');
    assert.ok(report.includes('        if: always()'));
    assert.ok(report.includes('        uses: mikepenz/action-junit-report@v6'));
    const inputs: Record<string,string>={};
    for(const match of report.matchAll(/^          ([a-z_]+): (.+)$/gm))inputs[match[1]]=match[2];
    assert.deepEqual(inputs,{report_paths:'"**/build/test-results/test/TEST-*.xml"',annotate_only:'true',job_summary:'true'});
});

test('verify keeps read-only token and Gradle failure gate',()=>{
    const text=workflowText(),verify=before(after(text,'  verify:\n'),'\n  package:');
    const permissions=before(after(verify,'    permissions:\n'),'    outputs:\n');
    assert.equal(permissions.trim(),'contents: read');
    const tests=before(after(verify,'      - name: Run automated tests\n'),'\n      - ');
    assert.ok(tests.includes('run: ./gradlew :app:test --no-daemon --console=plain'));
    assert.ok(!tests.includes('continue-on-error'));
    assert.ok(!text.includes('pull_request_target:'));
});
