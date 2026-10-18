package com.christopher.strata;

import java.io.*;

/** Checked filesystem operations for recovery copies; never publish a partial project. */
final class ProjectFiles {
    static File unique(File parent, String prefix) throws IOException {
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Can't create recovery folder");
        for (int i = 0; i < 10000; i++) {
            File f = new File(parent, prefix + System.currentTimeMillis() + "_" + i);
            if (!f.exists() && !new File(parent,"."+f.getName()).exists()) return f;
        }
        throw new IOException("Can't allocate a recovery copy");
    }

    static void copy(File src, File dst) throws IOException {
        if (!dst.mkdirs() && !dst.isDirectory()) throw new IOException("Can't create " + dst.getName());
        File[] files = src.listFiles();
        if (files == null) throw new IOException("Can't read " + src.getName());
        for (File f : files) {
            String n = f.getName();
            // Each version is independent. Never recursively copy its version bank or an unfinished save.
            if (n.equals(".versions") || n.equals(".version.properties") || n.equals(".trash.properties")
                    || n.endsWith(".tmp") || n.endsWith(".bak")) continue;
            if (!f.getCanonicalFile().getParentFile().equals(src.getCanonicalFile()))
                throw new IOException("Linked files cannot be copied into a recovery version");
            File target = new File(dst, n);
            if (f.isDirectory()) copy(f, target);
            else try (InputStream in = new FileInputStream(f); FileOutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[65536]; int count;
                while ((count = in.read(buf)) != -1) out.write(buf, 0, count);
                out.getFD().sync();
            }
        }
    }

    static boolean remove(File f) {
        if (java.nio.file.Files.isSymbolicLink(f.toPath())) return f.delete();
        if (!f.exists()) return true;
        File[] files = f.listFiles();
        if (f.isDirectory() && files == null) return false;
        if (files != null) for (File child : files) if (!remove(child)) return false;
        return f.delete();
    }
}
