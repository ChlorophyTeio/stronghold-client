package top.ppparty.stronghold_client;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.*;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Platform-only smoke runner for the actual minified release APK. Only GETs
 * reach the LAN spa-test backend. The transport adapter exists in the test APK
 * because this executor cannot directly use the public IPv6 endpoint. */
public final class NativeSmokeInstrumentation extends Instrumentation {
    private static final String ORIGIN = "https://test-spa.ppparty.top:8000/";
    private boolean updatesOnly;
    private boolean updatesOnline;
    private String upgradeMode;
    private Bundle upgradeArguments;
    private boolean requireCutout;
    private Activity activity;
    private WebView web;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); upgradeArguments = arguments; updatesOnly = "true".equals(arguments.getString("updatesOnly")); updatesOnline = "true".equals(arguments.getString("updatesOnline")); upgradeMode = arguments.getString("upgradeMode", ""); requireCutout = "true".equals(arguments.getString("requireCutout")); start(); }

    @Override public void onStart() {
        if ("download".equals(upgradeMode)) { UpgradeSmoke.download(this, upgradeArguments); return; }
        if ("verify".equals(upgradeMode)) { UpgradeSmoke.verify(this); return; }
        if ("install".equals(upgradeMode)) { UpgradeSmoke.install(this); return; }
        if (updatesOnline) { UpdateSmoke.online(this); return; }
        if (updatesOnly) { UpdateSmoke.run(this); return; }
        Bundle result = new Bundle();
        try {
            File[] oldShots = getTargetContext().getExternalFilesDir(null).listFiles();
            if (oldShots != null) for (File file : oldShots)
                if (file.getName().startsWith("native-") && (file.getName().endsWith(".png") || file.getName().endsWith(".json"))) file.delete();
            getTargetContext().getSharedPreferences("client_display", 0).edit().clear().commit();
            getTargetContext().getSharedPreferences("FlutterSharedPreferences", 0).edit()
                .putString("flutter.server", "https://ignored.invalid").commit();
            Intent intent = new Intent().setClassName(getTargetContext(), "top.ppparty.stronghold_client.GameActivity")
                .putExtra("server", "https://ignored.invalid/").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = startActivitySync(intent);
            await(15000, () -> {
                runOnMainSync(() -> web = findWeb(activity.getWindow().getDecorView()));
                return web != null;
            });
            await(15000, () -> {
                final String[] initial = new String[1];
                runOnMainSync(() -> initial[0] = web.getOriginalUrl());
                return ORIGIN.equals(initial[0]);
            });
            result.putBoolean("initialServerFixed", true);
            runOnMainSync(() -> {
                web.stopLoading();
                WebViewClient original = web.getWebViewClient();
                web.setWebViewClient(new WebViewClient() {
                    @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                        WebResourceResponse local = original.shouldInterceptRequest(view, request);
                        if (local != null) return local;
                        if (!"test-spa.ppparty.top".equals(request.getUrl().getHost())) return null;
                        if (!"GET".equals(request.getMethod())) throw new AssertionError("Smoke test must not mutate server state");
                        String path = request.getUrl().getEncodedPath();
                        if (request.getUrl().getEncodedQuery() != null) path += "?" + request.getUrl().getEncodedQuery();
                        try { return backend(path); }
                        catch (Exception error) { throw new RuntimeException(error); }
                    }
                    @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return original.shouldOverrideUrlLoading(view, request); }
                    @Override public void onPageStarted(WebView view, String url, Bitmap icon) { original.onPageStarted(view, url, icon); }
                    @Override public void onPageFinished(WebView view, String url) { original.onPageFinished(view, url); }
                    @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) { original.onReceivedError(view, request, error); }
                    @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) { original.onReceivedHttpError(view, request, response); }
                });
                web.loadUrl(ORIGIN);
            });
            for (int round = 0; round < 3; round++) {
                await(60000, () -> "true".equals(eval("!!window.__SP__ && document.getElementById('app').innerText.length > 20")));
                JSONObject state = new JSONObject(decode(eval("JSON.stringify({w:innerWidth,h:innerHeight,text:document.getElementById('app').innerText.slice(0,160)})")));
                require(state.getInt("w") > state.getInt("h"), "Web content is not landscape: " + state);
                require(activity.getResources().getConfiguration().orientation == 2, "Activity not landscape");
                Thread.sleep(5000);
                Bitmap shot = getUiAutomation().takeScreenshot();
                require(shot != null && shot.getWidth() > shot.getHeight(), "Screenshot not landscape");
                Set<Integer> colors = new HashSet<>();
                for (int x = 50; x < shot.getWidth() - 60; x += 17)
                    for (int y = 60; y < shot.getHeight() - 30; y += 17) colors.add(shot.getPixel(x, y));
                require(colors.size() > 16, "DOM exists but rendered pixels are blank: " + colors.size());
                try (OutputStream out = new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null), "native-title-" + round + ".png"))) {
                    shot.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                shot.recycle();
                if (round < 2) {
                    eval("delete window.__SP__; location.reload()");
                    Thread.sleep(300);
                }
            }
            checkFullscreenViewport();
            require("true".equals(eval("['t','r','b','l'].every(side => getComputedStyle(document.documentElement).getPropertyValue('--sa-' + side).trim() === '0px')")), "Website safe area still reserves space");
            result.putBoolean("fullBleed", true);
            eval("document.querySelector('[data-testid=\"settings-btn\"], [aria-label=\"设置\"]').click()");
            await(10000, () -> "true".equals(eval("!!document.querySelector('[data-sp-client-settings] a')")));
            require("1".equals(eval("document.querySelectorAll('[data-sp-client-settings]').length")), "Duplicate client settings entry");
            Thread.sleep(1000);
            saveScreenshot("native-settings.png");
            eval("document.querySelector('[data-sp-client-settings] a').click()");
            await(5000, () -> { android.view.accessibility.AccessibilityNodeInfo node = getUiAutomation().getRootInActiveWindow(); return node != null && !node.findAccessibilityNodeInfosByText("客户端设置 · 测试服").isEmpty(); });
            saveScreenshot("native-client-settings.png");
            result.putBoolean("settingsEntry", true);
            testMargins();
            result.putBoolean("manualMargins", true);
            getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            runOnMainSync(() -> activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE));
            Thread.sleep(2000);
            checkFullscreenViewport();
            require("true".equals(eval("innerWidth > innerHeight")), "Reverse landscape viewport is wrong");
            saveScreenshot("native-reverse.png");
            result.putBoolean("reverseLandscape", true);
            result.putInt("renderedLoads", 3);
            result.putBoolean("landscape", true);

            JSONObject entries;
            try (InputStream input = getTargetContext().getAssets().open("flutter_assets/assets/game/index.json")) {
                entries = new JSONObject(new String(readAll(input), "UTF-8")).getJSONObject("entries");
            }
            String audio = null;
            for (Iterator<String> keys = entries.keys(); keys.hasNext();) {
                String key = keys.next();
                if (key.startsWith("/assets/audio/") && key.endsWith(".mp3")) { audio = key; break; }
            }
            require(audio != null, "No packaged audio fixture");
            String hash = entries.getJSONObject(audio).getJSONArray("hashes").getString(0);
            String url = ORIGIN + "media/~" + hash + "/" + audio.substring("/assets/audio/".length(), audio.length() - 4);
            eval("fetch(" + JSONObject.quote(url) + ",{headers:{Range:'bytes=0-7'}}).then(async r=>window.__PACK_TEST__={status:r.status,bytes:(await r.arrayBuffer()).byteLength,source:r.headers.get('X-SP-Resource')})");
            await(10000, () -> "true".equals(eval("!!window.__PACK_TEST__")));
            JSONObject audioResult = new JSONObject(decode(eval("JSON.stringify(window.__PACK_TEST__)")));
            require(audioResult.getInt("status") == 206 && audioResult.getInt("bytes") == 8
                && "apk-native".equals(audioResult.getString("source")), "Pack range response mismatch: " + audioResult);
            result.putString("rangeResponse", audioResult.toString());
            require(decode(eval("location.origin")).equals("https://test-spa.ppparty.top:8000"), "Server override was accepted");
            result.putBoolean("fixedTestServer", true);
            result.putBoolean("ok", true);
            try (Writer out = new OutputStreamWriter(new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null), "native-smoke-result.json")), "UTF-8")) {
                out.write(new JSONObject().put("ok", true).put("renderedLoads", 3).put("landscape", true)
                    .put("fixedTestServer", true).put("manualMargins", true).put("settingsEntry", true).put("safeArea", true).put("reverseLandscape", true).put("rangeResponse", audioResult).toString());
            }
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putBoolean("ok", false);
            StringWriter trace = new StringWriter(); error.printStackTrace(new PrintWriter(trace));
            result.putString("error", trace.toString());
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            if (activity != null) runOnMainSync(() -> activity.finish());
        }
    }

    private android.view.accessibility.AccessibilityNodeInfo node(String text) {
        android.view.accessibility.AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
        if (root == null) return null;
        for (android.view.accessibility.AccessibilityNodeInfo item : root.findAccessibilityNodeInfosByText(text)) {
            if (item.isClickable()) return item;
        }
        return null;
    }

    private void clickText(String text) throws Exception {
        await(5000, () -> node(text) != null);
        node(text).performAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
        Thread.sleep(200);
        require(node(text).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK), "Cannot click " + text);
    }

    private android.view.accessibility.AccessibilityNodeInfo slider(android.view.accessibility.AccessibilityNodeInfo root, String label) {
        if (root == null) return null;
        if ("android.widget.SeekBar".equals(String.valueOf(root.getClassName())) && label.equals(String.valueOf(root.getContentDescription()))) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo found = slider(root.getChild(i), label);
            if (found != null) return found;
        }
        return null;
    }

    private void testMargins() throws Exception {
        final int[] base = new int[4];
        runOnMainSync(() -> {
            ViewGroup root = (ViewGroup) web.getParent();
            base[0] = root.getPaddingLeft(); base[1] = root.getPaddingTop();
            base[2] = root.getPaddingRight(); base[3] = root.getPaddingBottom();
        });
        clickText("屏幕边距");
        String[] labels = {"左侧边距", "顶部边距", "右侧边距", "底部边距"};
        int[] values = {12, 8, 20, 6};
        for (int index = 0; index < labels.length; index++) {
            final String label = labels[index];
            await(5000, () -> slider(getUiAutomation().getRootInActiveWindow(), label) != null);
            android.view.accessibility.AccessibilityNodeInfo bar = slider(getUiAutomation().getRootInActiveWindow(), label);
            Bundle progress = new Bundle();
            progress.putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, values[index]);
            require(bar.performAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), progress), "Slider adjustment failed: " + label);
        }
        Thread.sleep(500);
        runOnMainSync(() -> {
            ViewGroup root = (ViewGroup) web.getParent();
            int[] actual = {root.getPaddingLeft(), root.getPaddingTop(), root.getPaddingRight(), root.getPaddingBottom()};
            for (int i = 0; i < values.length; i++)
                require(actual[i] == base[i] + Math.round(values[i] * activity.getResources().getDisplayMetrics().density), "Margin preview failed: " + labels[i]);
        });
        saveScreenshot("native-margin-adjusted.png");
        clickText("完成");
        await(5000, () -> getTargetContext().getSharedPreferences("client_display", 0).getInt("left", -1) == 12);
        reopenMenuAfterMarginDialog();
        clickText("屏幕边距");
        await(5000, () -> slider(getUiAutomation().getRootInActiveWindow(), "左侧边距") != null);
        require(slider(getUiAutomation().getRootInActiveWindow(), "左侧边距").getRangeInfo().getCurrent() == 12f, "Slider did not restore saved value");
        clickText("恢复默认");
        Thread.sleep(300);
        runOnMainSync(() -> require(((ViewGroup) web.getParent()).getPaddingLeft() == base[0], "Reset did not restore full display"));
        saveScreenshot("native-margin-default.png");
        clickText("完成");
        await(5000, () -> getTargetContext().getSharedPreferences("client_display", 0).getInt("left", -1) == 0);
        reopenMenuAfterMarginDialog();
    }

    private void reopenMenuAfterMarginDialog() throws Exception {
        await(5000, () -> slider(getUiAutomation().getRootInActiveWindow(), "左侧边距") == null);
        getUiAutomation().waitForIdle(300, 5000);
        getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
        await(5000, () -> node("屏幕边距") != null);
    }

    private void saveScreenshot(String name) throws Exception {
        Bitmap shot = getUiAutomation().takeScreenshot();
        require(shot != null, "Missing screenshot");
        try (OutputStream out = new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null), name))) {
            shot.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        shot.recycle();
    }

    private void checkFullscreenViewport() {
        runOnMainSync(() -> {
            android.view.ViewGroup root = (android.view.ViewGroup) web.getParent();
            require(root.getPaddingLeft() == 0 && root.getPaddingTop() == 0 && root.getPaddingRight() == 0 && root.getPaddingBottom() == 0, "Automatic game margins remain");
            View decor = activity.getWindow().getDecorView();
            if(requireCutout) {
                android.view.DisplayCutout cutout = decor.getRootWindowInsets().getDisplayCutout();
                require(cutout != null && !cutout.getBoundingRects().isEmpty(), "Cutout fixture was not active");
            }
            android.util.DisplayMetrics physical = new android.util.DisplayMetrics();
            activity.getWindowManager().getDefaultDisplay().getRealMetrics(physical);
            require(root.getWidth() == physical.widthPixels && root.getHeight() == physical.heightPixels, "Game does not fill physical display");
            int[] location = new int[2]; root.getLocationOnScreen(location);
            require(location[0] == 0 && location[1] == 0, "Root view is offset from full display");
            require(root.getWidth() == decor.getWidth() && root.getHeight() == decor.getHeight(), "Root view is not full window");
            require(web.getWidth() == root.getWidth() && web.getHeight() == root.getHeight(), "WebView is not full window");
        });
    }

    private WebResourceResponse backend(String path) throws Exception {
        require(path.startsWith("/") && !path.contains("\r") && !path.contains("\n"), "Invalid fixture path");
        Map<String, String> headers = new HashMap<>();
        byte[] data;
        int code;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("192.168.130.10", 9012), 5000);
            socket.setSoTimeout(15000);
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: 192.168.130.10:9012\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
            InputStream input = new BufferedInputStream(socket.getInputStream());
            code = Integer.parseInt(line(input).split(" ")[1]);
            for (;;) {
                String line = line(input);
                if (line.isEmpty()) break;
                int colon = line.indexOf(':');
                headers.put(line.substring(0, colon).toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
            if (headers.getOrDefault("transfer-encoding", "").toLowerCase(Locale.ROOT).contains("chunked")) {
                ByteArrayOutputStream decoded = new ByteArrayOutputStream();
                for (;;) {
                    int size = Integer.parseInt(line(input).split(";")[0].trim(), 16);
                    if (size == 0) {
                        while (!line(input).isEmpty()) { /* trailers */ }
                        break;
                    }
                    byte[] buffer = new byte[Math.min(size, 16384)];
                    int left = size;
                    while (left > 0) {
                        int n = input.read(buffer, 0, Math.min(left, buffer.length));
                        if (n < 0) throw new EOFException("Truncated chunk");
                        decoded.write(buffer, 0, n);
                        left -= n;
                    }
                    require(line(input).isEmpty(), "Invalid chunk delimiter");
                }
                data = decoded.toByteArray();
            } else data = readAll(input);
        }
        String type = headers.getOrDefault("content-type", "application/octet-stream").split(";")[0];
        for (String key : Arrays.asList("connection", "keep-alive", "transfer-encoding", "set-cookie", "content-length")) headers.remove(key);
        headers.put("Content-Length", String.valueOf(data.length));
        return new WebResourceResponse(type, type.startsWith("text/") || type.contains("json") || type.contains("javascript") ? "utf-8" : null,
            code, code == 200 ? "OK" : "HTTP " + code, headers, new ByteArrayInputStream(data));
    }

    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (;;) {
            int n = input.read();
            if (n < 0 || n == 10) break;
            if (n != 13) out.write(n);
            if (out.size() >= 65536) throw new IOException("Invalid header");
        }
        return out.toString("ISO-8859-1");
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int n;
        while ((n = input.read(buffer)) >= 0) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    private static WebView findWeb(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                WebView found = findWeb(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private String eval(String script) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        String[] value = {"null"};
        runOnMainSync(() -> web.evaluateJavascript(script, result -> { value[0] = result; latch.countDown(); }));
        require(latch.await(5, TimeUnit.SECONDS), "JavaScript callback timeout");
        return value[0];
    }

    private static String decode(String value) throws Exception { return (String) new JSONTokener(value).nextValue(); }
    private interface Condition { boolean get() throws Exception; }
    private static void await(long timeout, Condition condition) throws Exception {
        long end = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < end) { if (condition.get()) return; Thread.sleep(100); }
        throw new AssertionError("Condition timed out after " + timeout + " ms");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
