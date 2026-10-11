"""Normal-app synthetic fixture contracts. No Docker/SSH/process launch.

Outer CI launcher owns exact containers, offline-H2 import only while stopped,
caps, independent deadlines and cleanup. Credentials are ephemeral memory only.
"""
import hashlib,http.cookiejar,importlib.util,io,ipaddress,json,re,secrets,urllib.parse,urllib.request,zipfile
from pathlib import Path
from fixture_adapter import PLUGIN_ID,seed_appendix,validate_enrichment,validate_source_manifest
from synthetic_image_fixture import cover_bytes,cover_route,fixture_manifest,update_game_body,validate_response

def generate_fixture(output):
    output=Path(output)
    seed_path=Path(__file__).resolve().parents[2]/'scripts/rehearsal/seed-scan-fixture.py'
    spec=importlib.util.spec_from_file_location('benchmark_seed',seed_path)
    seed=importlib.util.module_from_spec(spec);spec.loader.exec_module(seed)
    seed.generate(output,'/fixture')
    manifest=json.loads((output/'manifest.json').read_text())
    with (output/'seed.sql').open('a',encoding='utf8') as sql:sql.write(seed_appendix(manifest))
    for path in (output/'sources').rglob('*'):path.chmod(0o755 if path.is_dir() else 0o644)
    (output/'sources').chmod(0o755)
    images=output/'images';images.mkdir(mode=0o755);images.chmod(0o755)
    (images/'cover.png').write_bytes(cover_bytes());(images/'cover.png').chmod(0o644)
    return {'manifest':manifest,'manifestSha256':hashlib.sha256(json.dumps(manifest,sort_keys=True,separators=(',',':')).encode()).hexdigest(),
        'seedGeneratorSha256':hashlib.sha256(seed_path.read_bytes()).hexdigest(),'image':fixture_manifest()}

class SameOriginRedirect(urllib.request.HTTPRedirectHandler):
    def __init__(self,origin):self.origin=origin
    def redirect_request(self,request,fp,code,msg,headers,newurl):
        if urllib.parse.urlsplit(newurl).scheme!='http' or urllib.parse.urlsplit(newurl).netloc!=urllib.parse.urlsplit(self.origin).netloc:
            raise ValueError('Fixture redirect left exact origin')
        return super().redirect_request(request,fp,code,msg,headers,newurl)

class FixtureClient:
    def __init__(self,address):
        parsed=ipaddress.ip_address(address)
        if parsed.version!=4 or not parsed.is_private or parsed.is_loopback or parsed.is_link_local:
            raise ValueError('Exact private internal Docker IPv4 required')
        self.base='http://'+str(parsed)+':8080';self.metrics_base='http://'+str(parsed)+':8081'
        self.cookies=http.cookiejar.CookieJar();self.csrf=None
        self.opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),SameOriginRedirect(self.base),urllib.request.HTTPCookieProcessor(self.cookies))
    def raw(self,path,body=None,headers=None,*,management=False):
        if not path.startswith('/') or path.startswith('//') or '\\' in path or len(path)>1024:
            raise ValueError('Fixture path bound')
        request=urllib.request.Request((self.metrics_base if management else self.base)+path,body,headers or {})
        try:response=self.opener.open(request,timeout=10)
        except urllib.error.HTTPError as error:
            if error.code!=304:raise RuntimeError('Fixture HTTP request failed') from None
            response=error
        with response:
            data=response.read(2*1024*1024+1)
            if len(data)>2*1024*1024:raise ValueError('Fixture response byte bound')
            return response.status,dict(response.headers),data
    def request(self,path,body=None):
        data=None if body is None else json.dumps(body,separators=(',',':')).encode()
        if data and len(data)>65536:raise ValueError('Fixture request bound')
        headers={} if body is None else {'Content-Type':'application/json','X-CSRF-Token':self.csrf or ''}
        status,_,value=self.raw(path,data,headers)
        if status!=200:raise RuntimeError('Fixture API status')
        return json.loads(value) if value else None
    def page_csrf(self,path):
        status,_,body=self.raw(path)
        match=re.search(rb'<meta[^>]*name="_csrf"[^>]*content="([^"]{1,512})"',body)
        if status!=200 or not match:raise RuntimeError('Normal fixture CSRF page unavailable')
        self.csrf=match[1].decode('ascii');return self.csrf
    def bootstrap(self):
        self.page_csrf('/setup');password=secrets.token_urlsafe(32)
        self.request('/connect/SetupEndpoint/registerSuperAdmin',{'superAdminRegistration':
            {'username':'fixture-admin','password':password,'email':'fixture@example.invalid'}})
        self.login(password)
        for plugin in (PLUGIN_ID,'org.gameyfin.plugins.download.direct'):
            self.request('/connect/PluginEndpoint/enablePlugin',{'pluginId':plugin})
        self.plugin_gate();return password
    def login(self,password):
        if not isinstance(password,str) or not 32<=len(password)<=128:raise ValueError('Ephemeral fixture password bound')
        self.page_csrf('/login')
        form=urllib.parse.urlencode({'username':'fixture-admin','password':password,'_csrf':self.csrf}).encode()
        status,_,body=self.raw('/login',form,{'Content-Type':'application/x-www-form-urlencoded'})
        match=re.search(rb'<meta[^>]*name="_csrf"[^>]*content="([^"]{1,512})"',body)
        if status!=200 or not match:raise RuntimeError('Normal fixture login failed')
        self.csrf=match[1].decode('ascii')
    def plugin_gate(self):
        plugins=self.request('/connect/PluginEndpoint/getAll',{})
        match=[p for p in plugins if p.get('id')==PLUGIN_ID]
        if len(match)!=1 or match[0].get('trustLevel')!='THIRD_PARTY' or match[0].get('state')!='STARTED':
            raise RuntimeError('Normal THIRD_PARTY fixture enable lifecycle required')
        return {'id':PLUGIN_ID,'trustLevel':'THIRD_PARTY','state':'STARTED'}
    def ingest_cover(self):
        self.request('/connect/GameEndpoint/updateGame',update_game_body())
        return cover_route(self.request('/connect/GameEndpoint/getAll',{}))
    def image_gate(self,path):
        if cover_route(self.request('/connect/GameEndpoint/getAll',{}))!=path:raise ValueError('Cover association changed')
        status,headers,body=self.raw(path);normal=validate_response(status,headers,body)
        status,headers,body=self.raw(path,headers={'If-None-Match':normal['etag']})
        conditional=validate_response(status,headers,body,expected_etag=normal['etag'],conditional=True)
        return {'associationStable':True,'normal200':normal,'conditional304':conditional}
    def scan(self):self.request('/connect/LibraryEndpoint/triggerScan',{'scanType':'FULL','libraryIds':[27001,27002,27003,27004]})
    def validate(self,fixture,path):
        result=validate_enrichment(self.request('/connect/GameEndpoint/getAll',{}),fixture['manifest'])
        result['image']=self.image_gate(path);result['downloads']=self.downloads(fixture['manifest'])
        return result
    def downloads(self,manifest):
        providers=self.request('/connect/DownloadProviderEndpoint/getProviders',{})
        if len(providers)!=1:raise ValueError('Exactly one explicitly enabled direct provider required')
        game=manifest['games'][0];results=[]
        for optional in (False,True):
            ids=[game['requiredContentId']]+([game['optionalContentId']] if optional else [])
            members=game['requiredMembers']+(game['optionalMembers'] if optional else [])
            archive_members=game['requiredArchiveMembers'].copy()
            if optional:archive_members.update(game['optionalArchiveMembers'])
            expected={name:manifest['sha256'][member] for name,member in archive_members.items()}
            query=urllib.parse.urlencode({'provider':providers[0]['key'],'variantId':game['variantId'],'contentIds':','.join(map(str,ids)),'explicitSelection':'true'})
            route='/download/'+str(game['gameId'])
            estimate=self.request(route+'/estimate?'+query)
            status,_,payload=self.raw(route+'?'+query)
            if status!=200:raise ValueError('Exact selection ZIP status')
            with zipfile.ZipFile(io.BytesIO(payload)) as archive:
                entries=[e for e in archive.infolist() if not e.is_dir()]
                if len(entries)!=len(expected) or any(e.file_size>65536 for e in entries) or sum(e.file_size for e in entries)>131072:
                    raise ValueError('Synthetic ZIP member/count/inflation bound')
                actual={e.filename:hashlib.sha256(archive.read(e)).hexdigest() for e in entries}
                if actual!=expected or int(estimate)!=sum(e.file_size for e in entries):raise ValueError('Exact selection bytes/estimate changed')
            results.append({'optionalSelected':optional,'selectedBytesMatch':True,'estimateBytes':int(estimate)})
        return results

def verify_sources(output,manifest):
    sources=Path(output)/'sources'
    if sources.is_symlink():raise ValueError('Synthetic source root cannot be symlink')
    paths=list(sources.rglob('*'))
    if any(p.is_symlink() for p in paths):raise ValueError('Synthetic fixture source symlink rejected')
    actual={p.relative_to(sources).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in paths if p.is_file()}
    return validate_source_manifest(manifest,actual)
