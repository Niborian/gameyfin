import {safeProxyDiagnostics} from '../../scripts/browser-benchmark/frontend_ui_driver.mjs';
export const CASE1_MODE='--public-auth-case1';
export const CASE1_COMMAND='public-auth-case1';
export const CASE1_WINDOW='login-document-start-to-first-enabled-login-form-checkpoint';
export const CASE1_FAILURE_STAGES=Object.freeze(['version','anonymous-profile','sign-in','login-form','settlement']);
export const CASE1_DOM_FLAGS=Object.freeze(['originMatches','libraryPath','gamePath','loginPath','navPresent','signInControlUnique','usernamePresent','passwordPresent','submitPresent','submitEnabled','documentComplete','footerVersionMatches']);
export function validateCase1Failure(value){
  const flags=['anonymousRequestObserved','anonymousProfileWasNull','observationOverflow','loginDocumentObserved'];
  const limits={pendingCatalogHttp:128,oldWebSockets:16,anonymousAdminHttp:256,authCatalogHttp:256};
  const keys=['stage','dom','websocketLifecycle','profileClassification','anonymousHttpStatus',...flags,...Object.keys(limits)];
  const ws=value?.websocketLifecycle,wsKeys=['created','handshake101','handshakeNon101','frameError','closed'];
  if(!ws||typeof ws!=='object'||Object.keys(ws).length!==wsKeys.length||!wsKeys.every(k=>Number.isInteger(ws[k])&&ws[k]>=0&&ws[k]<=16&&ws[k]<=ws.created))throw new Error('Case1 WebSocket diagnostic boundary');
  if(!['not-observed','pending','null','fixture-user','other','invalid','failed'].includes(value?.profileClassification)||(value?.anonymousHttpStatus!==null&&(!Number.isInteger(value?.anonymousHttpStatus)||value.anonymousHttpStatus<100||value.anonymousHttpStatus>599)))throw new Error('Case1 profile diagnostic boundary');
  if(!value||typeof value!=='object'||Object.keys(value).length!==keys.length||!keys.every(k=>Object.hasOwn(value,k))||!CASE1_FAILURE_STAGES.includes(value.stage)||!flags.every(k=>typeof value[k]==='boolean')||!Object.entries(limits).every(([k,max])=>Number.isInteger(value[k])&&value[k]>=0&&value[k]<=max))throw new Error('Case1 failure boundary');
  if(value.dom!==null&&(!value.dom||typeof value.dom!=='object'||Object.keys(value.dom).length!==CASE1_DOM_FLAGS.length||!CASE1_DOM_FLAGS.every(k=>typeof value.dom[k]==='boolean')))throw new Error('Case1 DOM boundary');
  return {...value,websocketLifecycle:{...ws},dom:value.dom===null?null:{...value.dom}};
}
export function parseCase1Arguments(argv){
  if(!Array.isArray(argv)||argv.length!==4||argv[3]!==CASE1_MODE)throw new Error('Exact public case1 mode required');
  return argv.slice(0,3);
}
const yes=['uiVersionVerified','anonymousProfileWasNull','originalPublicLibraryAndGamePassed','coversDecodedAndHashVerified','actualSignInAndLoginReadinessPassed','oldDocumentCatalogHttpAndWebSocketsSettledAtCheckpoint'];
const no=['anonymousAdminSubscriptionAbsenceProven','sameDocumentReactCancellationProven','staleSubscriptionCallbackFencingProven','fullIssue154AcceptanceProven'];
const zero=['authDocumentCatalogRpcCount','anonymousAdminListHttpRpcCount'];
export function validateCase1Result(value){
  const keys=[...yes,...no,...zero,'authCatalogObservationWindow','naturalPendingCatalogAtSignIn','catalogRequestsCancelled'];
  if(!value||typeof value!=='object'||Object.keys(value).length!==keys.length||!keys.every(k=>Object.hasOwn(value,k))||!yes.every(k=>value[k]===true)||!no.every(k=>value[k]===false)||!zero.every(k=>value[k]===0)||value.authCatalogObservationWindow!==CASE1_WINDOW||!Number.isInteger(value.naturalPendingCatalogAtSignIn)||value.naturalPendingCatalogAtSignIn<0||value.naturalPendingCatalogAtSignIn>128||!Number.isInteger(value.catalogRequestsCancelled)||value.catalogRequestsCancelled<0||value.catalogRequestsCancelled>256)throw new Error('Exact scoped public case1 proof required');
  return {...value};
}
export function case1Receipt(result,proxy){return {publicAuthCase1:validateCase1Result(result),proxyDiagnostics:safeProxyDiagnostics(proxy,false)};}
