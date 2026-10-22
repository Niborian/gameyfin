import {safeProxyDiagnostics} from '../../scripts/browser-benchmark/frontend_ui_driver.mjs';
export const CASE1_MODE='--public-auth-case1';
export const CASE1_COMMAND='public-auth-case1';
export const CASE1_WINDOW='login-document-start-to-first-enabled-login-form-checkpoint';
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
