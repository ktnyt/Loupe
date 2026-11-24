package jp.nano.rawgallery.camera;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public final class SdRemountUsbRestoreTest {
    private static final String FAKE_DUMPSYS = """
            if [ "$1" = "off" ]; then
                printf 'off-start\\n' >> "$2"
                count=0
                while [ ! -f "$2.release" ] && [ "$count" -lt 250 ]; do
                    /bin/sleep 0.02
                    count=$((count + 1))
                done
                printf 'off-end\\n' >> "$2"
            else
                printf 'on\\n' >> "$2"
            fi
            """;
    private static final String EXPECTED = "off-start\noff-end\non\n";

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && "--owner".equals(args[0])) {
            Process cycle = launchCycle(Path.of(args[1]), Path.of(args[2]));
            try (var commands = cycle.getOutputStream(); var output = output(cycle)) {
                arm(commands, output);
                signal(commands, "cycle");
                System.in.read();
            }
            return;
        }

        Path journal = Files.createTempFile("usb-cycle", ".txt");
        Path release = Path.of(journal + ".release");
        Path fake = Files.createTempFile("usb-cycle-dumpsys", ".sh");
        try {
            Files.writeString(fake, FAKE_DUMPSYS);
            unstartedCycleExitsWithoutWriting(journal, fake);
            startedCycleReportsBothCompleted(journal, release, fake);
            cycleSurvivesKilledOwner(journal, release, fake);
            System.out.println("SdRemountUsbRestoreTest passed");
        } finally {
            Files.deleteIfExists(release);
            Files.deleteIfExists(journal);
            Files.deleteIfExists(fake);
        }
    }

    private static void unstartedCycleExitsWithoutWriting(Path journal, Path fake) throws Exception {
        Process cycle = launchCycle(journal, fake);
        try (var commands = cycle.getOutputStream(); var output = output(cycle)) {
            arm(commands, output);
        }
        if (!cycle.waitFor(5, TimeUnit.SECONDS) || cycle.exitValue() != 0) throw new AssertionError("cycle did not finish");
        if (!Files.readString(journal).isEmpty()) throw new AssertionError("unstarted cycle changed the port");
    }

    private static void startedCycleReportsBothCompleted(Path journal, Path release, Path fake) throws Exception {
        Files.createFile(release);
        Process cycle = launchCycle(journal, fake);
        try (var commands = cycle.getOutputStream(); var output = output(cycle)) {
            arm(commands, output);
            signal(commands, "cycle");
            if (!"done 0 0".equals(output.readLine())) throw new AssertionError("cycle did not confirm both writes");
        }
        if (!cycle.waitFor(5, TimeUnit.SECONDS) || cycle.exitValue() != 0) throw new AssertionError("cycle did not exit");
        if (!EXPECTED.equals(Files.readString(journal))) throw new AssertionError("unexpected order of normal cycle");
        Files.delete(release);
        Files.writeString(journal, "");
    }

    private static void cycleSurvivesKilledOwner(Path journal, Path release, Path fake) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process owner = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                SdRemountUsbRestoreTest.class.getName(), "--owner", journal.toString(), fake.toString()).start();
        try {
            awaitJournal(journal, "off-start\n");
            owner.destroyForcibly();
            if (!owner.waitFor(5, TimeUnit.SECONDS)) throw new AssertionError("owner did not exit");
            Files.createFile(release);
            awaitJournal(journal, EXPECTED);
        } finally {
            if (owner.isAlive()) owner.destroyForcibly();
            if (!Files.exists(release)) Files.createFile(release);
        }
    }

    private static void awaitJournal(Path journal, String expected) throws Exception {
        long stop = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < stop) {
            if (expected.equals(Files.readString(journal))) return;
            Thread.sleep(20);
        }
        throw new AssertionError("USB command order was " + Files.readString(journal));
    }

    private static Process launchCycle(Path journal, Path fake) throws Exception {
        String prefix = "/system/bin/dumpsys -t 7 usb enable-usb-data port0 ";
        String script = SdRemountUsbRestore.SCRIPT.replace(prefix + "false", "/bin/sh \"$2\" off \"$1\"")
                .replace(prefix + "true", "/bin/sh \"$2\" on \"$1\"")
                .replace("/system/bin/sleep", "/bin/sleep");
        return new ProcessBuilder("/bin/sh", "-c", script, "usb-cycle", journal.toString(), fake.toString()).start();
    }

    private static BufferedReader output(Process process) {
        return new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.US_ASCII));
    }

    private static void arm(OutputStream commands, BufferedReader output) throws Exception {
        signal(commands, "arm");
        if (!"armed".equals(output.readLine())) throw new AssertionError("cycle did not arm");
    }

    private static void signal(OutputStream commands, String command) throws Exception {
        commands.write((command + "\n").getBytes(StandardCharsets.US_ASCII));
        commands.flush();
    }
}
