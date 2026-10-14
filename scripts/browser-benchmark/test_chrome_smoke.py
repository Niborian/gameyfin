import json,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
import chrome_smoke
from hosted_browser import CREATION_PLAN
class Smoke(unittest.TestCase):
 def test_cancellation_recovery_exact_identity_and_removed_proof(self):
  with tempfile.TemporaryDirectory() as directory:
   path=Path(directory)/'intent.json';owner='a'*16;image='sha256:'+'b'*64;identity='c'*64
   plan={'owner':owner,'image':image,'id':None,'contract':CREATION_PLAN,'entrypoint':['node'],'cmd':['/opt/gameyfin-browser/chrome_smoke.mjs']};path.write_text(json.dumps(plan))
   row={'Name':'/gameyfin-browser-'+owner,'Config':{'Entrypoint':['node'],'Cmd':plan['cmd']}}
   with patch.object(chrome_smoke,'ledger_path',return_value=path),patch.object(chrome_smoke,'validate_cleanup_scope'),patch.object(chrome_smoke,'command',side_effect=[identity,json.dumps([row]),'','', ''] ) as command:
    chrome_smoke.cleanup(path)
    self.assertIn((('rm','--force',identity),),[(c.args,) for c in command.call_args_list]);self.assertFalse(path.exists())
 def test_residual_container_keeps_intent(self):
  with tempfile.TemporaryDirectory() as directory:
   path=Path(directory)/'intent.json';owner='a'*16;image='sha256:'+'b'*64;identity='c'*64
   plan={'owner':owner,'image':image,'id':identity,'contract':CREATION_PLAN,'entrypoint':['node'],'cmd':['/opt/gameyfin-browser/chrome_smoke.mjs']};path.write_text(json.dumps(plan))
   row={'Name':'/gameyfin-browser-'+owner,'Config':{'Entrypoint':['node'],'Cmd':plan['cmd']}}
   with patch.object(chrome_smoke,'ledger_path',return_value=path),patch.object(chrome_smoke,'validate_cleanup_scope'),patch.object(chrome_smoke,'command',side_effect=[identity,json.dumps([row]),'',identity]):
    with self.assertRaises(ValueError):chrome_smoke.cleanup(path)
    self.assertTrue(path.exists())
 def test_receipt_rejects_raw_and_noninteger_codes(self):
  row={'scope':'Chrome-only startup diagnostic; not app performance','started':False,'sandboxVerified':False,'exitCode':None,'signal':'SIGTRAP','signalScope':'Includes diagnostic shutdown; not automatic crash attribution','deadlineTriggered':False,'exitObservedBeforeCleanup':True,'failurePhase':'endpoint','fatalFlags':{x:False for x in ('pthreadCreate','resourceUnavailable','checkFailed','seccomp','ptrace','credentialsSource','zygoteSource')},'before':{x:0 for x in ('pids.events.max','memory.events.max','memory.events.oom','memory.events.oom_kill')},'after':{x:0 for x in ('pids.events.max','memory.events.max','memory.events.oom','memory.events.oom_kill')}}
  row['chromeCacheVersion']='155.0.8059.39';row['zygoteChecks']={**{x:False for x in ('processValid','socketpair','receiveProcessId','bootPid','realPid')},'errno':None,'sourceLine':None}
  row['fatalExcerpt']={'lines':['[FATAL] Check failed: .'],'rejected':False}
  self.assertEqual(chrome_smoke.validate_receipt(row),row)
  for mutation in ({'raw':'secret'},{'exitCode':True},{'signal':'private'},{'chromeCacheVersion':'/private/secret'}):
   with self.assertRaises(ValueError):chrome_smoke.validate_receipt({**row,**mutation})
  for mutation in ({'raw':'private'},{'errno':True},{'errno':999},{'processValid':'private'},{'sourceLine':True},{'sourceLine':100001}):
   with self.assertRaises(ValueError):chrome_smoke.validate_receipt({**row,'zygoteChecks':{**row['zygoteChecks'],**mutation}})
  for line in ['password=private','api_key: hidden','session_id: hidden','session-id: hidden','auth_header: hidden','SID: hidden','client_id: hidden','a=hidden','credential: hidden','ws://private','ftp://private','2001:db8::1','https://private','/private','10.0.0.200','bad\x01text','x'*257]:
   with self.assertRaises(ValueError):chrome_smoke.validate_receipt({**row,'fatalExcerpt':{'lines':[line],'rejected':False}})
 def test_recorded_renamed_container_cannot_be_forgotten(self):
  with tempfile.TemporaryDirectory() as directory:
   path=Path(directory)/'intent.json';owner='a'*16;image='sha256:'+'b'*64;identity='c'*64
   path.write_text(json.dumps({'owner':owner,'image':image,'id':identity,'contract':CREATION_PLAN,'entrypoint':['node'],'cmd':['/opt/gameyfin-browser/chrome_smoke.mjs']}))
   row={'Name':'/renamed'}
   with patch.object(chrome_smoke,'ledger_path',return_value=path),patch.object(chrome_smoke,'validate_cleanup_scope',side_effect=ValueError('unknown scope')),patch.object(chrome_smoke,'command',side_effect=[identity,json.dumps([row])]) as commands:
    with self.assertRaises(ValueError):chrome_smoke.cleanup(path)
    self.assertTrue(path.exists());self.assertFalse(any(c.args[0]=='rm' for c in commands.call_args_list))
if __name__=='__main__':unittest.main()
