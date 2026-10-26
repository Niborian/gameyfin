// Actual modal/effect characterization with controlled React hooks; no DOM or RPC.
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
const {createRequire} = require('node:module');
const ts = process.env.TEST_TYPESCRIPT_MODULE ? require(process.env.TEST_TYPESCRIPT_MODULE) : createRequire(path.resolve(__dirname,'../app/package.json'))('typescript');

function gameDownloadFixture(delayedProviders = false, providerRequest?: (init: {signal: AbortSignal}) => Promise<any[]>) {
    const slots: any[] = []; let cursor = 0, effects: (() => void)[] = [];
    let nodes: {type: string; props: any}[] = [], providerCalls = 0;
    const downloads: any[][] = [];
    let actor = {id:7,username:'fixture'};
    let resolveProviders!: (value: any[]) => void;
    const providers = [{key: 'direct', name: 'Direct', description: 'Fixture'}];
    const providerPromise = delayedProviders ? new Promise<any[]>(resolve => {resolveProviders = resolve;}) : Promise.resolve(providers);
    const state: any = {isLoaded:true, state:{1:{id:1,title:'Synthetic',metadata:{fileSize:1},collectionIds:[],variants:[]}}};
    const react = {
        lazy: () => 'VariantManagerModal',
        useState(initial: any) {const i = cursor++; if (!(i in slots)) slots[i] = initial;
            return [slots[i], (value: any) => {slots[i] = typeof value === 'function' ? value(slots[i]) : value;}];},
        useRef(initial: any) {const i = cursor++; if (!(i in slots)) slots[i] = {current:initial}; return slots[i];},
        useLayoutEffect(callback: () => any, deps: any[]) {return react.useEffect(callback, deps);},
        useEffect(callback: () => any, deps: any[]) {const i = cursor++, previous = slots[i];
            if (!previous || deps.some((value,index) => !Object.is(value,previous.deps[index]))) effects.push(() => {
                previous?.cleanup?.(); slots[i] = {deps, cleanup:callback()};
            });}
    };
    function jsx(type: string, props: any) {nodes.push({type,props}); return {type,props};}
    const module = {exports:{} as {default: () => unknown}};
    const source = fs.readFileSync(path.resolve(__dirname,'../app/src/main/frontend/views/GameView.tsx'),'utf8');
    const code = ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022,jsx:ts.JsxEmit.ReactJSX}}).outputText;
    vm.runInNewContext(code,{module,exports:module.exports,AbortController,AbortSignal,document:{title:''},require(name: string) {
        if (name === 'react') return {...react,default:react};
        if (name === 'react/jsx-runtime') return {jsx,jsxs:jsx};
        if (name === 'react-router') return {useParams:() => ({gameId:'1'}),useNavigate:() => () => {}};
        if (name === 'valtio/react') return {useSnapshot:(value: any) => value};
        if (name.endsWith('/GameState')) return {gameState:state};
        if (name.endsWith('/CollectionState')) return {collectionState:{state:{}}};
        if (name.endsWith('/auth')) return {useAuth:() => ({state:{user:actor,loading:false,initializing:false}})};
        if (name.endsWith('/utils')) return {isAdmin:() => false,humanFileSize:String,starRatingAsString:() => ''};
        if (name === 'Frontend/generated/endpoints') return {DownloadProviderEndpoint:{getProviders:(init: {signal: AbortSignal}) => {providerCalls++;return providerRequest ? providerRequest(init) : providerPromise;}},OwnedTorrentEndpoint:{available:async () => false},GameEndpoint:{}};
        if (name === 'Frontend/endpoints/endpoints') return {DownloadEndpoint:{downloadGame:(...args: any[]) => downloads.push(args)}};
        if (name === '@heroui/react') return new Proxy({useDisclosure:() => ({isOpen:false})} as any,{get:(value,key) => value[key] ?? key});
        if (name.endsWith('/VariantContentType')) return {default:Object.fromEntries(['BASE','DLC','PATCH','MOD','EXTRA','DEDICATED_SERVER'].map(value => [value,value]))};
        if (name.endsWith('/ComboButton')) return {default:'ComboButton'};
        return {default:name};
    }});
    return {
        render() {cursor=0;effects=[];nodes=[];module.exports.default();effects.forEach(effect => effect());},
        setVariants(variants: any[]) {state.state[1] = {...state.state[1],variants};},
        download() {nodes.find(node => node.type === 'ComboButton')!.props.options.direct.action();},
        selectVariant(id: number) {nodes.find(node => node.type === 'Select' && node.props.label === 'Version')!.props.onSelectionChange(new Set([String(id)]));},
        toggleOptional(selected: boolean) {nodes.find(node => node.type === 'Checkbox' && !node.props.isDisabled)!.props.onValueChange(selected);},
        resolveProviders() {resolveProviders(providers);},
        setActor(value: typeof actor) {actor=value;},
        unmount() {slots.forEach(slot => slot?.cleanup?.());},
        get downloads() {return downloads;}, get providerCalls() {return providerCalls;}, get nodes() {return nodes;}
    };
}

test('provider failure shows honest error and explicit retry restores current exact download without automatic retries', async () => {
    let calls=0;
    const f=gameDownloadFixture(false, async () => {if (++calls===1) throw new TypeError('Synthetic fetch failure'); return [{key:'direct',name:'Direct'}];});
    f.setVariants([downloadVariant(1,[baseContent(101,'Base')])]);
    f.render(); await new Promise(resolve => setImmediate(resolve)); f.render();
    assert.equal(f.nodes.some(node => node.props.role==='alert'),true);
    assert.equal(f.nodes.some(node => node.type==='ComboButton'),false);
    f.render(); assert.equal(f.providerCalls,1);
    f.nodes.find(node => node.type==='Button' && node.props.children==='Retry download methods')!.props.onPress();
    f.render(); await new Promise(resolve => setImmediate(resolve)); f.render();
    assert.equal(f.nodes.some(node => node.props.role==='alert'),false);
    f.download(); assert.deepEqual(Array.from(f.downloads.at(-1)![3]),[101]);
    f.setVariants([downloadVariant(1,[baseContent(201,'Replacement')])]); f.render(); f.download();
    assert.deepEqual(Array.from(f.downloads.at(-1)![3]),[201]); assert.equal(f.providerCalls,2);
});

test('provider generation aborts on identity change and unmount; late old results and old actions cannot attach', async () => {
    const pending: {signal: AbortSignal; resolve: (value: any[]) => void}[]=[];
    const f=gameDownloadFixture(false, init => new Promise(resolve => pending.push({signal:init.signal,resolve})));
    f.setVariants([downloadVariant(1,[baseContent(101,'Base')])]); f.render();
    f.setActor({id:8,username:'replacement'}); f.render();
    assert.equal(pending[0].signal.aborted,true);
    pending[0].resolve([{key:'old',name:'Stale'}]); await new Promise(resolve => setImmediate(resolve)); f.render();
    assert.equal(f.nodes.some(node => node.type==='ComboButton'),false);
    pending[1].resolve([{key:'direct',name:'Direct'}]); await new Promise(resolve => setImmediate(resolve)); f.render();
    const action=f.nodes.find(node => node.type==='ComboButton')!.props.options.direct.action;
    f.unmount(); assert.equal(pending[1].signal.aborted,true); action(); assert.equal(f.downloads.length,0);
});

function downloadVariant(id: number, contents: any[]) {
    return {...variant(id,id===1),tags:[],contents};
}

test('game-page action immediately uses replacement canonical base IDs without reopening', async () => {
    const f=gameDownloadFixture(); f.setVariants([downloadVariant(1,[baseContent(101,'Broad base')])]);
    f.render(); await new Promise(resolve => setImmediate(resolve)); f.render();
    f.download(); assert.deepEqual(Array.from(f.downloads.at(-1)![3]),[101]);
    f.setVariants([downloadVariant(1,[baseContent(201,'Base child')])]);
    f.render(); f.download();
    assert.deepEqual(Array.from(f.downloads.at(-1)![3]),[201]);
    assert.equal(f.downloads.at(-1)![2],1);
});

test('replacement selects new default extras and prunes removed IDs without restoring a deselected existing extra', async () => {
    const f = gameDownloadFixture();
    const oldPatch = {...baseContent(102, 'Existing patch'), type:'PATCH', required:false, defaultSelected:true};
    f.setVariants([downloadVariant(1,[baseContent(101,'Broad base'),oldPatch])]);
    f.render(); await new Promise(resolve => setImmediate(resolve)); f.render();
    f.toggleOptional(false); f.render();
    const newPatch = {...baseContent(203,'New patch'), type:'PATCH', required:false, defaultSelected:true};
    const newOptional = {...baseContent(204,'Unselected extra'), type:'EXTRA', required:false, defaultSelected:false};
    f.setVariants([downloadVariant(1,[baseContent(201,'New base'),oldPatch,newPatch,newOptional])]);
    f.render(); f.download();
    assert.deepEqual(Array.from(f.downloads.at(-1)![3]),[201,203]);
    f.render(); f.download();
    assert.deepEqual(Array.from(f.downloads.at(-1)![3]),[201,203]);
});

test('unrelated variant addition and default change preserve selected version and explicit optional choices', async () => {
    const f = gameDownloadFixture();
    const extra = {...baseContent(202,'Chosen extra'), type:'EXTRA', required:false, defaultSelected:false};
    const selected = downloadVariant(2,[baseContent(201,'Base'),extra]);
    f.setVariants([downloadVariant(1,[baseContent(101,'Other')]),selected]);
    f.render(); await new Promise(resolve => setImmediate(resolve)); f.render();
    f.selectVariant(2); f.render(); f.toggleOptional(true); f.render();
    f.setVariants([{...downloadVariant(1,[baseContent(101,'Other')]),default:false,defaultLocked:false},selected,
        {...downloadVariant(3,[baseContent(301,'Added')]),default:true,defaultLocked:true}]);
    f.render(); f.render(); f.download();
    assert.equal(f.downloads.at(-1)![2],2);
    assert.deepEqual(Array.from(f.downloads.at(-1)![3]),[201,202]);
});

test('switching variants initializes its defaults rather than carrying another variant selection', async () => {
    const f = gameDownloadFixture();
    f.setVariants([downloadVariant(1,[baseContent(101,'First')]),downloadVariant(2,[baseContent(201,'Second'),
        {...baseContent(202,'Default patch'),type:'PATCH',required:false,defaultSelected:true}])]);
    f.render(); await new Promise(resolve => setImmediate(resolve)); f.render();
    f.selectVariant(2); f.render(); f.download();
    assert.deepEqual(Array.from(f.downloads.at(-1)![3]),[201,202]);
    f.selectVariant(1); f.render(); f.download();
    assert.deepEqual(Array.from(f.downloads.at(-1)![3]),[101]);
});

test('late provider lookup uses current variant and explicit optional selection', async () => {
    const f=gameDownloadFixture(true);
    const optional={...baseContent(202,'Optional patch'),type:'PATCH',required:false,defaultSelected:false};
    const first=downloadVariant(1,[baseContent(101,'First')]);
    const second=downloadVariant(2,[baseContent(201,'Second'),optional]);
    f.setVariants([first,second]); f.render(); f.render();
    f.selectVariant(2); f.render(); f.toggleOptional(true); f.render();
    f.resolveProviders(); await new Promise(resolve => setImmediate(resolve)); f.render(); f.download();
    assert.equal(f.downloads.at(-1)![2],2);
    assert.deepEqual(Array.from(f.downloads.at(-1)![3]),[201,202]);
    f.toggleOptional(false); f.render(); f.download();
    assert.deepEqual(Array.from(f.downloads.at(-1)![3]),[201]);
    assert.equal(f.providerCalls,1);
    f.unmount();
});
type Variant = {id: number; name: string; version: string; default: boolean; defaultLocked: boolean; contents: unknown[]};
type Editor = {key: string; variant: Variant; onDirtyChange: (value: boolean) => void};
type EffectSlot = {deps: unknown[]};
function variant(id: number, pinned = false): Variant {return {id,name:'Normal',version:String(id),default:pinned,defaultLocked:pinned,contents:[]};}
function fixture(initialVariantId?: number, listContents?: (path: string) => Promise<unknown[]>, gameApi: Record<string, unknown> = {}) {
    let closeCalls = 0;
    let focusedControl = '';
    const toasts: any[] = [];
    const canonical: {state: Record<number, unknown>} = {state:{}};
    const slots: unknown[] = []; let cursor = 0, effects: (() => void)[] = [], editor: Editor | undefined;
    let nodes: {type: string; props: any}[] = [];
    const react = {
        useLayoutEffect(callback: () => void, deps: unknown[]) {return react.useEffect(callback,deps);},
        useState<T>(initial: T) {const i=cursor++; if (!(i in slots)) slots[i]=initial; return [slots[i], (value: T | ((old: T) => T)) => {slots[i]=typeof value==='function' ? (value as (old: T) => T)(slots[i] as T) : value;}];},
        useRef<T>(initial: T) {const i=cursor++; if (!(i in slots)) slots[i]={current:initial}; return slots[i];},
        useMemo<T>(callback: () => T) {return callback();},
        useEffect(callback: () => void, deps: unknown[]) {const i=cursor++, previous=slots[i] as EffectSlot | undefined;
            if (!previous || deps.some((value,index) => value!==previous.deps[index])) effects.push(() => {callback();slots[i]={deps};});}
    };
    function jsx(type: string, props: any, key?: string) {
        nodes.push({type, props});
        if (type === 'Button' && props.ref) props.ref.current = {focus() {focusedControl=props.children;}};
        if (type==='VariantDetailsEditor') editor={key:key!,variant:props.variant,onDirtyChange:props.onDirtyChange};
        if (type==='ModalContent') return props.children(() => {});
        return {type,props,key};
    }
    const module = {exports:{} as {default: (props: any) => unknown}};
    const source=fs.readFileSync(path.resolve(__dirname,'../app/src/main/frontend/components/general/modals/VariantManagerModal.tsx'),'utf8');
    const code=ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022,jsx:ts.JsxEmit.ReactJSX}}).outputText;
    vm.runInNewContext(code,{module,exports:module.exports,window:{confirm:() => {throw Error('Harmless refresh must not discard or prompt');}},require(name: string) {
        if (name==='react') return {...react,default:react};
        if (name==='react/jsx-runtime') return {jsx,jsxs:jsx};
        if (name==='Frontend/generated/endpoints') return {FilesystemEndpoint: {listContents}, GameEndpoint: gameApi};
        if (name==='Frontend/state/GameState') return {gameState:canonical};
        if (name==='Frontend/util/utils') return {humanFileSize: (value: number) => String(value)};
        if (name==='@heroui/react') return new Proxy({useDisclosure:() => ({}),addToast:(value: any) => toasts.push(value)} as Record<string,unknown>,{get:(value,key) => value[String(key)] ?? key});
        if (name.endsWith('/VariantContentType')) return {default:Object.fromEntries(['BASE','DLC','DEDICATED_SERVER','PATCH','MOD','EXTRA'].map(value => [value,value]))};
        if (name.endsWith('/VariantDetailsEditor')) return {default:'VariantDetailsEditor'};
        return {default:name};
    }});
    let game={id:1,title:'Synthetic',metadata:{path:'/synthetic'},variants:[variant(1,true),variant(2)]};
    function render() {cursor=0;effects=[];editor=undefined;nodes=[];module.exports.default({game,initialVariantId,libraryGames:[],suggestions:[],isOpen:true,onOpenChange:() => {closeCalls++;}});for (const effect of effects) effect();return editor!;}
    return {render, setGame(value: typeof game) {game=value;}, get game() {return game;}, get nodes() {return nodes;}, get closeCalls() {return closeCalls;}, get focusedControl() {return focusedControl;}, toasts, canonical};
}

test('successful content attachment and default update use inline status, never a floating success toast', async () => {
    let calls=0, fail=false;
    const updated={id:1,title:'Saved',metadata:{path:'/synthetic'},variants:[variant(1,true),variant(2)]};
    const f=fixture(undefined,undefined,{attachVariantContent:async () => {calls++;if (fail) throw new Error('Synthetic rejected save');return updated;},setDefaultVariant:async () => updated});
    f.render();f.render();
    f.nodes.find(node => node.type==='Input' && node.props.label==='Source root path')!.props.onValueChange('/synthetic/new.bin');f.render();
    await f.nodes.find(node => node.type==='Button' && node.props.children==='Save content')!.props.onPress();f.render();
    assert.equal(calls,1);assert.equal(f.canonical.state[1],updated);
    assert.equal(f.nodes.some(node => node.props.role==='status'),true);
    assert.equal(f.nodes.some(node => node.type==='p' && node.props.children==='Variant content saved'),true);
    assert.equal(f.toasts.length,0);
    f.nodes.find(node => node.type==='Input' && node.props.label==='Source root path')!.props.onValueChange('/synthetic/other.bin');f.render();
    assert.equal(f.nodes.some(node => node.props.role==='status'),false);
    const defaultButton=f.nodes.find(node => node.type==='Button' && node.props.children==='Set default');
    assert.ok(defaultButton);await defaultButton!.props.onPress();f.render();
    assert.equal(f.nodes.some(node => node.type==='p' && node.props.children==='Default version updated'),true);
    assert.equal(f.toasts.length,0);
    fail=true;
    await f.nodes.find(node => node.type==='Button' && node.props.children==='Save content')!.props.onPress();f.render();
    assert.equal(f.nodes.some(node => node.props.role==='status'),false);
    assert.equal(f.toasts.length,1);assert.equal(f.toasts[0].color,'danger');
});

test('dirty editor close is controlled: keep editing preserves owner, duplicate requests do not discard, explicit discard closes once', () => {
    const f = fixture(); f.render(); const editor = f.render(); editor.onDirtyChange(true); f.render();
    const close = () => f.nodes.find(node => node.type === 'Button' && node.props.children === 'Close')!.props.onPress();
    close(); close(); f.render();
    assert.equal(f.closeCalls,0);
    assert.equal(f.nodes.filter(node => node.props.role === 'alertdialog').length,1);
    assert.equal(f.focusedControl,'Keep editing');
    f.nodes.find(node => node.type === 'Button' && node.props.children === 'Keep editing')!.props.onPress();
    const retained = f.render();
    assert.equal(retained.key,editor.key); assert.equal(f.closeCalls,0);
    assert.equal(f.nodes.some(node => node.props.role === 'alertdialog'),false);
    assert.equal(f.focusedControl,'Close');
    close(); f.render();
    let prevented = false, stopped = false;
    f.nodes.find(node => node.props.role === 'alertdialog')!.props.onKeyDown({key:'Escape',
        preventDefault:() => {prevented=true;}, stopPropagation:() => {stopped=true;}});
    f.render();
    assert.equal(prevented,true); assert.equal(stopped,true); assert.equal(f.closeCalls,0);
    assert.equal(f.focusedControl,'Close');
    assert.equal(f.nodes.some(node => node.props.role === 'alertdialog'),false);
    // Dirty remains set: the next close still requires an explicit decision.
    f.nodes.find(node => node.type === 'Modal')!.props.onOpenChange(false); f.render();
    const discard = f.nodes.find(node => node.type === 'Button' && node.props.children === 'Discard and close')!.props.onPress;
    discard(); discard(); close();
    assert.equal(f.closeCalls,1);
});

test('clean editor closes once normally and ignores an open notification', () => {
    const f = fixture(); f.render(); f.render();
    const modal = f.nodes.find(node => node.type === 'Modal')!;
    modal.props.onOpenChange(true); assert.equal(f.closeCalls,0);
    f.nodes.find(node => node.type === 'Button' && node.props.children === 'Close')!.props.onPress();
    modal.props.onOpenChange(false); assert.equal(f.closeCalls,1);
    f.render(); assert.equal(f.nodes.some(node => node.props.role === 'alertdialog'),false);
});

function baseContent(id: number, name: string) {return {id, name, path: `/fixture/${id}`, paths: [], memberNames:[], type:'BASE', required:true, defaultSelected:true, tags:[], pathCount:1, fileSize:1};}

test('same-ID canonical refresh updates clean cards and preserves dirty unrelated cards', () => {
    const f = fixture();
    f.setGame({...f.game, variants:[{...variant(1,true), contents:[baseContent(101,'Base game'),baseContent(102,'Other base')]}]});
    f.render(); f.render();
    f.nodes.filter(n => n.type === 'Input' && n.props.label === 'Content name')[1].props.onValueChange('Unsaved local name');
    f.render();
    f.setGame({...f.game, variants:[{...variant(1,true), contents:[baseContent(101,'Saved canonical'),baseContent(102,'Changed on server')]}]});
    f.render(); f.render();
    const values = f.nodes.filter(n => n.type === 'Input' && n.props.label === 'Content name').map(n => n.props.value);
    assert.deepEqual(values, ['Saved canonical','Unsaved local name']);
});

for (const change of ['root', 'target']) test(`late split preview cannot overwrite changed ${change}`, async () => {
    let resolve!: (value: unknown[]) => void;
    const f = fixture(undefined, () => new Promise(done => {resolve = done;}));
    f.setGame({...f.game, variants:[{...variant(1,true), contents:[baseContent(101,'Base game')]},variant(2)]});
    f.render(); f.render();
    const pending = f.nodes.find(n => n.type === 'Button' && n.props.children === 'Split into base / extras')!.props.onPress();
    f.render();
    if (change === 'root') f.nodes.find(n => n.type === 'Input' && n.props.label === 'Source root path')!.props.onValueChange('/new-source');
    else f.nodes.find(n => n.type === 'Select' && n.props.label === 'Attach to variant')!.props.onSelectionChange(new Set(['2']));
    f.render(); resolve([{name:'stale.zip',path:'/fixture/101/stale.zip'}]); await pending; f.render();
    assert.equal(f.nodes.find(n => n.type === 'Input' && n.props.label === 'Source root path')!.props.value, change === 'root' ? '/new-source' : '');
    if (change === 'target') assert.deepEqual(Array.from(f.nodes.find(n => n.type === 'Select' && n.props.label === 'Attach to variant')!.props.selectedKeys), ['2']);
});
test('dirty selected editor survives a different pinned default and added variant',() => {
    const f=fixture();f.render();const before=f.render();before.onDirtyChange(true);f.render();
    f.setGame({...f.game,variants:[variant(1),variant(2,true)]});
    f.render();const afterDefault=f.render();
    assert.equal(afterDefault.variant.id,1);assert.equal(afterDefault.key,before.key);
    f.setGame({...f.game,variants:[...f.game.variants,variant(3)]});
    f.render();const afterAddition=f.render();
    assert.equal(afterAddition.variant.id,1);assert.equal(afterAddition.key,before.key);
});
test('switching games initializes its own default instead of retaining a reused variant ID',() => {
    const f=fixture();f.render();f.render();
    f.setGame({...f.game,id:2,variants:[variant(1),variant(2,true)]});f.render();
    const selected=f.render();assert.equal(selected.variant.id,2);assert.equal(selected.key,'2:2');
});

test('game-page shortcut opens the selected version rather than a different pinned default', () => {
    const f = fixture(2); f.render();
    assert.equal(f.render().variant.id, 2);
});

test('split preview hints distinguish base archives from optional content without persisting anything', () => {
    const module = {exports: {} as {suggestContentType: (name: string) => string}};
    const source = fs.readFileSync(path.resolve(__dirname, '../app/src/main/frontend/util/contentClassification.ts'), 'utf8');
    const code = ts.transpileModule(source, {compilerOptions: {module: ts.ModuleKind.CommonJS}}).outputText;
    vm.runInNewContext(code, {module, exports: module.exports});
    const suggest = module.exports.suggestContentType;
    assert.equal(suggest('Example.v1.5.0.rar'), 'BASE');
    assert.equal(suggest('Multiplayer.zip'), 'PATCH');
    assert.equal(suggest('game.hotfix.7z'), 'PATCH');
    assert.equal(suggest('DLC.zip'), 'DLC');
    assert.equal(suggest('Dedicated Server'), 'DEDICATED_SERVER');
    assert.equal(suggest('readme.txt'), 'EXTRA');
    assert.equal(suggest('unrecognized directory'), 'EXTRA');
});

test('library editor receives canonical saved game without closing and reopening', () => {
    const original = {id: 1, title: 'Fixture', libraryId: 1, metadata: {path: '/fixture'}, variants: [variant(10)]};
    const state = {state: {1: original}, gamesByLibraryId: {1: [original]}};
    const slots: unknown[] = []; let cursor = 0, shown: typeof original | undefined;
    const react = {
        useState(initial: unknown) {const i = cursor++; if (!(i in slots)) slots[i] = initial;
            return [slots[i], (value: unknown) => {slots[i] = value;}];},
        useMemo(callback: () => unknown) {return callback();}, useEffect() {}
    };
    function jsx(type: string, props: any) {
        if (type === 'VariantManagerModal') shown = props.game;
        return {type, props};
    }
    const module = {exports: {} as {default: (props: unknown) => unknown}};
    const source = fs.readFileSync(path.resolve(__dirname, '../app/src/main/frontend/components/general/library/LibraryManagementGames.tsx'), 'utf8');
    const code = ts.transpileModule(source, {compilerOptions: {module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX}}).outputText;
    vm.runInNewContext(code, {module, exports: module.exports, require(name: string) {
        if (name === 'react') return {...react, default: react};
        if (name === 'react/jsx-runtime') return {jsx, jsxs: jsx};
        if (name === 'valtio/react') return {useSnapshot: () => state};
        if (name === '@heroui/react') return new Proxy({useDisclosure: () => ({isOpen: true})}, {get: (value: any, key) => value[key] ?? key});
        if (name.endsWith('/VariantManagerModal')) return {default: 'VariantManagerModal'};
        return {default: name};
    }});
    function render() {cursor = 0; shown = undefined; module.exports.default({library: {id: 1}}); return shown;}
    assert.equal(render(), original);
    const saved = {...original, variants: [variant(10), variant(20)]};
    state.state[1] = saved; state.gamesByLibraryId[1] = [saved];
    assert.equal(render(), saved);
    assert.equal(shown!.variants.length, 2);
});
