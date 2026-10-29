import {test} from 'node:test';
import assert from 'node:assert/strict';
import {avatarSource, avatarRefresher} from '../../main/frontend/util/avatarMetadata.ts';

test('own and named users never borrow another identity presence/version; names are encoded', () => {
    const viewer = {username: 'viewer', hasAvatar: false, avatarVersion: 4};
    const src = avatarSource(viewer, {username: 'other &+#?', hasAvatar: true, avatarVersion: 9});
    const url = new URL(src!, 'http://fixture.invalid');
    assert.equal(url.searchParams.get('username'), 'other &+#?');
    assert.equal(url.searchParams.get('v'), '9');
    assert.equal(avatarSource({username: 'viewer', hasAvatar: true}, {username: 'other'}), undefined);
    assert.equal(avatarSource({username: 'viewer', hasAvatar: true}, {hasAvatar: false}), undefined);
    assert.equal(avatarSource(undefined, {}), undefined);
    assert.equal(avatarSource({username: 'viewer', hasAvatar: true, avatarVersion: 5}, {}), '/images/avatar?username=viewer&v=5');
    assert.equal(avatarSource({username: 'viewer', hasAvatar: true, avatarId: 19}, {}), '/images/avatar?username=viewer&image=19');
});

test('invalidation bursts coalesce, abort ignores late metadata and failures never auto-retry', async () => {
    const controller = new AbortController();
    let resolve!: (value: number) => void;
    let loads = 0;
    const values: number[] = [];
    const refresh = avatarRefresher(controller.signal, () => {
        loads++;
        return loads === 1 ? new Promise<number>(done => { resolve = done; }) : Promise.resolve(2);
    }, value => values.push(value));
    const pending = refresh();
    for (let i = 0; i < 30; i++) void refresh();
    assert.equal(loads, 1);
    resolve(1); await pending;
    assert.equal(loads, 2); assert.deepEqual(values, [1, 2]);
    const later = new AbortController();
    const staleValues: number[] = [];
    const stale = avatarRefresher(later.signal, () => new Promise<number>(done => { resolve = done; }), value => staleValues.push(value));
    const outstanding = stale(); later.abort(); resolve(3); await outstanding;
    assert.deepEqual(staleValues, []);
    let failures = 0;
    const failing = avatarRefresher(controller.signal, async () => { failures++; throw new Error('synthetic'); }, () => assert.fail());
    await failing(); assert.equal(failures, 1); await failing(); assert.equal(failures, 2);
    controller.abort(); await failing(); assert.equal(failures, 2);
});
