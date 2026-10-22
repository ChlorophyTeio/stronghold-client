package top.ppparty.stronghold_client;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.webkit.*;
import android.net.Uri;
import java.io.*;
import java.net.*;
import java.util.*;
import org.json.*;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/** Offline signed pack transaction tests against the actual minified release. */
public final class UpdateSmoke {
    public static void run(Instrumentation runner) {
        Bundle result = new Bundle();
        UpdateStore store = new UpdateStore(runner.getTargetContext());
        Activity[] activity = {null};
        Activity[] game = {null};
        WebView[] web = {null};
        try {
            remove(store.getRoot());
            runner.getTargetContext().getSharedPreferences("client_updates", 0).edit().clear().commit();
            store = new UpdateStore(runner.getTargetContext());
            require(store.parse(read(runner.getContext().getAssets().open("update-fixtures/baseline/update-preview.json"))).getEntries().length() >= 25415, "Full baseline manifest rejected");
            result.putBoolean("fullBaseline", true);
            byte[] good = read(runner.getContext().getAssets().open("update-fixtures/1/update-preview.json"));
            JSONObject tampered = new JSONObject(new String(good, "UTF-8"));
            tampered.put("signature", "AAAA");
            try { store.parse(tampered.toString().getBytes("UTF-8")); throw new AssertionError("Invalid signature accepted"); }
            catch (IllegalArgumentException expected) {}
            UpdateStore.Release first = store.parse(good);
            UpdateStore.Release second = store.parse(read(runner.getContext().getAssets().open("update-fixtures/2/update-preview.json")));
            Function1<String, Unit> progress = text -> Unit.INSTANCE;
            store.apply(first, copy(runner, 1, false), progress);
            require(store.active().getSequence() == 1, "First resource activation failed");
            UpdateStore.Release pinned = store.active();
            game[0] = runner.startActivitySync(new Intent().setClassName(runner.getTargetContext(), "top.ppparty.stronghold_client.GameActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            long deadline = System.currentTimeMillis() + 15000;
            while(web[0] == null && System.currentTimeMillis() < deadline) {
                runner.runOnMainSync(() -> web[0] = findWeb(game[0].getWindow().getDecorView()));
                Thread.sleep(100);
            }
            require(web[0] != null, "Game WebView did not initialize");
            runner.runOnMainSync(() -> web[0].stopLoading());
            final WebViewClient[] clients = {null};
            runner.runOnMainSync(() -> clients[0] = web[0].getWebViewClient());
            WebResourceResponse range = clients[0].shouldInterceptRequest(web[0], request(first, true));
            require(range != null && range.getStatusCode() == 206 && "update-native".equals(range.getResponseHeaders().get("X-SP-Resource")) && "fixt".equals(new String(read(range.getData()), "UTF-8")), "Downloaded resource range interception failed");
            require(store.file(pinned.getEntries().getJSONObject("/assets/__update_test__.png")) != null, "Blob missing");
            // Repair an active version whose formerly available bytes disappeared.
            File missing = store.file(first.getEntries().getJSONObject("/assets/__update_test__.png"));
            require(missing != null && missing.delete(), "Could not remove isolated fixture blob");
            store.apply(first, copy(runner, 1, false), progress);
            require(store.file(first.getEntries().getJSONObject("/assets/__update_test__.png")) != null, "Active version repair failed");
            result.putBoolean("missingResourceRepair", true);
            // The actual packaged baseline is still intact.
            require(runner.getTargetContext().getAssets().open("flutter_assets/assets/game/index.json").read() >= 0, "APK baseline missing");
            try { store.apply(second, copy(runner, 2, true), progress); throw new AssertionError("Corrupt pack accepted"); }
            catch (IllegalStateException expected) {}
            require(store.active().getSequence() == 1, "Corrupt pack changed active resources");
            UpdateDownload cancel = new UpdateDownload() {
                public void checkCancelled() { throw new IllegalStateException("cancelled"); }
                public void download(String url, File target, long size, Function1<? super Long, Unit> callback) { throw new AssertionError("Download after cancellation"); }
            };
            try { store.apply(second, cancel, progress); throw new AssertionError("Cancelled update activated"); }
            catch (IllegalStateException expected) {}
            require(store.active().getSequence() == 1, "Cancelled update changed active resources");
            store.apply(second, copy(runner, 2, false), progress);
            require(store.active().getSequence() == 2 && pinned.getSequence() == 1, "Pinned session changed");
            require(store.file(pinned.getEntries().getJSONObject("/assets/__update_test__.png")) != null, "Pinned old blob deleted");
            require(clients[0].shouldInterceptRequest(web[0], request(first, false)) != null, "Open game lost pinned resources");
            require(clients[0].shouldInterceptRequest(web[0], request(second, false)) == null, "Open game switched resource versions mid-session");
            result.putBoolean("nativeResourceHook", true);
            runner.runOnMainSync(() -> game[0].finish()); game[0] = null;
            store.rollback();
            require(store.active().getSequence() == 1, "Rollback failed");
            try { store.apply(second, copy(runner, 2, false), progress); throw new AssertionError("Replay accepted after rollback"); }
            catch (IllegalArgumentException expected) {}
            result.putBoolean("signedTransactions", true);
            testZipImports(runner, result);
            ProxySelector selector = ProxySelector.getDefault();
            CookieHandler cookies = CookieHandler.getDefault();
            for (String mode : Arrays.asList("direct", "http", "socks", "accelerator", "system")) {
                runner.getTargetContext().getSharedPreferences("client_updates", 0).edit()
                    .putString("mode", mode).putString("host", "127.0.0.1").putInt("port", 3128)
                    .putString("prefix", "https://example.com").commit();
                new UpdateTransport(runner.getTargetContext()).cancel();
                require(selector == ProxySelector.getDefault() && cookies == CookieHandler.getDefault(), "Global networking changed");
            }
            result.putBoolean("proxyIsolation", true);
            runner.getTargetContext().getSharedPreferences("client_updates", 0).edit().clear().commit();
            activity[0] = runner.startActivitySync(new Intent().setClassName(runner.getTargetContext(), "top.ppparty.stronghold_client.UpdateActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runner.waitForIdleSync();
            final Activity shown = activity[0];
            JSONObject config = new JSONObject(new String(read(runner.getTargetContext().getAssets().open("update-source.json")), "UTF-8"));
            String expectedSource = config.optString("repository").isEmpty() ? "尚未配置更新源" : config.getString("repository");
            runner.runOnMainSync(() -> {
                View root = shown.getWindow().getDecorView();
                require(find(root, expectedSource) != null, "Update source status missing");
                View check = find(root, "检查更新 / 测试连接"); require(check != null, "Check button missing");
                if (config.optString("repository").isEmpty()) check.performClick();
            });
            Thread.sleep(500);
            runner.runOnMainSync(() -> require(find(shown.getWindow().getDecorView(), expectedSource) != null, "Update source UI failed"));
            File chosen = new File(runner.getTargetContext().getCacheDir(), "self-packed-materials.zip");
            UpdateStore uiStore = new UpdateStore(runner.getTargetContext());
            JSONObject importedEntries = uiStore.active().getEntries();
            String path = importedEntries.keys().next();
            writeZip(chosen, new String[]{path.substring(1)}, new byte[][]{read(new FileInputStream(uiStore.file(importedEntries.getJSONObject(path))))});
            runner.runOnMainSync(() -> ((UpdateActivity) shown).onActivityResult(642, Activity.RESULT_OK,
                new Intent().setData(Uri.fromFile(chosen))));
            long importDeadline = System.currentTimeMillis() + 15000;
            boolean[] imported = {false};
            while (!imported[0] && System.currentTimeMillis() < importDeadline) {
                runner.runOnMainSync(() -> imported[0] = find(shown.getWindow().getDecorView(), "已导入并启用") != null);
                Thread.sleep(100);
            }
            require(imported[0], "ZIP selection callback did not import materials");
            runner.runOnMainSync(() -> require(find(shown.getWindow().getDecorView(), "100.0%") != null, "Download/import progress UI missing"));
            chosen.delete(); result.putBoolean("zipSelectionUi", true); result.putBoolean("visibleProgress", true);
            result.putBoolean("updateUi", true); result.putBoolean("ok", true);
        } catch (Throwable error) {
            result.putString("error", android.util.Log.getStackTraceString(error));
        } finally {
            if (game[0] != null) runner.runOnMainSync(() -> game[0].finish());
            if (activity[0] != null) runner.runOnMainSync(() -> activity[0].finish());
            remove(store.getRoot());
            runner.getTargetContext().getSharedPreferences("client_updates", 0).edit().clear().commit();
        }
        runner.finish(result.getBoolean("ok") ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }
    private static void testZipImports(Instrumentation runner, Bundle result) throws Exception {
        UpdateStore store = new UpdateStore(runner.getTargetContext());
        remove(store.getRoot());
        runner.getTargetContext().getSharedPreferences("client_updates", 0).edit().clear().commit();
        store = new UpdateStore(runner.getTargetContext());
        UpdateStore.Release first = store.parse(read(runner.getContext().getAssets().open("update-fixtures/1/update-preview.json")));
        UpdateStore.Release second = store.parse(read(runner.getContext().getAssets().open("update-fixtures/2/update-preview.json")));
        String packName = first.getJson().getJSONArray("packs").getJSONObject(0).getString("url");
        packName = packName.substring(packName.lastIndexOf('/') + 1);
        byte[] packed = read(runner.getContext().getAssets().open("update-fixtures/1/" + packName));
        File archive = new File(store.getStaging(), "selected.zip");
        Function1<TransferProgress, Unit> progress = value -> Unit.INSTANCE;
        writeZip(archive, new String[]{"update-preview.json", packName}, new byte[][]{first.getEnvelope(), packed});
        require(store.importZip(archive, null, () -> Unit.INSTANCE, progress).contains("已导入并启用"), "Full offline import failed");
        require(store.active().getSequence() == 1, "Offline package did not activate");
        writeZip(archive, new String[]{"../escape.png"}, new byte[][]{new byte[]{1}});
        try { store.importZip(archive, second, () -> Unit.INSTANCE, progress); throw new AssertionError("Traversal accepted"); }
        catch (Exception expected) { require(expected instanceof IllegalArgumentException || expected instanceof java.util.zip.ZipException, "Unexpected traversal rejection"); }
        require(store.active().getSequence() == 1, "Invalid ZIP changed active version");
        writeZip(archive, new String[]{"assets/__update_test__.png"}, new byte[][]{"fixture-version-bad".getBytes("UTF-8")});
        try { store.importZip(archive, second, () -> Unit.INSTANCE, progress); throw new AssertionError("Corrupt raw art accepted"); }
        catch (IllegalArgumentException expected) {}
        writeZip(archive, new String[]{"assets/__update_test__.png"}, new byte[][]{"fixture-version-two".getBytes("UTF-8")});
        store.importZip(archive, second, () -> Unit.INSTANCE, progress);
        require(store.active().getSequence() == 2, "Signed-index raw art import failed");
        store.importZip(archive, first, () -> Unit.INSTANCE, progress);
        require(store.active().getSequence() == 2, "Older import index downgraded active resources");
        remove(store.getRoot());
        runner.getTargetContext().getSharedPreferences("client_updates", 0).edit().clear().commit();
        store = new UpdateStore(runner.getTargetContext());
        UpdateStore.Release batch = store.parse(read(runner.getContext().getAssets().open("update-fixtures/3/update-preview.json")));
        JSONObject pack = batch.getJson().getJSONArray("packs").getJSONObject(0);
        String name = pack.getString("url").substring(pack.getString("url").lastIndexOf('/') + 1);
        archive = new File(store.getStaging(), "single-pack.zip");
        try (OutputStream out = new FileOutputStream(archive)) { out.write(read(runner.getContext().getAssets().open("update-fixtures/3/" + name))); }
        store.remember(batch);
        require(new UpdateStore(runner.getTargetContext()).checked().getSequence() == 3, "Trusted index was not retained for later imports");
        String partial = store.importZip(archive, null, () -> Unit.INSTANCE, progress);
        require(partial.contains("仍缺") && store.active() == null, "Partial package incorrectly activated");
        java.util.concurrent.atomic.AtomicInteger downloads = new java.util.concurrent.atomic.AtomicInteger();
        UpdateDownload source = copy(runner, 3, false);
        UpdateDownload counted = new UpdateDownload() {
            public void checkCancelled() {}
            public void download(String url, File file, long size, Function1<? super Long, Unit> callback) { downloads.incrementAndGet(); source.download(url, file, size, callback); }
        };
        java.util.List<TransferProgress> events = new java.util.ArrayList<>();
        store.apply(batch, counted, value -> Unit.INSTANCE, value -> { events.add(value); return Unit.INSTANCE; });
        require(store.active().getSequence() == 3 && downloads.get() == batch.getJson().getJSONArray("packs").length() - 1, "Imported package downloaded again");
        TransferProgress last = events.get(events.size() - 1);
        require(last.getCompletedBytes() == last.getTotalBytes() && last.getFraction() == 1000, "Aggregate progress did not complete");
        result.putBoolean("zipImports", true); result.putBoolean("partialImportReuse", true); result.putBoolean("aggregateProgress", true);
    }
    private static void writeZip(File file, String[] names, byte[][] contents) throws IOException {
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(new FileOutputStream(file))) {
            for (int i = 0; i < names.length; i++) { zip.putNextEntry(new java.util.zip.ZipEntry(names[i])); zip.write(contents[i]); zip.closeEntry(); }
        }
    }

    /** Uses the real GitHub API and HTTPS Release redirects; no transport replacement. */
    public static void online(Instrumentation runner) {
        Bundle result = new Bundle();
        Activity[] activity = {null};
        try {
            activity[0] = runner.startActivitySync(new Intent().setClassName(runner.getTargetContext(), "top.ppparty.stronghold_client.UpdateActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Activity shown = activity[0];
            runner.waitForIdleSync();
            runner.runOnMainSync(() -> {
                View check = find(shown.getWindow().getDecorView(), "检查更新 / 测试连接");
                require(check != null, "Update check button missing"); check.performClick();
            });
            long deadline = System.currentTimeMillis() + 90000;
            boolean[] verified = {false};
            while (!verified[0] && System.currentTimeMillis() < deadline) {
                runner.runOnMainSync(() -> verified[0] = find(shown.getWindow().getDecorView(), "已验证更新：") != null);
                Thread.sleep(200);
            }
            require(verified[0], "Real GitHub signed manifest check failed");
            result.putBoolean("githubSignatureVerified", true);
            runner.runOnMainSync(() -> find(shown.getWindow().getDecorView(), "下载并启用素材更新").performClick());
            UpdateStore store = new UpdateStore(runner.getTargetContext());
            deadline = System.currentTimeMillis() + 30000;
            while (store.active() == null && System.currentTimeMillis() < deadline) Thread.sleep(200);
            UpdateStore.Release active = store.active();
            require(active != null, "Real signed resources did not activate");
            result.putString("resourceVersion", active.getLabel());
            result.putLong("sequence", active.getSequence());
            result.putBoolean("resourceActivation", true);
            android.graphics.Bitmap shot = runner.getUiAutomation().takeScreenshot();
            if (shot != null) try(OutputStream output=new FileOutputStream(new File(runner.getTargetContext().getExternalFilesDir(null), "update-online.png"))) {
                shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
            }
            result.putBoolean("ok", true);
        } catch (Throwable error) { result.putString("error", android.util.Log.getStackTraceString(error)); }
        finally { if(activity[0]!=null) runner.runOnMainSync(() -> activity[0].finish()); }
        runner.finish(result.getBoolean("ok") ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }
    private static WebResourceRequest request(UpdateStore.Release release, boolean range) throws JSONException {
        String hash = release.getEntries().getJSONObject("/assets/__update_test__.png").getJSONArray("hashes").getString(0);
        Uri uri = Uri.parse("https://spa.ppparty.top:8000/assets/~" + hash + "/__update_test__.png");
        return new WebResourceRequest() {
            public Uri getUrl() { return uri; }
            public boolean isForMainFrame() { return false; }
            public boolean isRedirect() { return false; }
            public boolean hasGesture() { return false; }
            public String getMethod() { return "GET"; }
            public Map<String,String> getRequestHeaders() { return range ? Collections.singletonMap("Range", "bytes=0-3") : Collections.emptyMap(); }
        };
    }
    private static WebView findWeb(View view) {
        if(view instanceof WebView) return (WebView)view;
        if(view instanceof ViewGroup) { ViewGroup group=(ViewGroup)view; for(int i=0;i<group.getChildCount();i++) { WebView found=findWeb(group.getChildAt(i)); if(found!=null)return found; } }
        return null;
    }
    private static UpdateDownload copy(Instrumentation runner, int sequence, boolean corrupt) {
        return new UpdateDownload() {
            public void checkCancelled() {}
            public void download(String url, File target, long size, Function1<? super Long, Unit> callback) {
                try {
                    String name = url.substring(url.lastIndexOf('/')+1);
                    byte[] data = read(runner.getContext().getAssets().open("update-fixtures/"+sequence+"/"+name));
                    if (corrupt) data[data.length/2] ^= 1;
                    try (OutputStream output = new FileOutputStream(target)) { output.write(data); }
                    callback.invoke((long) data.length);
                } catch (IOException error) { throw new RuntimeException(error); }
            }
        };
    }
    private static byte[] read(InputStream stream) throws IOException {
        try (InputStream input=stream; ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] data = new byte[16384]; int n;
            while ((n=input.read(data)) >= 0) out.write(data,0,n);
            return out.toByteArray();
        }
    }
    private static void remove(File file) {
        File[] children=file.listFiles(); if(children!=null) for(File child:children) remove(child);
        file.delete();
    }
    private static View find(View view, String text) {
        if(view instanceof TextView && ((TextView)view).getText().toString().contains(text)) return view;
        if(view instanceof ViewGroup) { ViewGroup group=(ViewGroup)view; for(int i=0;i<group.getChildCount();i++) { View found=find(group.getChildAt(i),text); if(found!=null)return found; } }
        return null;
    }
    private static void require(boolean condition,String text) { if(!condition)throw new AssertionError(text); }
}
