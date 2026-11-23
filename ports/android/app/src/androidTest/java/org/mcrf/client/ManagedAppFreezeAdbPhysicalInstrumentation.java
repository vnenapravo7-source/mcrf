package org.mcrf.client;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Physical test runner source. Compile into the owned matrix test artifact only. */
public final class ManagedAppFreezeAdbPhysicalInstrumentation extends Instrumentation {
    private static final String MAIN = "org.mcrf.client";
    private static final String HELPER = "org.mcrf.p1owner";
    private static final String PEER = "org.mcrf.p1owner.peer";
    private static final String PROVIDER = "content://" + MAIN + ".freeze-adb";
    private static final String DESCRIPTOR = MAIN + ".ManagedAppFreezeAdb";
    private static final int CHANGE = IBinder.FIRST_CALL_TRANSACTION;
    private static final int QUERY = CHANGE + 1;
    private static final int STOP = CHANGE + 2;
    private static final int DEFAULT = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
    private static final int DISABLED = PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
    private static final String ACTIVATION_PREFIX = "adb shell 'CLASSPATH=$(pm path --user 0 " + MAIN
            + " | head -n 1 | cut -d: -f2) app_process /system/bin " + MAIN + ".ManagedAppFreezeAdbDaemon ";
    private static final Pattern ACTIVATION = Pattern.compile(
            Pattern.quote(ACTIVATION_PREFIX) + "([0-9a-f-]{36})'");
    private static final String START = "CLASSPATH=$(pm path --user 0 org.mcrf.client | head -n 1 | cut -d: -f2); "
            + "export CLASSPATH; exec timeout -s KILL 55 app_process /system/bin org.mcrf.client.ManagedAppFreezeAdbDaemon ";
    private static final String DISABLED_PACKAGES = "cmd package list packages --user 0 -d ";
    private static final String AUTH_REJECTED = "MCRF_ADB_AUTH_REJECTED";
    private static final Pattern START_FAILED = Pattern.compile(
            "^MCRF_ADB_START_FAILED:(caller|context|owner|handoff|lifetime):([A-Za-z][A-Za-z0-9]{0,63})$",
            Pattern.MULTILINE);
    private static final String NO_DAEMON_STATUS = "no-daemon-status";
    private String stage = "preflight";
    private String daemonStatus;
    private Context app;
    private PackageManager pm;
    private UiAutomation automation;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_CANCELED;
        boolean owned = false;
        IBinder binder = null;
        ShellTask daemon = null;
        try {
            app = getTargetContext();
            pm = app.getPackageManager();
            require(Build.VERSION.SDK_INT >= 34, "matrix runner requires Android 14+");
            automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            int mainUid = app.getApplicationInfo().uid;
            require(MAIN.equals(app.getPackageName()) && pm.getPackageInfo(MAIN, 0).getLongVersionCode() == 10188,
                    "Main fixture identity");
            require(mainUid >= 10000 && mainUid < 100000 && Process.myUid() == mainUid, "Main user-0 instrumentation");
            require(command("id -u").trim().equals("2000"), "real ADB shell required");
            int helperUid = fixtureUid(HELPER);
            require(helperUid == fixtureUid(PEER) && helperUid != mainUid, "fixture UID split");
            require(command("run-as " + HELPER + " --user 0 id -u").trim().equals(Integer.toString(helperUid)),
                    "fixture process identity");
            require(state(HELPER) == DEFAULT && state(PEER) == DEFAULT && session() == null, "clean baseline");
            owned = true;

            stage = "wrong-provider-caller";
            String shellGet = command("content call --user 0 --uri " + PROVIDER + " --method get");
            require(shellGet.contains("IllegalArgumentException") && !shellGet.contains("Result: Bundle"), "shell GET rejected");
            String helperGet = command("run-as " + HELPER + " --user 0 content call --user 0 --uri "
                    + PROVIDER + " --method get");
            // Ordinary apps can be rejected before provider dispatch by ActivityManager.
            require(helperGet.contains("SecurityException")
                    && (helperGet.contains("getContentProviderExternal") || helperGet.contains("android.permission.DUMP"))
                    && !helperGet.contains("Result: Bundle"), "fixture UID rejected");
            result.putBoolean(stage, true);

            stage = "wrong-nonce";
            String activation = activation();
            String wrong = (activation.startsWith("0") ? "1" : "0") + activation.substring(1);
            rejectActivation(wrong);
            result.putBoolean(stage, true);

            stage = "activate-query";
            daemon = launch(activation);
            binder = awaitSession(daemon);
            require(request(binder, QUERY, null) == DEFAULT, "helper state before freeze");
            result.putBoolean(stage, true);

            stage = "freeze-thaw";
            request(binder, CHANGE, DISABLED);
            require(request(binder, QUERY, null) == DISABLED && state(HELPER) == DISABLED && shellDisabled(HELPER),
                    "freeze visible to package manager");
            require(state(PEER) == DEFAULT && !shellDisabled(PEER), "shared-UID peer unchanged");
            request(binder, CHANGE, DEFAULT);
            require(request(binder, QUERY, null) == DEFAULT && state(HELPER) == DEFAULT && !shellDisabled(HELPER),
                    "exact fixture state restored");
            result.putBoolean(stage, true);

            stage = "stop-death";
            stop(binder);
            IBinder stopped = binder;
            await(() -> !stopped.isBinderAlive() && session() == null);
            require(daemon.output().contains("MCRF ADB connected."), "daemon acknowledged activation");
            daemon.close();
            daemon = null;
            binder = null;
            result.putBoolean(stage, true);

            stage = "replay-after-stop";
            rejectActivation(activation);
            result.putBoolean(stage, true);
            code = Activity.RESULT_OK;
        } catch (Exception error) {
            // Commands, exceptions, daemon output and activation values are never emitted.
            result.putString("failure", stage + ":" + error.getClass().getSimpleName());
            if (daemonStatus != null) result.putString("daemonStatus", daemonStatus);
        } finally {
            try {
                if (owned) {
                    if (state(HELPER) != DEFAULT) {
                        if (binder != null && binder.isBinderAlive()) {
                            try { request(binder, CHANGE, DEFAULT); } catch (Exception unavailable) { /* bounded fallback below */ }
                        }
                        if (state(HELPER) != DEFAULT)
                            command("pm default-state --user 0 " + HELPER);
                    }
                    IBinder current = binder != null ? binder : session();
                    if (current != null && current.isBinderAlive()) {
                        stop(current);
                        await(() -> !current.isBinderAlive() && session() == null);
                    }
                    require(state(HELPER) == DEFAULT && state(PEER) == DEFAULT && session() == null, "fixture cleanup");
                    result.putBoolean("cleanup", true);
                }
            } catch (Exception error) {
                code = Activity.RESULT_CANCELED;
                result.putString("cleanupFailure", error.getClass().getSimpleName());
            } finally {
                if (daemon != null) daemon.close();
                finish(code, result);
            }
        }
    }

    private int fixtureUid(String name) throws PackageManager.NameNotFoundException {
        ApplicationInfo info = pm.getApplicationInfo(name, 0);
        int flags = ApplicationInfo.FLAG_TEST_ONLY | ApplicationInfo.FLAG_DEBUGGABLE;
        require((info.flags & flags) == flags && pm.getPackageInfo(name, 0).getLongVersionCode() == (HELPER.equals(name) ? 2 : 1)
                && pm.checkSignatures(MAIN, name) == PackageManager.SIGNATURE_MATCH
                && info.uid >= 10000 && info.uid < 100000, "owned user-0 fixture required");
        return info.uid;
    }

    private String activation() throws ReflectiveOperationException {
        Class<?> type = app.getClassLoader().loadClass(MAIN + ".ManagedAppFreezeAdb");
        Object instance = type.getField("INSTANCE").get(null);
        String command = (String) type.getMethod("activationCommand", Context.class).invoke(instance, app);
        Matcher match = ACTIVATION.matcher(command);
        require(match.matches(), "unexpected activation command format");
        return match.group(1);
    }

    private IBinder session() {
        Bundle response = app.getContentResolver().call(Uri.parse(PROVIDER), "get", null, null);
        return response == null ? null : response.getBinder("binder");
    }

    private int state(String name) {
        if (!HELPER.equals(name) && !PEER.equals(name)) throw new IllegalArgumentException();
        return pm.getApplicationEnabledSetting(name);
    }

    private boolean shellDisabled(String name) throws Exception {
        if (!HELPER.equals(name) && !PEER.equals(name)) throw new IllegalArgumentException();
        boolean disabled = false;
        for (String line : command(DISABLED_PACKAGES + name).split("\\R")) {
            if (line.isEmpty()) continue;
            require(line.matches("package:[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+"),
                    "unexpected package list response");
            if (("package:" + name).equals(line)) disabled = true;
        }
        return disabled;
    }

    private int request(IBinder binder, int operation, Integer state) throws Exception {
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(DESCRIPTOR);
            request.writeString(HELPER);
            if (state != null) request.writeInt(state);
            require(binder.transact(operation, request, reply, 0), "Binder command accepted");
            reply.readException();
            return operation == QUERY ? reply.readInt() : 0;
        } finally {
            request.recycle();
            reply.recycle();
        }
    }

    private void stop(IBinder binder) throws Exception {
        Parcel request = Parcel.obtain();
        try {
            request.writeInterfaceToken(DESCRIPTOR);
            require(binder.transact(STOP, request, null, IBinder.FLAG_ONEWAY), "Binder stop accepted");
        } finally {
            request.recycle();
        }
    }

    private void rejectActivation(String activation) throws Exception {
        try (ShellTask rejected = launch(activation)) {
            daemonStatus = daemonStatus(rejected.output());
            require(AUTH_REJECTED.equals(daemonStatus) && session() == null, "helper rejected wrong or spent activation");
            daemonStatus = null;
        }
    }

    private IBinder awaitSession(ShellTask daemon) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 12000L;
        while (SystemClock.elapsedRealtime() < deadline) {
            IBinder active = session();
            if (active != null && active.isBinderAlive()) return active;
            if (daemon.completed()) {
                daemonStatus = daemonStatus(daemon.output());
                throw new IllegalStateException("daemon exited before connection");
            }
            SystemClock.sleep(100L);
        }
        throw new IllegalStateException("daemon did not connect");
    }

    private static String daemonStatus(String output) {
        for (String line : output.split("\\R")) {
            if (AUTH_REJECTED.equals(line)) return AUTH_REJECTED;
        }
        Matcher failure = START_FAILED.matcher(output);
        return failure.find() ? failure.group(1) + ":" + failure.group(2) : NO_DAEMON_STATUS;
    }

    private ShellTask launch(String activation) throws Exception {
        require(activation.matches("[0-9a-f-]{36}"), "invalid activation input");
        // UiAutomation executes argv directly. Send the fixed script through stdin of sh
        // so the activation value never appears in the logged UiAutomation command.
        return shellTask("/system/bin/sh", START + activation + "\n");
    }

    private ShellTask shellTask(String command, String inputText) throws Exception {
        ParcelFileDescriptor[] pipes = automation.executeShellCommandRwe(command);
        ShellTask task = new ShellTask(pipes[0], pipes[2]);
        try (ParcelFileDescriptor.AutoCloseOutputStream input = new ParcelFileDescriptor.AutoCloseOutputStream(pipes[1])) {
            if (inputText != null) input.write(inputText.getBytes(StandardCharsets.US_ASCII));
            return task;
        } catch (Exception error) {
            task.close();
            throw error;
        }
    }

    private String command(String shell) throws Exception {
        try (ShellTask task = shellTask(shell, null)) {
            return task.output();
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 12000L;
        while (!condition.getAsBoolean()) {
            require(SystemClock.elapsedRealtime() < deadline, "deadline expired");
            SystemClock.sleep(100L);
        }
    }

    private static void require(boolean condition, String error) {
        if (!condition) throw new IllegalStateException(error);
    }

    private static final class ShellTask implements AutoCloseable {
        private final InputStream stdout;
        private final InputStream stderr;
        private final FutureTask<String> output;
        private final FutureTask<String> errors;

        ShellTask(ParcelFileDescriptor out, ParcelFileDescriptor err) {
            stdout = new ParcelFileDescriptor.AutoCloseInputStream(out);
            stderr = new ParcelFileDescriptor.AutoCloseInputStream(err);
            output = drain(stdout);
            errors = drain(stderr);
        }

        private static FutureTask<String> drain(InputStream stream) {
            FutureTask<String> read = new FutureTask<>(() -> {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    int retain = Math.min(count, 16384 - bytes.size());
                    if (retain > 0) bytes.write(buffer, 0, retain);
                }
                return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            });
            Thread reader = new Thread(read, "adb-fixture-output");
            reader.setDaemon(true);
            reader.start();
            return read;
        }

        String output() throws Exception {
            return output.get(15L, TimeUnit.SECONDS) + errors.get(2L, TimeUnit.SECONDS);
        }

        boolean completed() { return output.isDone() && errors.isDone(); }

        @Override public void close() {
            output.cancel(true);
            errors.cancel(true);
            try { stdout.close(); } catch (java.io.IOException ignored) { /* test stream already closed */ }
            try { stderr.close(); } catch (java.io.IOException ignored) { /* test stream already closed */ }
        }
    }
}
