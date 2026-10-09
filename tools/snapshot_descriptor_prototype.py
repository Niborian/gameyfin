"""Linux-only synthetic acquisition prototype. NOT an application/provider interface.

Caller supplies already trusted source/cache directory descriptors and host-captured
member fingerprints. No CLI accepts arbitrary roots. No directory enumeration or
publication is implemented. Successful trees remain private operation directories.
"""
import ctypes
import errno
import hashlib
import os
import stat
import sys
import uuid


class OpenHow(ctypes.Structure):
    _fields_ = [("flags", ctypes.c_uint64), ("mode", ctypes.c_uint64),
                ("resolve", ctypes.c_uint64)]


def fingerprint(value):
    return (value.st_dev, value.st_ino, stat.S_IFMT(value.st_mode), value.st_size,
            value.st_mtime_ns, value.st_ctime_ns)


def open_beneath(root_fd, relative):
    if not sys.platform.startswith("linux") or os.uname().machine not in ("x86_64", "aarch64"):
        raise OSError(errno.ENOSYS, "unsupported descriptor backend")
    if not relative or "\x00" in relative or relative.startswith("/") or any(p in ("", ".", "..") for p in relative.split("/")):
        raise ValueError("invalid relative member")
    # Linux x86_64/aarch64 SYS_openat2=437. Fail closed on syscall/flag errors.
    how = OpenHow(os.O_RDONLY | os.O_CLOEXEC | os.O_NONBLOCK, 0, 0x08 | 0x04 | 0x02 | 0x01)
    libc = ctypes.CDLL(None, use_errno=True)
    libc.syscall.restype = ctypes.c_long
    result = libc.syscall(ctypes.c_long(437), ctypes.c_int(root_fd),
                          ctypes.c_char_p(os.fsencode(relative)), ctypes.byref(how),
                          ctypes.c_size_t(ctypes.sizeof(how)))
    if result < 0:
        failure = ctypes.get_errno()
        raise OSError(failure, "descriptor acquisition refused")
    return result


def require_private_cache(cache_fd):
    value = os.fstat(cache_fd)
    if not stat.S_ISDIR(value.st_mode) or value.st_uid != os.geteuid() or stat.S_IMODE(value.st_mode) != 0o700:
        raise ValueError("cache descriptor must be service-owned mode 0700")


def copy_members(source_fd, cache_fd, members, max_bytes, max_files, cancelled=lambda: False,
                 after_chunk=lambda: None, before_open=lambda: None, after_open=lambda: None):
    """Copy a bounded explicit flat output manifest; identity/ancestry authority is external.

    members: [(source-relative path, generated single-component output, expected fingerprint)]
    Hooks exist only for deterministic synthetic adversarial tests.
    """
    require_private_cache(cache_fd)
    if max_bytes < 0 or max_files < 0 or len(members) > max_files:
        raise ValueError("quota")
    names = [member[1] for member in members]
    if len(set(names)) != len(names) or any(not n or "\x00" in n or n in (".", "..") or "/" in n or "\\" in n for n in names):
        raise ValueError("unsafe output names")
    operation = "copy-" + uuid.uuid4().hex
    os.mkdir(operation, mode=0o700, dir_fd=cache_fd)
    # Capture identity before opening so an open failure cannot justify deleting an
    # unknown replacement. Same-UID/root mutation remains outside the trust boundary.
    try:
        created_directory = os.stat(operation, dir_fd=cache_fd, follow_symlinks=False)
    except OSError:
        raise RuntimeError("uncertain created operation identity") from None
    created_identity = (created_directory.st_dev, created_directory.st_ino)
    try:
        operation_fd = os.open(operation, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=cache_fd)
    except BaseException:
        try:
            current = os.stat(operation, dir_fd=cache_fd, follow_symlinks=False)
            if (current.st_dev, current.st_ino) != created_identity:
                raise RuntimeError("uncertain operation identity after open failure")
            os.rmdir(operation, dir_fd=cache_fd)
        except OSError:
            raise RuntimeError("uncertain operation cleanup after open failure") from None
        raise
    try:
        operation_identity = fingerprint(os.fstat(operation_fd))[:2]
    except OSError:
        os.close(operation_fd)
        raise RuntimeError("uncertain opened operation identity") from None
    if operation_identity != created_identity:
        os.close(operation_fd)
        raise RuntimeError("uncertain opened operation identity")
    created = []
    total = 0
    manifest = []
    try:
        for relative, output, expected in members:
            if cancelled():
                raise InterruptedError("cancelled")
            before_open()
            source = open_beneath(source_fd, relative)
            try:
                after_open()
                opened = os.fstat(source)
                if not stat.S_ISREG(opened.st_mode) or opened.st_nlink != 1 or fingerprint(opened) != tuple(expected):
                    raise ValueError("opened identity mismatch or unsupported file")
                destination = os.open(output, os.O_RDWR | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                                      0o600, dir_fd=operation_fd)
                created.append(output)
                try:
                    copied = hashlib.sha256()
                    size = 0
                    while True:
                        if cancelled():
                            raise InterruptedError("cancelled")
                        chunk = os.read(source, 65536)
                        if not chunk:
                            break
                        total += len(chunk)
                        size += len(chunk)
                        if total > max_bytes:
                            raise ValueError("byte quota")
                        copied.update(chunk)
                        view = memoryview(chunk)
                        while view:
                            written = os.write(destination, view)
                            if written <= 0:
                                raise OSError("short write")
                            view = view[written:]
                        after_chunk()
                    os.fsync(destination)
                    if size != expected[3]:
                        raise ValueError("source size differed from authorized fingerprint")
                    os.lseek(destination, 0, os.SEEK_SET)
                    destination_hash = hashlib.sha256()
                    destination_size = 0
                    while True:
                        if cancelled():
                            raise InterruptedError("cancelled")
                        chunk = os.read(destination, 65536)
                        if not chunk:
                            break
                        destination_size += len(chunk)
                        if destination_size > size:
                            raise ValueError("destination grew during verification")
                        destination_hash.update(chunk)
                    if destination_size != size or destination_hash.digest() != copied.digest():
                        raise ValueError("destination verification mismatch")
                finally:
                    os.close(destination)
                if fingerprint(os.fstat(source)) != tuple(expected):
                    raise ValueError("source changed during copy")
                os.lseek(source, 0, os.SEEK_SET)
                repeated = hashlib.sha256()
                repeated_size = 0
                while True:
                    if cancelled():
                        raise InterruptedError("cancelled")
                    chunk = os.read(source, 65536)
                    if not chunk:
                        break
                    repeated_size += len(chunk)
                    if repeated_size > size:
                        raise ValueError("source grew during verification")
                    repeated.update(chunk)
                if (fingerprint(os.fstat(source)) != tuple(expected) or repeated_size != size
                        or repeated.digest() != copied.digest()):
                    raise ValueError("unstable source")
                manifest.append((output, size, copied.hexdigest()))
            finally:
                os.close(source)
        os.fsync(operation_fd)
        named = os.stat(operation, dir_fd=cache_fd, follow_symlinks=False)
        if (named.st_dev, named.st_ino) != operation_identity:
            raise RuntimeError("uncertain operation identity before return")
        return operation, manifest
    except BaseException:
        # Descriptor-relative files only; refuse to remove a substituted operation name.
        for output in created:
            os.unlink(output, dir_fd=operation_fd)
        try:
            actual = os.stat(operation, dir_fd=cache_fd, follow_symlinks=False)
        except FileNotFoundError:
            raise RuntimeError("uncertain missing operation name") from None
        if (actual.st_dev, actual.st_ino) != operation_identity:
            raise RuntimeError("uncertain operation name; foreign entry preserved")
        os.rmdir(operation, dir_fd=cache_fd)
        raise
    finally:
        os.close(operation_fd)
