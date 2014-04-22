package org.dcache.simplenfs;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.primitives.Longs;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.dcache.chimera.UnixPermission;
import org.dcache.nfs.ChimeraNFSException;
import org.dcache.nfs.nfsstat;
import org.dcache.nfs.v4.xdr.nfsace4;
import org.dcache.nfs.vfs.AclCheckable;
import org.dcache.nfs.vfs.DirectoryEntry;
import org.dcache.nfs.vfs.FsStat;
import org.dcache.nfs.vfs.Inode;
import org.dcache.nfs.vfs.Stat;
import org.dcache.nfs.vfs.Stat.Type;
import org.dcache.nfs.vfs.VirtualFileSystem;

/**
 *
 */
public class LocalFileSystem implements VirtualFileSystem {

    private final Path _root;
    private final BiMap<Path, Long> _id_cache = HashBiMap.create();
    private final AtomicLong fileId = new AtomicLong();

    private long getOrCreateId(Path path) {
        Long id = _id_cache.get(path);
        if (id == null) {
            id = fileId.getAndIncrement();
            _id_cache.put(path, id);
        }
        return id;
    }

    private Inode toFh(long id) {
        return Inode.forFile(Longs.toByteArray(id));
    }

    private Long toId(Inode inode) {
        return Longs.fromByteArray(inode.getFileId());
    }

    private Path resolve(Inode inode) {
        return _id_cache.inverse().get(toId(inode));
    }

    public LocalFileSystem(File root) {
        _root = root.toPath();
        _id_cache.put(_root, fileId.getAndIncrement());
    }

    @Override
    public Inode create(Inode parent, Type type, String path, int uid, int gid, int mode) throws IOException {
        long parentId = Longs.fromByteArray(parent.getFileId());
        Path parentPath = _id_cache.inverse().get(parentId);
        Path newPath = parentPath.resolve(path);
        Files.createFile(newPath);
        long newId = fileId.getAndIncrement();
        _id_cache.put(newPath, newId);
        return toFh(newId);
    }

    @Override
    public FsStat getFsStat() throws IOException {

        File fileStore = _root.toFile();
        long totalSpace = fileStore.getTotalSpace();
        long avail = fileStore.getUsableSpace();
        long totalFiles = 0;
        long usedFiles = 0;

        return new FsStat(totalSpace, totalFiles, totalSpace - avail, usedFiles);
    }

    @Override
    public Inode getRootInode() throws IOException {
        Long id = _id_cache.get(_root);
        return toFh(id);
    }

    @Override
    public Inode lookup(Inode parent, String path) throws IOException {

        Path parentPath = resolve(parent);

        Path element = parentPath.resolve(path);
        if (!Files.exists(element)) {
            throw new ChimeraNFSException(nfsstat.NFSERR_NOENT, element.toString());
        }

        long id = getOrCreateId(element);
        return toFh(id);
    }

    @Override
    public Inode link(Inode parent, Inode link, String path, int uid, int gid) throws IOException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public List<DirectoryEntry> list(Inode inode) throws IOException {
        Path path = resolve(inode);
        DirectoryStream<Path> directoryStream = Files.newDirectoryStream(path);
        List<DirectoryEntry> list = new ArrayList<>();
        for (Path p : directoryStream) {
            list.add(new DirectoryEntry(p.getFileName().toString(), lookup(inode, p.toString()), statPath(p)));
        }
        return list;
    }

    @Override
    public Inode mkdir(Inode parent, String path, int uid, int gid, int mode) throws IOException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public boolean move(Inode src, String oldName, Inode dest, String newName) throws IOException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public Inode parentOf(Inode inode) throws IOException {
        Path path = resolve(inode);
        if (path.equals(_root)) {
            throw new ChimeraNFSException(nfsstat.NFSERR_NOENT, "no parent");
        }
        Path parent = path.getParent();
        long id = getOrCreateId(parent);
        return toFh(id);
    }

    @Override
    public int read(Inode inode, byte[] data, long offset, int count) throws IOException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public String readlink(Inode inode) throws IOException {
        Path path = resolve(inode);
        return Files.readSymbolicLink(path).toString();
    }

    @Override
    public void remove(Inode parent, String path) throws IOException {
        Path parentPath = resolve(parent);
        Files.delete(parentPath.resolve(path));
    }

    @Override
    public Inode symlink(Inode parent, String path, String link, int uid, int gid, int mode) throws IOException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public int write(Inode inode, byte[] data, long offset, int count) throws IOException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    private Stat statPath(Path p) throws IOException {
        PosixFileAttributes attrs = Files.getFileAttributeView(p, PosixFileAttributeView.class).readAttributes();

        Stat stat = new Stat();

        stat.setATime(attrs.lastAccessTime().toMillis());
        stat.setCTime(attrs.lastModifiedTime().toMillis());
        stat.setMTime(attrs.lastModifiedTime().toMillis());

        // FIXME
        stat.setGid(0);
        stat.setUid(0);

        stat.setDev(17);
        stat.setIno(attrs.fileKey().hashCode());
        stat.setMode(toUnixMode(attrs));
        stat.setNlink(1);
        stat.setRdev(17);
        stat.setSize(attrs.size());
        stat.setFileid(attrs.fileKey().hashCode());
        stat.setGeneration(attrs.lastModifiedTime().toMillis());

        return stat;
    }

    private int toUnixMode(PosixFileAttributes attributes) {
        int mode = 0;
        if (attributes.isDirectory()) {
            mode |= Stat.S_IFDIR;
        } else if (attributes.isRegularFile()) {
            mode |= Stat.S_IFREG;
        } else if (attributes.isSymbolicLink()) {
            mode |= Stat.S_IFLNK;
        } else {
            mode |= Stat.S_IFSOCK;
        }

        for(PosixFilePermission perm: attributes.permissions()) {
            switch(perm) {
                case GROUP_EXECUTE:  mode |= UnixPermission.S_IXGRP;  break;
                case GROUP_READ:     mode |= UnixPermission.S_IRGRP;  break;
                case GROUP_WRITE:    mode |= UnixPermission.S_IWGRP;  break;
                case OTHERS_EXECUTE: mode |= UnixPermission.S_IXOTH;  break;
                case OTHERS_READ:    mode |= UnixPermission.S_IROTH;  break;
                case OTHERS_WRITE:   mode |= UnixPermission.S_IWOTH;  break;
                case OWNER_EXECUTE:  mode |= UnixPermission.S_IXUSR;  break;
                case OWNER_READ:     mode |= UnixPermission.S_IRUSR;  break;
                case OWNER_WRITE:    mode |= UnixPermission.S_IWUSR;  break;
            }
        }
        return mode;
    }

    @Override
    public int access(Inode inode, int mode) throws IOException {
        return mode;
    }

    @Override
    public Stat getattr(Inode inode) throws IOException {
        Path path = resolve(inode);
        return statPath(path);
    }

    @Override
    public void setattr(Inode inode, Stat stat) throws IOException {
        // NOP
    }

    @Override
    public nfsace4[] getAcl(Inode inode) throws IOException {
        return new nfsace4[0];
    }

    @Override
    public void setAcl(Inode inode, nfsace4[] acl) throws IOException {
        // NOP
    }

    @Override
    public boolean hasIOLayout(Inode inode) throws IOException {
        return false;
    }

    @Override
    public AclCheckable getAclCheckable() {
        return AclCheckable.UNDEFINED_ALL;
    }
}