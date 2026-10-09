package com.example.app;

import android.app.Activity;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Bundle;
import android.widget.TextView;
import java.io.File;

public class MainActivity extends Activity {
    int count = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        final TextView tv = new TextView(this);
        tv.setTextSize(16);
        tv.setPadding(48, 96, 48, 48);
        setContentView(tv);

        final File dir = new File(getFilesDir(), "run");
        dir.mkdirs();
        dir.setReadable(true, false);
        dir.setWritable(true, false);
        dir.setExecutable(true, false);
        final String path = dir.getAbsolutePath() + "/wayland-0";
        tv.setText("Socket:\n" + path + "\n\nMenunggu koneksi...");

        new Thread(() -> {
            try {
                new File(path).delete();
                LocalSocket s = new LocalSocket(LocalSocket.SOCKET_STREAM);
                s.bind(new LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM));
                new File(path).setReadable(true, false);
                new File(path).setWritable(true, false);
                LocalServerSocket server = new LocalServerSocket(s.getFileDescriptor());
                while (true) {
                    LocalSocket c = server.accept();
                    count++;
                    final int n = count;
                    runOnUiThread(() -> tv.setText("Socket:\n" + path
                            + "\n\nKoneksi masuk: " + n));
                    c.close();
                }
            } catch (Exception e) {
                runOnUiThread(() -> tv.setText("Error: " + e));
            }
        }).start();
    }
}