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
  self.assertEqual(chrome_smoke.validate_receipt(row),row)
  for mutation in ({'raw':'secret'},{'exitCode':True},{'signal':'private'}):
   with self.assertRaises(ValueError):chrome_smoke.validate_receipt({**row,**mutation})
 def test_recorded_renamed_container_cannot_be_forgotten(self):
  with tempfile.TemporaryDirectory() as directory:
   path=Path(directory)/'intent.json';owner='a'*16;image='sha256:'+'b'*64;identity='c'*64
   path.write_text(json.dumps({'owner':owner,'image':image,'id':identity,'contract':CREATION_PLAN,'entrypoint':['node'],'cmd':['/opt/gameyfin-browser/chrome_smoke.mjs']}))
   row={'Name':'/renamed'}
   with patch.object(chrome_smoke,'ledger_path',return_value=path),patch.object(chrome_smoke,'validate_cleanup_scope',side_effect=ValueError('unknown scope')),patch.object(chrome_smoke,'command',side_effect=[identity,json.dumps([row])]) as commands:
    with self.assertRaises(ValueError):chrome_smoke.cleanup(path)
    self.assertTrue(path.exists());self.assertFalse(any(c.args[0]=='rm' for c in commands.call_args_list))
if __name__=='__main__':unittest.main()
