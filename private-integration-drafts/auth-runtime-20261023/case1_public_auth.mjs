// Inactive draft: no entry point, browser construction, network gate, or workflow.
import {FixtureUiDriver, classifyUserInfoBody, loginFormExpression} from '../../scripts/browser-benchmark/frontend_ui_driver.mjs';
import {case1ObservationReady} from './browser_entry.draft.mjs';
import {validateCase1Failure} from './case1_protocol.mjs';

const CATALOG = /^\/connect\/(Library|Collection|Platform|GameRequest|Plugin|Game)Endpoint\//;
const ADMIN_LIST = /^\/connect\/(?:UserEndpoint\/getAllUsers|BandwidthMonitoringEndpoint\/getActiveSessions|LibraryEndpoint\/subscribeToScanProgressEvents)$/;

export async function runPublicAuthCase({driver, cdp, publicPolicyEnabled, onFailure=()=>{}}) {
  if (!(driver instanceof FixtureUiDriver) || driver.cdp !== cdp || !case1ObservationReady(cdp) || typeof cdp?.onEvent !== 'function' || publicPolicyEnabled !== true || driver.authRound !== 0 || driver.navigations !== 0)
    throw new Error('Fresh original driver and verified owned public-policy lease required');
  const origin = new URL(driver.origin);
  if (origin.protocol !== 'http:' || origin.hostname !== '127.0.0.1' || !origin.port)
    throw new Error('Existing fixture origin required');
  const prior = cdp.onEvent, pending = new Set(), sockets = new Set(), oldSockets = new Set(), proof = [];
  let phase = 'public', requests = 0, adminCalls = 0, authCatalogCalls = 0;
  let cancelled = 0, pendingAtSignIn = 0, anonymousId = null, anonymousNull = false, anonymousStatus = null;
  let loginDocumentObserved = false, overflow = false;
  let diagnosticStage = null;
  let profileClassification = 'not-observed';
  const boundedId = id => typeof id === 'string' && id.length <= 128;
  const parse = url => { try { const u = new URL(url); return u.origin === driver.origin ? u.pathname : null; } catch { return null; } };
  const ownedSocket = url => { try { const u = new URL(url); return u.protocol === 'ws:' && u.host === origin.host && !u.username && !u.password; } catch { return false; } };
  const observe = (method, event) => {
    prior(method, event); // Existing origin interception/cancellation owner stays authoritative.
    if (method === 'Fixture.observationOverflow') overflow = true;
    if (method === 'Page.frameNavigated' && !event.frame?.parentId && parse(event.frame?.url) === '/login') {
      phase = 'auth-document'; loginDocumentObserved = true;
    }
    if (method === 'Network.requestWillBeSent') {
      if (++requests > 256) { overflow = true; return; }
      const path = parse(event.request?.url);
      if (event.request?.method === 'POST' && path) {
        if (ADMIN_LIST.test(path)) adminCalls++;
        if (CATALOG.test(path)) {
          if (phase === 'auth-document') authCatalogCalls++;
          if (boundedId(event.requestId) && pending.size < 128) pending.add(event.requestId); else overflow = true;
        }
        if (phase === 'public' && path === '/connect/UserEndpoint/getUserInfo' && anonymousId === null && boundedId(event.requestId)) { anonymousId = event.requestId; profileClassification = 'pending'; }
      }
    }
    if (method === 'Network.responseReceived' && event.requestId === anonymousId) anonymousStatus = event.response?.status;
    if (method === 'Network.loadingFinished' && event.requestId === anonymousId && proof.length === 0) {
      if (anonymousStatus !== 200 || !Number.isFinite(event.encodedDataLength) || event.encodedDataLength > 16384) overflow = true;
      else proof.push(driver.request('Network.getResponseBody', {requestId: anonymousId})
        .then(body => { profileClassification = classifyUserInfoBody(body); anonymousNull = profileClassification === 'null'; }, () => { profileClassification = 'failed'; overflow = true; }));
    }
    if (['Network.loadingFinished', 'Network.loadingFailed'].includes(method) && pending.delete(event.requestId)) {
      if (method === 'Network.loadingFailed' && event.canceled === true) cancelled++;
    }
    if (method === 'Network.webSocketCreated') {
      if (!boundedId(event.requestId) || sockets.size >= 16 || !ownedSocket(event.url)) overflow = true;
      else sockets.add(event.requestId);
    }
    if (method === 'Network.webSocketClosed') { sockets.delete(event.requestId); oldSockets.delete(event.requestId); }
  };
  cdp.onEvent = observe;
  try {
    await driver.enableBeforeAuth();
    // These existing driver methods verify exact original content, decode and SHA256 cover.
    const library = await driver.navigate('/library/27003');
    const game = await driver.navigate('/game/33000');
    if (library.coverSha256Matches !== true || game.coverSha256Matches !== true) throw new Error('Original cover identity unproved');
    diagnosticStage = 'version';
    await driver.poll("(()=>({ready:[...document.querySelectorAll('p')].some(p=>p.textContent.trim()==='Unofficial Gameyfin 2.4.3')}))()");
    diagnosticStage = 'anonymous-profile';
    await Promise.all(proof);
    if (!anonymousNull || overflow || adminCalls !== 0) throw new Error('Anonymous public identity/bounds unproved');
    pendingAtSignIn = pending.size;
    for (const id of sockets) oldSockets.add(id);
    diagnosticStage = 'sign-in';
    const clicked = await driver.evaluate(`(()=>{const buttons=[...document.querySelectorAll('nav button.gradient-primary:not(.size-8)')].filter(b=>b.getClientRects().length>0);if(location.origin!==${JSON.stringify(driver.origin)}||buttons.length!==1)return false;buttons[0].click();return true;})()`);
    if (clicked !== true) throw new Error('Actual anonymous Sign In control unavailable');
    diagnosticStage = 'login-form';
    await driver.poll(loginFormExpression()); // Original readiness; no artificial delay.
    diagnosticStage = 'settlement';
    if (!loginDocumentObserved || overflow || adminCalls || authCatalogCalls || pending.size || oldSockets.size)
      throw new Error('Auth-document bootstrap/old-document settlement unproved');
    return {uiVersionVerified: true, anonymousProfileWasNull: true, originalPublicLibraryAndGamePassed: true,
      coversDecodedAndHashVerified: library.coverSha256Matches === true && game.coverSha256Matches === true,
      actualSignInAndLoginReadinessPassed: true, authDocumentCatalogRpcCount: authCatalogCalls,
      anonymousAdminListHttpRpcCount: adminCalls, anonymousAdminSubscriptionAbsenceProven: false,
      authCatalogObservationWindow: 'login-document-start-to-first-enabled-login-form-checkpoint',
      naturalPendingCatalogAtSignIn: pendingAtSignIn,
      catalogRequestsCancelled: cancelled, oldDocumentCatalogHttpAndWebSocketsSettledAtCheckpoint: true,
      sameDocumentReactCancellationProven: false, staleSubscriptionCallbackFencingProven: false,
      fullIssue154AcceptanceProven: false};
  } catch (error) {
    if (diagnosticStage !== null) {
      // One observation only, within the existing total budget; no retry or assertion recovery.
      let dom = null;
      try {
        dom = await driver.evaluate(`(()=>{const u=document.querySelector('input[name="username"]'),p=document.querySelector('input[name="password"]'),b=u?.closest('form')?.querySelector('button[type="submit"]');return {originMatches:location.origin===${JSON.stringify(driver.origin)},libraryPath:location.pathname==='/library/27003',gamePath:location.pathname==='/game/33000',loginPath:location.pathname==='/login',navPresent:!!document.querySelector('nav'),signInControlUnique:[...document.querySelectorAll('nav button.gradient-primary:not(.size-8)')].filter(b=>b.getClientRects().length>0).length===1,usernamePresent:!!u,passwordPresent:!!p,submitPresent:!!b,submitEnabled:!!b&&!b.disabled,documentComplete:document.readyState==='complete',footerVersionMatches:[...document.querySelectorAll('p')].some(p=>p.textContent.trim()==='Unofficial Gameyfin 2.4.3')};})()`,Math.min(driver.totalDeadline,performance.now()+250));
      } catch { /* Unavailable observation is explicitly null; original error still fails. */ }
      onFailure(validateCase1Failure({stage:diagnosticStage,dom,profileClassification,anonymousHttpStatus:Number.isInteger(anonymousStatus)&&anonymousStatus>=100&&anonymousStatus<=599?anonymousStatus:null,anonymousRequestObserved:anonymousId!==null,anonymousProfileWasNull:anonymousNull,observationOverflow:overflow,loginDocumentObserved,pendingCatalogHttp:Math.min(128,pending.size),oldWebSockets:Math.min(16,oldSockets.size),anonymousAdminHttp:Math.min(256,adminCalls),authCatalogHttp:Math.min(256,authCatalogCalls)}));
    }
    throw error;
  } finally {
    if (cdp.onEvent !== observe) throw new Error('Original CDP owner lost');
    cdp.onEvent = prior;
    pending.clear(); sockets.clear(); oldSockets.clear();
  }
}
