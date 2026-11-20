package dev.lusalhi.lumynoid.userlandprobe;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

public final class ProbeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        var layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setFitsSystemWindows(true);
        var run = new Button(this);
        run.setText("Run offline userland probe");
        var result = new TextView(this);
        layout.addView(run);
        layout.addView(result);
        setContentView(layout);
        run.setOnClickListener(view -> {
            run.setEnabled(false);
            result.setText("Running fixed read fixture…");
            new Thread(() -> {
                String report;
                try {
                    report = probe();
                } catch (IOException | InterruptedException e) {
                    report = "Probe failed: " + e.getMessage();
                }
                String text = Build.MANUFACTURER + " " + Build.MODEL + " · API " + Build.VERSION.SDK_INT + "\n" + report;
                runOnUiThread(() -> {
                    result.setText(text);
                    run.setEnabled(true);
                });
            }, "userland-probe").start();
        });
    }

    private String probe() throws IOException, InterruptedException {
        var work = Files.createTempDirectory(getCacheDir().toPath(), "userland-");
        try {
            var archive = work.resolve("rootfs.tar.gz");
            try (InputStream asset = getAssets().open("rootfs.tar.gz")) {
                Files.copy(asset, archive);
            }
            var rootfs = Files.createDirectory(work.resolve("rootfs"));
            var output = work.resolve("output.txt");
            long begin = SystemClock.elapsedRealtime();
            int tar = run(new ProcessBuilder("/system/bin/toybox", "tar", "-xzf", archive.toString(),
                    "-C", rootfs.toString()), output.toFile());
            long extracted = SystemClock.elapsedRealtime();
            if (tar != 0) return "Rootfs extraction: exit " + tar + "\n"
                    + new String(Files.readAllBytes(output), StandardCharsets.UTF_8);

            var bin = new File(getApplicationInfo().nativeLibraryDir);
            var command = new ProcessBuilder(new File(bin, "libproot.so").getAbsolutePath(),
                    "--kill-on-exit", "-r", rootfs.toString(), "-w", "/", "/bin/cat", "/etc/alpine-release");
            command.environment().clear();
            command.environment().put("PATH", "/usr/bin:/bin");
            command.environment().put("PROOT_LOADER", new File(bin, "libproot_loader.so").getAbsolutePath());
            command.environment().put("PROOT_TMP_DIR", work.toString());
            int status = run(command, output.toFile());
            return "Extraction: " + (extracted - begin) + " ms\nRead: " + (SystemClock.elapsedRealtime() - extracted)
                    + " ms · exit " + status + "\n" + new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
        } finally {
            try (var files = Files.walk(work)) {
                var paths = files.sorted(Comparator.reverseOrder()).iterator();
                while (paths.hasNext()) Files.delete(paths.next());
            }
        }
    }

    private static int run(ProcessBuilder command, File output) throws IOException, InterruptedException {
        var process = command.redirectErrorStream(true).redirectOutput(output).start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(30, TimeUnit.SECONDS)) throw new IOException("Timed out after 30 seconds");
            return process.exitValue();
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
