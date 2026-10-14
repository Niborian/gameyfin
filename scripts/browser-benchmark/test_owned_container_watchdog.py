import copy
import json
import unittest
from owned_container_watchdog import cleanup, validate

ID = "a" * 64
OWNER = "b" * 16
IMAGE = "sha256:" + "c" * 64


def fixture():
    return {"Id": ID, "Name": "/gameyfin-browser-" + OWNER, "Image": IMAGE,
            "Config": {"Labels": {"gameyfin.browser.owner": OWNER},'User':'10042:10042'},
            'NetworkSettings':{'Networks':{'gameyfin-browser-net-'+OWNER:{}}},'Mounts':[],
            "HostConfig": {"NetworkMode": "gameyfin-browser-net-" + OWNER,
                           "Privileged": False, "ReadonlyRootfs": True,'CgroupnsMode':'private','Init':True,'PidsLimit':256,'NanoCpus':1000000000,'Memory':768*1024*1024,'MemorySwap':768*1024*1024,'CapDrop':['ALL'],'CapAdd':['SYS_ADMIN','SYS_CHROOT']}}


class WatchdogTests(unittest.TestCase):
    def test_exact_cleanup(self):
        calls = []
        def command(*args):
            calls.append(args)
            return json.dumps([fixture()]) if args[0] == "inspect" else ""
        cleanup(ID, OWNER, IMAGE, command)
        self.assertEqual(calls, [("inspect", ID), ("rm", "--force", ID)])

    def test_reject_reused_name_or_labels(self):
        for key, value in [("Id", "d" * 64), ("Name", "/other"), ("Image", "sha256:" + "e" * 64)]:
            row = fixture(); row[key] = value
            with self.assertRaises(ValueError): validate(row, ID, OWNER, IMAGE)

    def test_reject_namespace_sharing(self):
        for mode in ["host", "bridge", "container:" + ID]:
            row = fixture(); row["HostConfig"]["NetworkMode"] = mode
            with self.assertRaises(ValueError): validate(row, ID, OWNER, IMAGE)
    def test_browser_pid_budget_is_exact_not_unbounded(self):
        for limit in (128,512,-1,None):
            row=fixture();row['HostConfig']['PidsLimit']=limit
            with self.assertRaises(ValueError):validate(row,ID,OWNER,IMAGE)

    def test_failure_never_removes(self):
        calls = []
        def command(*args):
            calls.append(args)
            row = fixture(); row["Id"] = "f" * 64
            return json.dumps([row])
        with self.assertRaises(ValueError): cleanup(ID, OWNER, IMAGE, command)
        self.assertEqual(calls, [("inspect", ID)])

    def test_canonical_docker28_cap_does_not_change_accepted_grant(self):
        for caps in [['CAP_SYS_ADMIN','CAP_SYS_CHROOT'],['SYS_CHROOT','CAP_SYS_ADMIN']]:
            row=fixture();row['HostConfig']['CapAdd']=caps
            self.assertEqual(validate(row,ID,OWNER,IMAGE),ID)
        for caps in [['CAP_SYS_ADMIN'],['CAP_SYS_CHROOT'],['CAP_SYS_ADMIN','CAP_SYS_CHROOT','CAP_NET_ADMIN'],['ALL'],['SYS_ADMIN','CAP_SYS_ADMIN'],['SYS_ADMIN','SYS_CHROOT','CAP_SYS_CHROOT']]:
            row=fixture();row['HostConfig']['CapAdd']=caps
            with self.assertRaises(ValueError):validate(row,ID,OWNER,IMAGE)

    def test_owned_scope_cleanup_survives_numeric_acceptance_failure(self):
        row=fixture();row['HostConfig']['Memory']=2*1024**3
        with self.assertRaises(ValueError):validate(row,ID,OWNER,IMAGE)
        calls=[]
        def command(*args):
            calls.append(args)
            return json.dumps([row]) if args[0]=='inspect' else ''
        cleanup(ID,OWNER,IMAGE,command)
        self.assertEqual(calls,[('inspect',ID),('rm','--force',ID)])


if __name__ == "__main__": unittest.main()
