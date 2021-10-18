package com.raincat.dolby_beta;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Environment;

import com.raincat.dolby_beta.helper.ClassHelper;
import com.raincat.dolby_beta.helper.ExtraHelper;
import com.raincat.dolby_beta.helper.FileHelper;
import com.raincat.dolby_beta.helper.SettingHelper;
import com.raincat.dolby_beta.hook.AdAndUpdateHook;
import com.raincat.dolby_beta.hook.AutoSignInHook;
import com.raincat.dolby_beta.hook.BlackHook;
import com.raincat.dolby_beta.hook.CdnHook;
import com.raincat.dolby_beta.hook.DownloadMD5Hook;
import com.raincat.dolby_beta.hook.EAPIHook;
import com.raincat.dolby_beta.hook.GrayHook;
import com.raincat.dolby_beta.hook.InternalDialogHook;
import com.raincat.dolby_beta.hook.MagiskFixHook;
import com.raincat.dolby_beta.hook.ProxyHook;
import com.raincat.dolby_beta.hook.SettingHook;
import com.raincat.dolby_beta.hook.UserProfileHook;
import com.raincat.dolby_beta.utils.Tools;

import java.io.File;
import java.io.IOException;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * <pre>
 *     author : RainCat
 *     e-mail : nining377@gmail.com
 *     time   : 2021/09/22
 *     desc   : 说明
 *     version: 1.0
 * </pre>
 */

public class Hook {
    private final static String PACKAGE_NAME = "com.netease.cloudmusic";
    //进程初始化状态
    public boolean playProcessInit = false;
    public boolean mainProcessInit = false;
    //主线程反编译dex完成后通知可以对play进程进行hook了
    private final String msg_hookPlayProcess = "hookPlayProcess";
    //play进程初始化完成通知主线程
    private final String msg_playProcessInitFinish = "playProcessInitFinish";

    public Hook(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod(XposedHelpers.findClass("com.netease.cloudmusic.NeteaseMusicApplication", lpparam.classLoader),
                "attachBaseContext", Context.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        final Context context = (Context) param.thisObject;
                        final int versionCode = context.getPackageManager().getPackageInfo(PACKAGE_NAME, 0).versionCode;
                        //初始化仓库
                        ExtraHelper.init(context);
                        //初始化设置
                        SettingHelper.init(context);

                        final String processName = Tools.getCurrentProcessName(context);
                        if (processName.equals(PACKAGE_NAME)) {
                            //设置
                            new SettingHook(context);
                            //总开关
                            if (!SettingHelper.getInstance().getSetting(SettingHelper.master_key))
                                return;
                            //音源代理
                            new ProxyHook(context, versionCode, false);
                            //黑胶
                            if (SettingHelper.getInstance().isEnable(SettingHelper.black_key)) {
                                new BlackHook(context, versionCode);
                                deleteAdAndTinker();
                            }
                            //不变灰
                            new GrayHook(context);
                            //自动签到
                            new AutoSignInHook(context, versionCode);
                            //去广告与去升级
                            new AdAndUpdateHook(context, versionCode);
                            //修复magisk冲突导致的无法读写外置sd卡
                            new MagiskFixHook(context);
                            //去掉内测与听歌识曲弹窗
                            new InternalDialogHook(context, versionCode);
                            ClassHelper.getCacheClassList(context, versionCode, () -> {
                                //获取账号信息
                                new UserProfileHook(context);
                                //网络访问
                                new EAPIHook(context);
                                //下载MD5校验
                                new DownloadMD5Hook();
                                new CdnHook(context, versionCode);

                                mainProcessInit = true;
                                if (mainProcessInit && playProcessInit)
                                    context.sendBroadcast(new Intent(msg_hookPlayProcess));
                            });
                            IntentFilter intentFilter = new IntentFilter();
                            intentFilter.addAction(msg_playProcessInitFinish);
                            context.registerReceiver(new BroadcastReceiver() {
                                @Override
                                public void onReceive(Context c, Intent intent) {
                                    playProcessInit = true;
                                    if (mainProcessInit && playProcessInit)
                                        context.sendBroadcast(new Intent(msg_hookPlayProcess));
                                }
                            }, intentFilter);
                        } else if (processName.equals(PACKAGE_NAME + ":play") && SettingHelper.getInstance().getSetting(SettingHelper.master_key)) {
                            //音源代理
                            new ProxyHook(context, versionCode, true);
                            IntentFilter intentFilter = new IntentFilter();
                            intentFilter.addAction(msg_hookPlayProcess);
                            context.registerReceiver(new BroadcastReceiver() {
                                @Override
                                public void onReceive(Context c, Intent intent) {
                                    if (msg_hookPlayProcess.equals(intent.getAction())) {
                                        ClassHelper.getCacheClassList(context, versionCode, () -> {
                                            new EAPIHook(context);
                                            new CdnHook(context, versionCode);
                                        });
                                    }
                                }
                            }, intentFilter);
                            context.sendBroadcast(new Intent(msg_playProcessInitFinish));
                        }
                    }
                });

        //关闭tinker
        Class<?> tinkerClass = XposedHelpers.findClassIfExists("com.tencent.tinker.loader.app.TinkerApplication", lpparam.classLoader);
        if (tinkerClass != null)
            XposedHelpers.findAndHookConstructor(tinkerClass, int.class, String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    super.beforeHookedMethod(param);
                    param.args[0] = 0;
                }
            });
    }

    /**
     * 删掉广告和热修复
     */
    private void deleteAdAndTinker() throws IOException {
        //广告缓存路径
        String CACHE_PATH = Environment.getExternalStorageDirectory() + "/netease/cloudmusic/Ad";
        String CACHE_PATH2 = Environment.getExternalStorageDirectory() + "/Android/data/com.netease.cloudmusic/cache/Ad";
        String TINKER_PATH = "data/data/" + PACKAGE_NAME + "/tinker";

        FileHelper.deleteDirectory(CACHE_PATH);
        FileHelper.deleteDirectory(CACHE_PATH2);

        File tinkerFile = new File(TINKER_PATH);
        if (tinkerFile.exists() && tinkerFile.isDirectory())
            FileHelper.deleteDirectory(TINKER_PATH);
        if (!tinkerFile.exists())
            tinkerFile.createNewFile();

        String command = "chmod 000 " + tinkerFile.getAbsolutePath();
        Runtime runtime = Runtime.getRuntime();
        runtime.exec(command);
    }
}
