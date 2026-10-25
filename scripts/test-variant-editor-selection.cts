// Actual modal/effect characterization with controlled React hooks; no DOM or RPC.
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
const {createRequire} = require('node:module');
const ts = process.env.TEST_TYPESCRIPT_MODULE ? require(process.env.TEST_TYPESCRIPT_MODULE) : createRequire(path.resolve(__dirname,'../app/package.json'))('typescript');
type Variant = {id: number; name: string; version: string; default: boolean; defaultLocked: boolean; contents: unknown[]};
type Editor = {key: string; variant: Variant; onDirtyChange: (value: boolean) => void};
type EffectSlot = {deps: unknown[]};
function variant(id: number, pinned = false): Variant {return {id,name:'Normal',version:String(id),default:pinned,defaultLocked:pinned,contents:[]};}
function fixture(initialVariantId?: number) {
    const slots: unknown[] = []; let cursor = 0, effects: (() => void)[] = [], editor: Editor | undefined;
    const react = {
        useState<T>(initial: T) {const i=cursor++; if (!(i in slots)) slots[i]=initial; return [slots[i], (value: T | ((old: T) => T)) => {slots[i]=typeof value==='function' ? (value as (old: T) => T)(slots[i] as T) : value;}];},
        useRef<T>(initial: T) {const i=cursor++; if (!(i in slots)) slots[i]={current:initial}; return slots[i];},
        useMemo<T>(callback: () => T) {return callback();},
        useEffect(callback: () => void, deps: unknown[]) {const i=cursor++, previous=slots[i] as EffectSlot | undefined;
            if (!previous || deps.some((value,index) => value!==previous.deps[index])) effects.push(() => {callback();slots[i]={deps};});}
    };
    function jsx(type: string, props: any, key?: string) {
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
        if (name==='@heroui/react') return new Proxy({useDisclosure:() => ({})} as Record<string,unknown>,{get:(value,key) => value[String(key)] ?? key});
        if (name.endsWith('/VariantContentType')) return {default:Object.fromEntries(['BASE','DLC','DEDICATED_SERVER','PATCH','MOD','EXTRA'].map(value => [value,value]))};
        if (name.endsWith('/VariantDetailsEditor')) return {default:'VariantDetailsEditor'};
        return {default:name};
    }});
    let game={id:1,title:'Synthetic',metadata:{path:'/synthetic'},variants:[variant(1,true),variant(2)]};
    function render() {cursor=0;effects=[];editor=undefined;module.exports.default({game,initialVariantId,libraryGames:[],suggestions:[],isOpen:true,onOpenChange:() => {}});for (const effect of effects) effect();return editor!;}
    return {render, setGame(value: typeof game) {game=value;}, get game() {return game;}};
}
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
