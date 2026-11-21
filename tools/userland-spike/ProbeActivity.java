package dev.lusalhi.lumynoid.userlandprobe;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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
        result.setTextIsSelectable(true);
        try {
            result.setText(new String(reportFile().readFully(), StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            result.setText("Ready");
        }
        layout.addView(run);
        layout.addView(result);
        var scroll = new ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);
        run.setOnClickListener(view -> {
            run.setEnabled(false);
            result.setText("Running offline fixture…");
            new Thread(() -> {
                String report;
                try {
                    report = probe();
                } catch (IOException | InterruptedException e) {
                    report = "Probe failed: " + e.getMessage();
                }
                String text = Build.MANUFACTURER + " " + Build.MODEL + " · API " + Build.VERSION.SDK_INT + "\n" + report;
                String displayed = saveReport(text);
                runOnUiThread(() -> {
                    result.setText(displayed);
                    run.setEnabled(true);
                });
            }, "userland-probe").start();
        });
    }

    private String probe() throws IOException, InterruptedException {
        var work = Files.createTempDirectory(getCacheDir().toPath(), "userland-");
        String report;
        try {
            report = runFixture(work);
        } finally {
            try (var files = Files.walk(work)) {
                var paths = files.sorted(Comparator.reverseOrder()).iterator();
                while (paths.hasNext()) Files.delete(paths.next());
            }
        }
        return report + "\nWorkspace cleanup: PASS (temporary files removed)";
    }

    private String runFixture(Path work) throws IOException, InterruptedException {
        var archive = work.resolve("rootfs.tar.gz");
        copyAsset("rootfs.tar.gz", archive);
        var rootfs = Files.createDirectory(work.resolve("rootfs"));
        var output = work.resolve("output.txt");
        long begin = SystemClock.elapsedRealtime();
        int tar = run(new ProcessBuilder("/system/bin/toybox", "tar", "-xzf", archive.toString(),
                "-C", rootfs.toString()), output.toFile(), 60);
        long extracted = SystemClock.elapsedRealtime();
        if (tar != 0) return "Rootfs extraction: exit " + tar + "\n" + readLog(output);
        Files.delete(archive);

        boolean npm = Arrays.asList(getAssets().list("")).contains("npm-fixture.sh");
        var bin = new File(getApplicationInfo().nativeLibraryDir);
        var command = new ProcessBuilder(new File(bin, "libproot.so").getAbsolutePath(),
                "--kill-on-exit", "-r", rootfs.toString());
        command.environment().clear();
        command.environment().put("PATH", "/usr/bin:/bin");
        command.environment().put("PROOT_LOADER", new File(bin, "libproot_loader.so").getAbsolutePath());
        command.environment().put("PROOT_TMP_DIR", work.toString());
        if (npm) {
            var workspace = Files.createDirectory(work.resolve("workspace"));
            Files.createDirectory(rootfs.resolve("workspace"));
            copyAsset("npm-fixture.sh", workspace.resolve("probe.sh"));
            copyAsset("lumynoid-fixture.tgz", workspace.resolve("lumynoid-fixture.tgz"));
            command.command().addAll(Arrays.asList("-b", workspace + ":/workspace", "-b", "/dev/null",
                    "-b", "/dev/urandom", "-w", "/workspace", "/bin/sh", "/workspace/probe.sh"));
            command.environment().put("HOME", "/workspace");
            command.environment().put("TMPDIR", "/tmp");
            command.environment().put("npm_config_cache", "/workspace/.npm-cache");
            command.environment().put("npm_config_update_notifier", "false");
        } else {
            command.command().addAll(Arrays.asList("-w", "/", "/bin/cat", "/etc/alpine-release"));
        }

        long started = SystemClock.elapsedRealtime();
        int status = run(command, output.toFile(), npm ? 120 : 30);
        String log = readLog(output);
        String extraction = "Extraction: " + (extracted - begin) + " ms";
        String elapsed = (SystemClock.elapsedRealtime() - started) + " ms · exit " + status;
        if (!npm) return extraction + "\nRead: " + elapsed + "\n" + log;
        boolean passed = status == 0 && log.contains("Workspace read/write: PASS")
                && log.contains("Offline npm install: PASS") && log.contains("Node script: PASS");
        return "PRoot npm: " + (passed ? "PASS" : "FAIL") + "\n"
                + extraction + "\nRuntime: " + elapsed + "\n" + log;
    }

    private static String readLog(Path output) throws IOException {
        return new String(Files.readAllBytes(output), StandardCharsets.UTF_8).trim();
    }

    private void copyAsset(String name, Path target) throws IOException {
        try (InputStream asset = getAssets().open(name)) {
            Files.copy(asset, target);
        }
    }

    private AtomicFile reportFile() {
        return new AtomicFile(new File(getFilesDir(), "last-probe-report.txt"));
    }

    private String saveReport(String report) {
        var file = reportFile();
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(report.getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
            return report;
        } catch (IOException error) {
            if (output != null) file.failWrite(output);
            return report + "\nReport storage failed: " + error.getMessage();
        }
    }

    private static int run(ProcessBuilder command, File output, int seconds) throws IOException, InterruptedException {
        var process = command.redirectErrorStream(true).redirectOutput(output).start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) throw new IOException("Timed out after " + seconds + " seconds");
            return process.exitValue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IOException("Probe process did not stop");
            }
        }
    }
}
