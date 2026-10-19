package top.ppparty.stronghold_client;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import java.io.*;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Drive the installed OLD client, including its real downloader and installer. */
public final class UpgradeSmoke {
    public static void download(Instrumentation runner) {
        Bundle result = new Bundle(); Activity[] activity = {null};
        try {
            UpdateStore store = new UpdateStore(runner.getTargetContext());
            require(store.installedCode() == 7, "Upgrade test must start with 0.2.1 / code 7");
            runner.getTargetContext().getSharedPreferences("client_display",0).edit().putInt("left",13).putInt("right",7).commit();
            activity[0] = start(runner);
            Activity shown = activity[0];
            click(runner, shown, "检查更新 / 测试连接");
            waitFor(90000, () -> has(runner, shown, "已验证更新："));
            File apk = new File(runner.getTargetContext().getFilesDir(), "updates/client.apk");
            JSONObject metadata = null;
            for(int attempt=0; attempt<3 && metadata==null; attempt++) {
                click(runner, shown, "下载客户端 APK");
                clickDialog(runner, "下载");
                long deadline=System.currentTimeMillis()+12*60*1000;
                Thread.sleep(500);
                while(System.currentTimeMillis()<deadline) {
                    String value=runner.getTargetContext().getSharedPreferences("client_updates",0).getString("verifiedApk",null);
                    if(value!=null) {
                        JSONObject candidate=new JSONObject(value);
                        if(candidate.getLong("versionCode")==8 && apk.isFile()) { metadata=candidate; break; }
                    }
                    boolean[] enabled={false};
                    runner.runOnMainSync(() -> { View button=find(shown.getWindow().getDecorView(), "下载客户端 APK",true); enabled[0]=button!=null && button.isEnabled(); });
                    if(enabled[0]) break; // A failed request can be retried and resumed by the old client.
                    Thread.sleep(500);
                }
            }
            require(metadata!=null,"Old client did not finish verifying new APK: "+text(runner,shown));
            require(apk.length()==metadata.getLong("bytes"),"Downloaded APK length mismatch");
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStream input=new FileInputStream(apk)) {
                byte[] buffer=new byte[131072];int n;while((n=input.read(buffer))>=0)digest.update(buffer,0,n);
            }
            StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format("%02x",b));
            require(hash.toString().equals(metadata.getString("sha256")),"Old-client downloaded APK hash mismatch");
            result.putBoolean("oldClientDownloadedAndVerified",true);
            result.putString("apkSha256",hash.toString()); result.putLong("targetCode",metadata.getLong("versionCode"));result.putBoolean("ok",true);
        } catch(Throwable error) { result.putString("error",android.util.Log.getStackTraceString(error)); }
        finally { if(activity[0]!=null)runner.runOnMainSync(() -> activity[0].finish()); }
        runner.finish(result.getBoolean("ok")?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
    public static void install(Instrumentation runner) {
        Activity shown=null;
        try {
            require(new UpdateStore(runner.getTargetContext()).installedCode()==7,"Installer test must start with old client");
            shown=start(runner);click(runner,shown,"安装已验证的 APK");clickDialog(runner,"继续安装");
            Bundle stage=new Bundle();stage.putBoolean("installerRequested",true);runner.sendStatus(1,stage);
            // Confirm only a button belonging to the SYSTEM package installer.
            waitFor(20000,() -> installerButton(runner)!=null);
            stage.putBoolean("systemInstallerConfirmed",true);runner.sendStatus(2,stage);
            require(installerButton(runner).performAction(AccessibilityNodeInfo.ACTION_CLICK),"Cannot confirm system APK update");
            // Replacement kills this instrumentation together with the old process.
            long deadline=System.currentTimeMillis()+120000;
            while(System.currentTimeMillis()<deadline)Thread.sleep(500);
            throw new AssertionError("System installer did not replace the old client");
        } catch(Throwable error) {
            Bundle result=new Bundle();result.putString("error",android.util.Log.getStackTraceString(error));runner.finish(Activity.RESULT_CANCELED,result);
        }
    }
    public static void verify(Instrumentation runner) {
        Bundle result=new Bundle();
        try {
            long code=new UpdateStore(runner.getTargetContext()).installedCode();
            require(code==8,"System installer did not install 0.2.2");
            android.content.SharedPreferences prefs=runner.getTargetContext().getSharedPreferences("client_display",0);
            require(prefs.getInt("left",-1)==13 && prefs.getInt("right",-1)==7,"Upgrade lost manual margin settings");
            result.putLong("installedCode",code);result.putBoolean("manualMarginsPreserved",true);result.putBoolean("ok",true);
            prefs.edit().putInt("left",0).putInt("top",0).putInt("right",0).putInt("bottom",0).commit();
        } catch(Throwable error){result.putString("error",android.util.Log.getStackTraceString(error));}
        runner.finish(result.getBoolean("ok")?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
    private static AccessibilityNodeInfo installerButton(Instrumentation runner) {
        AccessibilityNodeInfo root=runner.getUiAutomation().getRootInActiveWindow();if(root==null)return null;
        String pkg=String.valueOf(root.getPackageName());
        if(!pkg.contains("packageinstaller") && !pkg.contains("permissioncontroller"))return null;
        for(String text:new String[]{"Update","Install","更新","安装"}) {
            for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(text)) {
                if(node.isClickable() && node.isEnabled() && text.contentEquals(node.getText()==null?"":node.getText()))return node;
            }
        }return null;
    }
    private static Activity start(Instrumentation runner) {
        Activity activity=runner.startActivitySync(new Intent().setClassName(runner.getTargetContext(),"top.ppparty.stronghold_client.UpdateActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        runner.waitForIdleSync();return activity;
    }
    private static void click(Instrumentation runner,Activity activity,String label) {
        runner.runOnMainSync(() -> { View button=find(activity.getWindow().getDecorView(),label,true);require(button!=null,"Missing button: "+label);button.performClick(); });
    }
    private static void clickDialog(Instrumentation runner,String label) throws Exception {
        waitFor(5000,() -> dialogNode(runner,label)!=null);
        require(dialogNode(runner,label).performAction(AccessibilityNodeInfo.ACTION_CLICK),"Cannot confirm "+label);
    }
    private static AccessibilityNodeInfo dialogNode(Instrumentation runner,String label) {
        AccessibilityNodeInfo root=runner.getUiAutomation().getRootInActiveWindow();if(root==null)return null;
        for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(label)) {
            if(node.isClickable() && label.contentEquals(node.getText()==null?"":node.getText()))return node;
        }return null;
    }
    private static boolean has(Instrumentation runner,Activity activity,String label) {
        boolean[] found={false};runner.runOnMainSync(() -> found[0]=find(activity.getWindow().getDecorView(),label,false)!=null);return found[0];
    }
    private static View find(View view,String label,boolean exact) {
        if(view instanceof TextView) { String text=((TextView)view).getText().toString();if(exact?text.equals(label):text.contains(label))return view; }
        if(view instanceof ViewGroup) { ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=find(group.getChildAt(i),label,exact);if(found!=null)return found;} }return null;
    }
    private static String text(Instrumentation runner,Activity activity) {
        StringBuilder out=new StringBuilder();runner.runOnMainSync(() -> collect(activity.getWindow().getDecorView(),out));return out.toString();
    }
    private static void collect(View view,StringBuilder out) {
        if(view instanceof TextView)out.append(((TextView)view).getText()).append('\n');
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)collect(group.getChildAt(i),out);}
    }
    private interface Condition { boolean get() throws Exception; }
    private static void waitFor(long timeout,Condition condition) throws Exception {
        long deadline=System.currentTimeMillis()+timeout;while(System.currentTimeMillis()<deadline){if(condition.get())return;Thread.sleep(200);}throw new AssertionError("Timed out");
    }
    private static void require(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
}
