package app.seanime.tv;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.Base64;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Test APK only, under its own UID and process. Use only framework/Java classes:
 * androidTest's APK omits dependencies already supplied by the target APK, and
 * those dependencies are not on this standalone activity's class path.
 */
public final class TestStreamingExternalPlayerActivity extends Activity {
    public static final String COMMAND = "app.seanime.tv.test.EXTERNAL_PLAYER_COMMAND";
    public static final String RESULT = "app.seanime.tv.test.EXTERNAL_PLAYER_RESULT";

    private final AtomicBoolean reading = new AtomicBoolean();
    private String source;
    private boolean receiverRegistered;
    private final BroadcastReceiver commands = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!source.equals(intent.getStringExtra("source"))) return;
            if (intent.getBooleanExtra("finish", false)) {
                finish();
                return;
            }
            if (!reading.compareAndSet(false, true)) return;
            new Thread(() -> {
                try {
                    for (int index = 0; index < 3; index++) {
                        if (index > 0) SystemClock.sleep(1_500);
                        int start = index * 256;
                        HttpURLConnection connection = (HttpURLConnection) new URL(source).openConnection();
                        try {
                            connection.setInstanceFollowRedirects(false);
                            connection.setConnectTimeout(10_000);
                            connection.setReadTimeout(10_000);
                            connection.setRequestProperty("Range", "bytes=" + start + "-" + (start + 255));
                            for (String header : new String[] {"Authorization", "Cookie", "X-Seanime-Token",
                                    "X-Seanime-Client-Id", "X-Seanime-Client-Id-Proof"}) {
                                if (connection.getRequestProperty(header) != null) throw new IllegalStateException();
                            }
                            int code = connection.getResponseCode();
                            ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
                            try (InputStream input = connection.getInputStream()) {
                                for (int count = 0; count < 256; count++) {
                                    int value = input.read();
                                    if (value < 0) break;
                                    bytes.write(value);
                                }
                            }
                            sendBroadcast(report("range").putExtra("index", index).putExtra("status", code)
                                    .putExtra("contentRange", connection.getHeaderField("Content-Range"))
                                    .putExtra("bytes", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))
                                    .putExtra("authorityHeaders", false));
                        } finally {
                            connection.disconnect();
                        }
                    }
                } catch (Exception error) {
                    sendBroadcast(report("error").putExtra("failure", error.getClass().getSimpleName()));
                }
            }, "owned-external-range-reader").start();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent handoff = getIntent();
        Uri uri = handoff.getData();
        if (uri == null || !"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
                || uri.getPort() != 43211 || !"/api/v1/mediastream/file".equals(uri.getPath())) {
            throw new IllegalArgumentException("Expected the owned loopback media route");
        }
        String path = uri.getQueryParameter("path");
        if (path == null || !Pattern.matches(".*/native-go-fixture-[0-9a-f-]{36}/library/[^/]+\\.mp4", path)
                || !Intent.ACTION_VIEW.equals(handoff.getAction()) || handoff.getType() == null
                || !handoff.getType().startsWith("video/")) {
            throw new IllegalArgumentException("Expected the owned video handoff");
        }
        Bundle extras = handoff.getExtras();
        if (extras != null) {
            for (String key : extras.keySet()) {
                String lower = key.toLowerCase(Locale.ROOT);
                if (lower.contains("header") || lower.contains("proof") || lower.contains("token")) {
                    throw new IllegalArgumentException("The handoff must not contain authority extras");
                }
            }
        }
        source = uri.toString();
        TextView view = new TextView(this);
        view.setText("Owned external streaming test\nReading only the generated video from Seanime");
        view.setTextColor(Color.WHITE);
        view.setBackgroundColor(Color.rgb(16, 23, 35));
        view.setTextSize(26f);
        view.setPadding(48, 48, 48, 48);
        setContentView(view);
        IntentFilter filter = new IntentFilter(COMMAND);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(commands, filter, Context.RECEIVER_EXPORTED);
        else registerLegacyCommands(filter);
        receiverRegistered = true;
    }

    /** API 23-32 has no RECEIVER_EXPORTED registration flag; the legacy receiver is exported. */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerLegacyCommands(IntentFilter filter) {
        registerReceiver(commands, filter);
    }

    @Override protected void onResume() {
        super.onResume();
        sendBroadcast(report("ready"));
    }

    @Override protected void onDestroy() {
        if (receiverRegistered) unregisterReceiver(commands);
        super.onDestroy();
    }

    private Intent report(String type) {
        return new Intent(RESULT).setPackage("app.seanime.tv")
                .putExtra("type", type).putExtra("source", source)
                .putExtra("pid", Process.myPid()).putExtra("uid", Process.myUid())
                .putExtra("elapsed", SystemClock.elapsedRealtime());
    }
}
