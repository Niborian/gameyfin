"""Independent pure adversarial launch-contract review; no Docker execution."""
import copy
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch,Mock
import hosted_browser
from hosted_browser import validate_app_contract

OWNER='a'*16; IMAGE='sha256:'+'b'*64; ID='c'*64; NETWORK='gameyfin-browser-net-'+OWNER

class Tests(unittest.TestCase):
    def fixture(self,base,waiting=False):
        root=Path(base)/('gameyfin-browser-fixture-'+OWNER);root.mkdir();(root/'OWNER').write_text('benchmark-27-'+OWNER)
        mounts=[]
        for leaf in ('db','data','logs','plugins','plugindata'):
            path=root/leaf;path.mkdir();mounts.append({'Type':'bind','Source':str(path),'Destination':'/opt/gameyfin/'+leaf,'RW':True})
        for leaf,destination in (('sources','/fixture'),('images','/fixture-images')):
            path=root/'fixture'/leaf;path.mkdir(parents=True,exist_ok=True);mounts.append({'Type':'bind','Source':str(path),'Destination':destination,'RW':False})
        if waiting:
            (root/'waiting').mkdir();mounts.append({'Type':'bind','Source':str(root/'waiting'),'Destination':'/waiting-fixture','RW':True})
        row={'Id':ID,'Name':'/gameyfin-benchmark-app-'+OWNER,'Image':IMAGE,'Config':{'Labels':{'gameyfin.fixture.owner':'benchmark-27-'+OWNER},'User':'1337:1337'},'State':{'Running':True,'OOMKilled':False},'RestartCount':0,
            'HostConfig':{'NetworkMode':NETWORK,'Privileged':False,'PidMode':'','IpcMode':'private','UTSMode':'','CgroupnsMode':'private','PortBindings':{},'PublishAllPorts':False,'NanoCpus':500000000,'Memory':1024**3,'MemorySwap':1024**3,'PidsLimit':256,'CapDrop':['ALL'],'CapAdd':[]},'NetworkSettings':{'Networks':{NETWORK:{}}},'Mounts':mounts}
        return root,row

    def test_valid_normal_and_explicit_waiting_contract(self):
        for waiting in (False,True):
            with tempfile.TemporaryDirectory() as base:
                root,row=self.fixture(base,waiting)
                self.assertIs(validate_app_contract(row,root,OWNER,IMAGE,ID,NETWORK,waiting=waiting),row)
                if waiting:
                    with self.assertRaises(ValueError):validate_app_contract(row,root,OWNER,IMAGE,ID,NETWORK)

    def test_identity_network_mount_resource_and_namespace_mutations(self):
        cases=[('Id','d'*64),('Image','sha256:'+'d'*64),('Name','/other-app'),('RestartCount',1)]
        host=[('NetworkMode','host'),('Privileged',True),('PidMode','host'),('PidMode','container:other'),('IpcMode','container:other'),('UTSMode','host'),('Memory',2*1024**3),('MemorySwap',2*1024**3),('NanoCpus',1000000000),('PidsLimit',1024),('CapAdd',['SYS_ADMIN']),('PortBindings',{'8080/tcp':[{'HostPort':'31005'}]})]
        with tempfile.TemporaryDirectory() as base:
            root,original=self.fixture(base)
            mutations=[]
            for key,value in cases:
                row=copy.deepcopy(original);row[key]=value;mutations.append((key,row))
            for key,value in host:
                row=copy.deepcopy(original);row['HostConfig'][key]=value;mutations.append((key+str(value),row))
            row=copy.deepcopy(original);row['Config']['Labels']['gameyfin.fixture.owner']='another';mutations.append(('owner',row))
            row=copy.deepcopy(original);row['Mounts'][0]['Source']=str(Path(base)/'outside');mutations.append(('outside',row))
            row=copy.deepcopy(original);row['Mounts'][-1]['RW']=True;mutations.append(('mutable-images',row))
            row=copy.deepcopy(original);row['NetworkSettings']['Networks']['other']={};mutations.append(('extra-network',row))
            for name,row in mutations:
                with self.subTest(name=name),self.assertRaises(ValueError):validate_app_contract(row,root,OWNER,IMAGE,ID,NETWORK)

    def test_actual_owner_marker_symlink_rejected_before_read(self):
        with tempfile.TemporaryDirectory() as base:
            root,row=self.fixture(base)
            outside=Path(base)/'other-owner-marker';outside.write_text('benchmark-27-'+OWNER)
            (root/'OWNER').unlink();(root/'OWNER').symlink_to(outside)
            with self.assertRaises(ValueError):validate_app_contract(row,root,OWNER,IMAGE,ID,NETWORK)

    def test_network_membership_changed_after_create_blocks_start(self):
        with tempfile.TemporaryDirectory() as base:
            root,row=self.fixture(base);network_calls=[0];browser_id='d'*64
            browser_row={'Id':browser_id,'Name':'/gameyfin-browser-'+OWNER,'Image':IMAGE,
                'Config':{'Labels':{'gameyfin.browser.owner':OWNER},'User':'10042:10042'},
                'HostConfig':{'NetworkMode':NETWORK,'Privileged':False,'PidMode':'','IpcMode':'private','UTSMode':'','CgroupnsMode':'private','Init':True,'ReadonlyRootfs':True,'PortBindings':{},'PublishAllPorts':False,'NanoCpus':1000000000,'Memory':768*1024**2,'MemorySwap':768*1024**2,'PidsLimit':256,'CapDrop':['ALL'],'CapAdd':['CAP_SYS_ADMIN','CAP_SYS_CHROOT']},
                'NetworkSettings':{'Networks':{NETWORK:{}}},'Mounts':[]}
            def command(*args):
                if args[:2]==('network','inspect'):
                    network_calls[0]+=1
                    members={ID:{'IPv4Address':'172.22.0.2/16'}}
                    if network_calls[0]>1:members['e'*64]={'IPv4Address':'172.22.0.3/16'}
                    return json.dumps([{'Name':NETWORK,'Internal':True,'Containers':members}])
                if args[0]=='inspect':return json.dumps([browser_row if args[-1]==browser_id else row])
                if args[:2]==('image','inspect'):return json.dumps([{'Id':IMAGE}])
                if args[0]=='create':return browser_id
                raise AssertionError('Unexpected command')
            with patch.dict(os.environ,{'GITHUB_ACTIONS':'true','RUNNER_ENVIRONMENT':'github-hosted'}),patch.object(hosted_browser,'command',side_effect=command),patch.object(hosted_browser.subprocess,'Popen',return_value=Mock()),patch.object(hosted_browser,'cleanup'):
                with self.assertRaises(ValueError):hosted_browser.HostedBrowser(OWNER,IMAGE,NETWORK,'172.22.0.2',1,'f'*64,app_id=ID,app_image=IMAGE,fixture_root=root)
                self.assertEqual(network_calls[0],2)

if __name__=='__main__':unittest.main()
