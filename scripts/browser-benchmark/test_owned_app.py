import json,os,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
from owned_app import exact_root,read_regular,validate_owned,hosted_only,JVM,intent_contract,recover_intents,persist_ledger,cleanup
from ownership_index import create,planned,created,read,index_path
from run_benchmark import validate_receipt,CONTROL,CANDIDATE

class OwnedAppTest(unittest.TestCase):
    def root(self,parent,owner):
        root=Path(parent)/('gameyfin-browser-fixture-'+owner);root.mkdir()
        s=root.stat();(root/'OWNER').write_text('benchmark-27-'+owner)
        (root/'IDENTITY.json').write_text(json.dumps({'owner':owner,'device':s.st_dev,'inode':s.st_ino}))
        return root
    def test_root_inode_and_regular_marker(self):
        with tempfile.TemporaryDirectory() as parent:
            owner='a'*16;root=self.root(parent,owner)
            self.assertEqual(exact_root(root,owner),root)
            saved=json.loads((root/'IDENTITY.json').read_text());saved['inode']+=1
            (root/'IDENTITY.json').write_text(json.dumps(saved))
            with self.assertRaises(ValueError):exact_root(root,owner)
            (root/'OWNER').write_text('x'*65)
            with self.assertRaises(ValueError):read_regular(root/'OWNER',64)
    def test_unknown_container_or_mount_rejected(self):
        with tempfile.TemporaryDirectory() as parent:
            owner='b'*16;root=self.root(parent,owner);identity='c'*64;image='sha256:'+'d'*64
            row={'Id':identity,'Image':image,'Name':'/gameyfin-benchmark-app-'+owner,
                'Config':{'Labels':{'gameyfin.fixture.owner':'benchmark-27-'+owner}},'HostConfig':{},'Mounts':[]}
            self.assertEqual(validate_owned(row,root,owner,image,identity),identity)
            row['Mounts']=[{'Type':'bind','Source':parent}]
            with self.assertRaises(ValueError):validate_owned(row,root,owner,image,identity)
            row['Mounts']=[];row['Id']='e'*64
            with self.assertRaises(ValueError):validate_owned(row,root,owner,image,identity)
    def test_index_tracks_only_exact_current_job_created_roots(self):
        with tempfile.TemporaryDirectory() as parent,patch.dict(os.environ,{'RUNNER_TEMP':parent,'GITHUB_RUN_ID':'123','GITHUB_RUN_ATTEMPT':'1','GITHUB_JOB':'paired'}):
            path=create();owner='f'*16;root=Path(parent)/('gameyfin-browser-fixture-'+owner)
            planned(path,root,owner);root.mkdir();created(path,root,owner)
            row=read(path)['roots'][0]
            self.assertEqual(row['inode'],root.stat().st_ino)
            with self.assertRaises(ValueError):planned(path,root,owner)
            with self.assertRaises(FileExistsError):create()
    def test_source_receipt_real_keys_and_parity_required(self):
        before='sha256:'+'1'*64;after='sha256:'+'2'*64
        receipt={'controlSource':CONTROL,'candidateSource':CANDIDATE,'entryByteParity':True,'normalizationPerformed':False,
            'differingResourceEntries':[],'controlImageId':before,'candidateImageId':after,
            'controlJarSha256':'3'*64,'candidateJarSha256':'4'*64}
        validate_receipt(receipt,before,after)
        receipt['normalizationPerformed']=True
        with self.assertRaises(ValueError):validate_receipt(receipt,before,after)
    def test_cancel_after_create_before_id_record_recovers_only_exact_intent(self):
        with tempfile.TemporaryDirectory() as parent:
            owner='a'*16;root=self.root(parent,owner);(root/'db').mkdir()
            image='sha256:'+'b'*64;identity='c'*64;name='gameyfin-benchmark-helper-'+owner+'-dddddddd'
            intent=intent_contract(name,image,'0:0','none',[(root/'db','/owned/db',False)],'chown',['1337:1337','/owned/db'],caps=('CHOWN',))
            ledger={'owner':owner,'network':'gameyfin-browser-net-'+owner,'containers':[],'intents':[intent]}
            persist_ledger(root,ledger)
            row={'Id':identity,'Image':image,'Name':'/'+name,
                'Config':{'User':'0:0','Entrypoint':['chown'],'Cmd':['1337:1337','/owned/db'],
                    'Labels':{'gameyfin.fixture.owner':'benchmark-27-'+owner}},
                'HostConfig':{'NetworkMode':'none','NanoCpus':250000000,'Memory':256*1024*1024,
                    'MemorySwap':256*1024*1024,'PidsLimit':64,'CapDrop':['ALL'],'CapAdd':['CHOWN'],
                    'CgroupnsMode':'private','IpcMode':'private'},
                'Mounts':[{'Type':'bind','Source':str(root/'db'),'Destination':'/owned/db','RW':True}]}
            def command(*args):return identity+'\n' if args[0]=='ps' else json.dumps([row])
            with patch('owned_app.command',command):
                recovered=recover_intents(root,owner,json.loads((root/'ledger.json').read_text()))
                self.assertEqual(recovered['containers'],[{'id':identity,'image':image}])
                self.assertEqual(json.loads((root/'ledger.json').read_text())['containers'],recovered['containers'])
                row['HostConfig']['CapAdd']=['CAP_CHOWN']
                self.assertEqual(recover_intents(root,owner,recovered)['containers'],recovered['containers'])
                row['HostConfig']['Memory']=512*1024*1024
                with self.assertRaises(ValueError):recover_intents(root,owner,recovered)
    def test_no_local_or_server_docker_entry(self):
        with patch.dict(os.environ,{},clear=True):
            with self.assertRaises(ValueError):hosted_only()
        self.assertIn('-XX:+UseCompactObjectHeaders',JVM);self.assertIn('-XX:G1PeriodicGCInterval=0',JVM)
    def test_other_exact_resources_still_removed_when_browser_guard_refuses(self):
        with tempfile.TemporaryDirectory() as parent:
            owner='a'*16;root=self.root(parent,owner);identity='b'*64;image='sha256:'+'c'*64;network='gameyfin-browser-net-'+owner
            ledger={'owner':owner,'network':network,'containers':[{'id':identity,'image':image}],'intents':[]};persist_ledger(root,ledger)
            row={'Id':identity,'Image':image,'Name':'/gameyfin-benchmark-app-'+owner,'Config':{'Labels':{'gameyfin.fixture.owner':'benchmark-27-'+owner}},'HostConfig':{},'Mounts':[]}
            ids=[identity];removed=[]
            def command(*args):
                if args[0]=='ps':return '\n'.join(ids)
                if args[0]=='inspect':return json.dumps([row])
                if args[:2]==('rm','--force'):removed.append(args[2]);ids.remove(args[2]);return ''
                if args[:2]==('network','ls'):return network+'\n'
                if args[:2]==('network','inspect'):return json.dumps([{'Name':network,'Labels':{'gameyfin.fixture.owner':'benchmark-27-'+owner},'Containers':{'unknown-browser':{}}}])
                raise AssertionError('Unexpected command')
            with patch('owned_app.command',command),patch('hosted_browser.cleanup_known_browser',side_effect=ValueError('guard')):
                with self.assertRaises(RuntimeError):cleanup(root,owner,remove_root=True)
            self.assertEqual(removed,[identity]);self.assertTrue(root.exists())
            self.assertEqual(json.loads((root/'cleanup-diagnostic.json').read_text())['scope'],'Cleanup incomplete; mismatched resources retained')
    @unittest.skipUnless(os.name=='posix','Actual FIFO requires Linux')
    def test_fifo_marker_rejects_without_blocking(self):
        import time
        with tempfile.TemporaryDirectory() as parent:
            fifo=Path(parent)/'marker';os.mkfifo(fifo)
            started=time.monotonic()
            with self.assertRaises(ValueError):read_regular(fifo,64)
            self.assertLess(time.monotonic()-started,.5)
if __name__=='__main__':unittest.main()
