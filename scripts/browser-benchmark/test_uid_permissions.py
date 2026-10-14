"""Root-only actual Linux UID proof using generated /tmp data, no Docker.

An inherited directory FD models the app's bind-mount view: host ROOT700 is not
in the app namespace. It does NOT claim actual container/Java integration.
"""
import os,shutil,signal,tempfile,time,unittest
from pathlib import Path

@unittest.skipUnless(os.name=='posix' and os.geteuid()==0,'Actual different-UID proof requires root on disposable local/CI fixture')
class UidPermissionTest(unittest.TestCase):
    def child(self,uid,action):
        pid=os.fork()
        if pid==0:
            try:os.setgroups([]);os.setgid(uid);os.setuid(uid);action();os._exit(0)
            except Exception:os._exit(1)
        return pid
    def wait_ok(self,pid):
        _,status=os.waitpid(pid,0);self.assertEqual(os.waitstatus_to_exitcode(status),0)
    def test_1337_app_1001_controller_shared_leaf_under_private_host_root(self):
        import sys
        sys.path.insert(0,str(Path(__file__).with_name('overlap')))
        from waiting_overlap import run_waiting_overlap
        with tempfile.TemporaryDirectory(prefix='gameyfin-uid-proof-') as name:
            root=Path(name);root.chmod(0o700);os.chown(root,1001,1001)
            gate=root/'gameyfin-overlap-fixture-0000000000000001';gate.mkdir();gate.chmod(0o777);os.chown(gate,1337,1337)
            fd=os.open(gate,os.O_RDONLY|os.O_DIRECTORY);go_read,go_write=os.pipe()
            def app():
                os.close(go_write);os.read(go_read,1)
                def marker(name):
                    pending=name+'.pending';f=os.open(pending,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o644,dir_fd=fd)
                    try:os.fchmod(f,0o644);os.write(f,b'waiting-provider-v1\n'+str(time.monotonic_ns()).encode()+b'\n')
                    finally:os.close(f)
                    os.link(pending,name,src_dir_fd=fd,dst_dir_fd=fd,follow_symlinks=False);os.unlink(pending,dir_fd=fd)
                marker('entered');deadline=time.monotonic()+3
                while True:
                    try:
                        release=os.open('release',os.O_RDONLY|os.O_NOFOLLOW,dir_fd=fd)
                        try:
                            if os.read(release,9)!=b'release\n':raise ValueError('Release shape')
                        finally:os.close(release)
                        break
                    except FileNotFoundError:
                        if time.monotonic()>=deadline:raise TimeoutError()
                        time.sleep(.005)
                marker('released');marker('exited')
            app_pid=self.child(1337,app)
            def controller():
                os.close(go_read)
                proof=run_waiting_overlap(gate,lambda:os.write(go_write,b'x'),lambda:{'testDoubleOnly':True})
                if not proof['workerLifetimeOverlapVerified']:raise ValueError('No overlap')
                if (gate/'release').stat().st_uid!=1001:raise ValueError('Wrong marker writer')
                if (gate/'entered').stat().st_uid!=1337:raise ValueError('Wrong receipt writer')
            controller_pid=self.child(1001,controller)
            pending={app_pid,controller_pid}
            try:
                pending.remove(controller_pid);self.wait_ok(controller_pid)
                pending.remove(app_pid);self.wait_ok(app_pid)
            finally:
                for pid in pending:
                    try:os.kill(pid,signal.SIGTERM)
                    except ProcessLookupError:pass
                    os.waitpid(pid,0)
                os.close(fd);os.close(go_read);os.close(go_write)
            self.assertEqual(root.stat().st_mode&0o777,0o700)
            self.assertEqual((gate/'release').stat().st_mode&0o777,0o644)
    def test_private_app_files_need_ownership_restore_before_runner_delete(self):
        with tempfile.TemporaryDirectory(prefix='gameyfin-cleanup-uid-proof-') as name:
            root=Path(name);root.chmod(0o700);os.chown(root,1001,1001)
            db=root/'db';db.mkdir(mode=0o755);os.chown(db,1337,1337)
            private=db/'private';private.mkdir(mode=0o700);os.chown(private,1337,1337)
            file=private/'synthetic.db';file.write_bytes(b'fixture-only');file.chmod(0o600);os.chown(file,1337,1337)
            def cannot_delete():
                try:shutil.rmtree(db)
                except PermissionError:return
                raise ValueError('Unexpected permission bypass')
            self.wait_ok(self.child(1001,cannot_delete))
            for path in (file,private,db):os.chown(path,1001,1001,follow_symlinks=False)
            self.wait_ok(self.child(1001,lambda:shutil.rmtree(db)))
            self.assertFalse(db.exists())
if __name__=='__main__':unittest.main()
