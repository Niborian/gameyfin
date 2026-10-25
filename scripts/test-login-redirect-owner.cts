// Focused actual LoginView characterization; no DOM/browser or transport claim.
type LoginResult = {error?: boolean; redirectUrl?: string; defaultUrl?: string};
type AuthUser = Record<string, unknown> | null;
type FormikHelpers = {setFieldError: (...values: string[]) => void};
type Effect = () => void | (() => void);
type EffectSlot = {deps: unknown[]; cleanup?: void | (() => void); fn: Effect};
type ElementNode = {type: unknown; props: {children?: unknown; onSubmit?: (values: {username: string; password: string}, helpers: FormikHelpers) => Promise<void>}};
type AuthFixture = {state: {user: AuthUser; initializing: boolean; loading: boolean}; login: () => Promise<LoginResult>};
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const {createRequire}=require('node:module');
const ts=process.env.TEST_TYPESCRIPT_MODULE?require(process.env.TEST_TYPESCRIPT_MODULE):createRequire(path.resolve(__dirname,'../app/package.json'))('typescript');
function deferred(){let resolve!: (value: LoginResult) => void;const promise=new Promise<LoginResult>(r=>resolve=r);return {promise,resolve};}
async function fixture(initialUser: AuthUser = null){
    let slots: unknown[]=[],cursor=0,effects: (() => void)[]=[],tree: ElementNode | null,loginCalls=0;
    const redirects: string[]=[],auth: AuthFixture={state:{user:initialUser,initializing:false,loading:false},login:()=>{loginCalls++;return pending.promise;}},pending=deferred();
    const react={
        useRef<T>(value: T): {current: T} {const i=cursor++;return (slots[i]??(slots[i]={current:value})) as {current: T};},
        useState<T>(value: T): [T, (value: T) => void] {const i=cursor++;slots[i]??=value;return [slots[i] as T,v=>{slots[i]=v;}];},
        useEffect(fn: Effect,deps: unknown[]) {const i=cursor++,old=slots[i] as EffectSlot | undefined;if(!old||deps.some((v,j)=>v!==old.deps[j])){effects.push(()=>{if(typeof old?.cleanup==='function')old.cleanup();const cleanup=fn();slots[i]={deps,cleanup,fn};});}}
    };
    const jsx=(type: unknown,props: ElementNode['props']): ElementNode=>({type,props});
    const module={exports:{} as {default: () => ElementNode | null}},source=fs.readFileSync(path.resolve(__dirname,'../app/src/main/frontend/views/LoginView.tsx'),'utf8');
    const compiled=ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022,jsx:ts.JsxEmit.ReactJSX}});
    const lifecycleModule={exports:{}};vm.runInNewContext(ts.transpileModule(fs.readFileSync(path.resolve(__dirname,'../app/src/main/frontend/util/startupLifecycle.ts'),'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText,{module:lifecycleModule,exports:lifecycleModule.exports});
    vm.runInNewContext(compiled.outputText,{module,exports:module.exports,window:{location:{set href(value){redirects.push(value);}}},require(name){if(name==='react')return react;if(name==='react/jsx-runtime')return {jsx,jsxs:jsx};if(name==='Frontend/util/startupLifecycle')return lifecycleModule.exports;if(name==='Frontend/util/auth')return {useAuth:()=>auth};if(name==='@heroui/react')return new Proxy({useDisclosure:()=>({})},{get:(o,k)=>o[k]??k});if(name==='formik')return {Form:'Form',Formik:'Formik'};if(name==='Frontend/generated/endpoints')return {RegistrationEndpoint:{isSelfRegistrationAllowed:async()=>false}};return {default:name};}});
    function render(){cursor=0;effects=[];tree=module.exports.default();for(const effect of effects)effect();return tree;}
    function find(node: unknown): ElementNode | null {if(!node||typeof node!=='object')return null;const element=node as ElementNode;if(element.type==='Formik')return element;for(const child of [element.props?.children].flat(Infinity)){const result=find(child);if(result)return result;}return null;}
    render();await new Promise<void>(resolve=>setImmediate(resolve));render();return {auth,pending,redirects,render,get loginCalls(){return loginCalls;},submit(formik: FormikHelpers={setFieldError(){}}){return find(tree)!.props.onSubmit!({username:'synthetic',password:'not-persisted'},formik);},replayEffects(){for(const value of slots){const slot=value as EffectSlot | undefined;if(slot?.fn){if(typeof slot.cleanup==='function')slot.cleanup();slot.cleanup=slot.fn();}}},unmount(){for(const value of slots){const slot=value as EffectSlot | undefined;if(typeof slot?.cleanup==='function')slot.cleanup();}}};
}
test('interactive state update before login result preserves saved target exactly once',async()=>{const f=await fixture(),p=f.submit();f.auth.state.user={};f.render();const before=[...f.redirects];f.pending.resolve({redirectUrl:'/saved',defaultUrl:'/default'});await p;f.render();assert.deepEqual({before,after:f.redirects},{before:[],after:['/saved']});});
test('interactive result before auth rerender uses default target exactly once',async()=>{const f=await fixture(),p=f.submit();f.pending.resolve({defaultUrl:'/default'});await p;f.auth.state.user={};f.render();f.render();assert.deepEqual(f.redirects,['/default']);});
test('already authenticated visit and effect replay redirect once without interactive login',async()=>{const f=await fixture({});f.replayEffects();f.render();f.auth.state.user={};f.render();assert.deepEqual(f.redirects,['/']);assert.equal(f.loginCalls,0);});
test('failed credentials retain form error and allow a successful retry',async()=>{const f=await fixture(),errors=[],p=f.submit({setFieldError:(...v)=>errors.push(v)});f.pending.resolve({error:true});await p;assert.equal(errors.length,2);assert.deepEqual(f.redirects,[]);f.auth.login=async()=>({redirectUrl:'/retry'});f.render();await f.submit();assert.deepEqual(f.redirects,['/retry']);});
test('double submit is single flight and unmounted completion cannot redirect',async()=>{const f=await fixture(),a=f.submit(),b=f.submit(),calls=f.loginCalls;f.unmount();f.pending.resolve({redirectUrl:'/saved'});await Promise.all([a,b]);assert.deepEqual({calls,redirects:f.redirects},{calls:1,redirects:[]});});
test('rejected login promise releases ownership for a normal retry',async()=>{const f=await fixture(),failure=new Error('Synthetic transport failure');f.auth.login=async()=>{throw failure;};f.render();await assert.rejects(f.submit(),error=>error===failure);assert.deepEqual(f.redirects,[]);f.auth.login=async()=>({defaultUrl:'/retry-default'});f.render();await f.submit();assert.deepEqual(f.redirects,['/retry-default']);});
