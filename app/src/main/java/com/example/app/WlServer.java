package com.example.app;

import android.graphics.Bitmap;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.system.Os;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.util.*;

public class WlServer {
    public interface Sink { void frame(Bitmap b); void log(String s); }

    final Sink sink;
    public WlServer(Sink s) { sink = s; }

    LocalSocket keep;
    LocalServerSocket keepSrv;

    public void start(final String path) {
        new Thread(() -> {
            try {
                new File(path).delete();
                keep = new LocalSocket(LocalSocket.SOCKET_STREAM);
                keep.bind(new LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM));
                Os.chmod(path, 0777);
                keepSrv = new LocalServerSocket(keep.getFileDescriptor());
                final LocalServerSocket srv = keepSrv;
                sink.log("Siap: " + path);
                while (true) {
                    final LocalSocket c = srv.accept();
                    new Thread(() -> {
                        try { new Client(c).run(); }
                        catch (Exception e) { sink.log("Klien putus: " + e); }
                    }).start();
                }
            } catch (Exception e) { sink.log("Error: " + e); }
        }).start();
    }

    class Pool {
        FileInputStream f; MappedByteBuffer m;
        Pool(FileDescriptor fd, int size) throws IOException { f = new FileInputStream(fd); remap(size); }
        void remap(int size) throws IOException { m = f.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, size); }
    }

    class Buf { Pool p; int off, w, h, stride; }

    class Client {
        final LocalSocket s; final InputStream in; final OutputStream out;
        final Deque<FileDescriptor> fds = new ArrayDeque<>();
        final Map<Integer, String> type = new HashMap<>();
        final Map<Integer, Pool> pools = new HashMap<>();
        final Map<Integer, Buf> bufs = new HashMap<>();
        final Map<Integer, Integer> pending = new HashMap<>();
        final Map<Integer, List<Integer>> frames = new HashMap<>();
        int serial = 1;

        Client(LocalSocket s) throws IOException { this.s = s; in = s.getInputStream(); out = s.getOutputStream(); }

        void readFully(byte[] b, int n) throws IOException {
            int o = 0;
            while (o < n) {
                int r = in.read(b, o, n - o);
                if (r < 0) throw new EOFException();
                o += r;
                FileDescriptor[] a = s.getAncillaryFileDescriptors();
                if (a != null) for (FileDescriptor d : a) fds.add(d);
            }
        }

        void run() throws Exception {
            type.put(1, "wl_display");
            byte[] h = new byte[8];
            while (true) {
                readFully(h, 8);
                ByteBuffer hb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
                int obj = hb.getInt();
                int w2 = hb.getInt();
                int op = w2 & 0xffff, size = w2 >>> 16;
                byte[] body = new byte[size - 8];
                if (body.length > 0) readFully(body, body.length);
                handle(obj, op, ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN));
            }
        }

        String str(ByteBuffer b) {
            int n = b.getInt();
            byte[] t = new byte[n];
            b.get(t);
            b.position((b.position() + 3) & ~3);
            return new String(t, 0, Math.max(0, n - 1));
        }

        byte[] ints(int... v) {
            ByteBuffer b = ByteBuffer.allocate(4 * v.length).order(ByteOrder.LITTLE_ENDIAN);
            for (int x : v) b.putInt(x);
            return b.array();
        }

        void send(int obj, int op, byte[] body) throws IOException {
            ByteBuffer b = ByteBuffer.allocate(8 + body.length).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(obj);
            b.putInt(((8 + body.length) << 16) | op);
            b.put(body);
            synchronized (out) { out.write(b.array()); out.flush(); }
        }

        void global(int reg, int name, String i, int ver) throws IOException {
            byte[] sb = i.getBytes();
            int len = sb.length + 1, pad = (len + 3) & ~3;
            ByteBuffer b = ByteBuffer.allocate(12 + pad).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(name); b.putInt(len); b.put(sb);
            b.position(8 + pad); b.putInt(ver);
            send(reg, 0, b.array());
        }

        void handle(int obj, int op, ByteBuffer b) throws Exception {
            String t = type.get(obj);
            if (t == null) return;
            switch (t) {
                case "wl_display": {
                    if (op == 0) {
                        int cb = b.getInt();
                        send(cb, 0, ints(serial++));
                        send(1, 1, ints(cb));
                    } else if (op == 1) {
                        int r = b.getInt();
                        type.put(r, "wl_registry");
                        global(r, 1, "wl_compositor", 4);
                        global(r, 2, "wl_shm", 1);
                        global(r, 3, "xdg_wm_base", 1);
                    }
                    break;
                }
                case "wl_registry": {
                    if (op == 0) {
                        b.getInt();
                        String i = str(b);
                        b.getInt();
                        int id = b.getInt();
                        type.put(id, i);
                        if (i.equals("wl_shm")) { send(id, 0, ints(0)); send(id, 0, ints(1)); }
                    }
                    break;
                }
                case "wl_compositor": {
                    int id = b.getInt();
                    if (op == 0) { type.put(id, "wl_surface"); } else { type.put(id, "wl_region"); }
                    break;
                }
                case "wl_shm": {
                    if (op == 0) {
                        int id = b.getInt();
                        int size = b.getInt();
                        FileDescriptor fd = fds.poll();
                        pools.put(id, new Pool(fd, size));
                        type.put(id, "wl_shm_pool");
                    }
                    break;
                }
                case "wl_shm_pool": {
                    if (op == 0) {
                        int id = b.getInt();
                        Buf bf = new Buf();
                        bf.p = pools.get(obj);
                        bf.off = b.getInt(); bf.w = b.getInt(); bf.h = b.getInt(); bf.stride = b.getInt();
                        bufs.put(id, bf);
                        type.put(id, "wl_buffer");
                    } else if (op == 2) {
                        pools.get(obj).remap(b.getInt());
                    }
                    break;
                }
                case "wl_buffer": {
                    if (op == 0) bufs.remove(obj);
                    break;
                }
                case "wl_surface": {
                    if (op == 1) {
                        pending.put(obj, b.getInt());
                    } else if (op == 3) {
                        int id = b.getInt();
                        type.put(id, "wl_callback");
                        List<Integer> l = frames.get(obj);
                        if (l == null) { l = new ArrayList<>(); frames.put(obj, l); }
                        l.add(id);
                    } else if (op == 6) {
                        commit(obj);
                    }
                    break;
                }
                case "xdg_wm_base": {
                    if (op == 1) { type.put(b.getInt(), "xdg_positioner"); }
                    else if (op == 2) { type.put(b.getInt(), "xdg_surface"); }
                    break;
                }
                case "xdg_surface": {
                    if (op == 1) {
                        int id = b.getInt();
                        type.put(id, "xdg_toplevel");
                        send(id, 0, ints(0, 0, 0));
                        send(obj, 0, ints(serial++));
                    } else if (op == 2) {
                        type.put(b.getInt(), "xdg_popup");
                    }
                    break;
                }
                default:
                    break;
            }
        }

        void commit(int sid) throws IOException {
            Integer bid = pending.get(sid);
            if (bid != null && bid != 0) {
                Buf bf = bufs.get(bid);
                if (bf != null) { draw(bf); send(bid, 0, new byte[0]); }
                pending.put(sid, 0);
            }
            List<Integer> l = frames.get(sid);
            if (l != null) {
                for (int cb : l) {
                    send(cb, 0, ints((int) System.currentTimeMillis()));
                    send(1, 1, ints(cb));
                }
                l.clear();
            }
        }

        void draw(Buf bf) {
            ByteBuffer m = bf.p.m.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            int[] px = new int[bf.w * bf.h];
            for (int y = 0; y < bf.h; y++)
                for (int x = 0; x < bf.w; x++)
                    px[y * bf.w + x] = m.getInt(bf.off + y * bf.stride + x * 4) | 0xFF000000;
            sink.frame(Bitmap.createBitmap(px, bf.w, bf.h, Bitmap.Config.ARGB_8888));
        }
    }
}
