package com.mill.wpengine;

import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;
import android.widget.Toast;

import java.util.Date;

/**
 * 视频壁纸引擎服务
 *
 * 多进程sp访问，主工程里面调用：
 * SharedPreferences sp = context.getSharedPreferences(SP_EXPORT_FILE, Context.MODE_MULTI_PROCESS | Context.MODE_WORLD_READABLE);
    sp.edit().putString(SP_KEY_FIRST_SET_FILEPATH, videoFilePath).commit();

 */
public class VideoLiveWallpaper extends WallpaperService {
    public static final String TAG = "VideoLiveWallpaper";

    public static final String VIDEO_WP_ENGINE_PRE = "video_wp_engine";

    public static final String AP_APK_NAME = "<主工程包名>";

    public static final String SP_VIDEO_FILE_DEFAULT = "<默认视频路径，建议放远程路径>";
    public static final String SP_EXPORT_FILE = "plugin_vwp_export";
    public static final String SP_KEY_FIRST_SET_FILEPATH = "KEY_FIRST_SET_FILEPATH";
    public static final String SP_KEY_VIDEODESKTOP_USED_LAST_TIME = "VideoDesktop_Used_Last_Time";
    public static final String SP_KEY_ALIVE_AP_LAST_TIME = "alive_ap_last_time";

    public final static String THREAD_NAME_ALIVE_AP = "alive_thread";        // 视频壁纸引擎APK，拉活
    public static final String VIDEO_PARAMS_CONTROL_ACTION = "com.zhy.livewallpaper";
    public static final String KEY_VOLUME_WPSETTING = "KEY_Volume_WPSetting";
    public static final String KEY_ACTION = "action";
    public static final int ACTION_VOICE_SILENCE = 110;
    public static final int ACTION_VOICE_NORMAL = 111;

    private String mVideoFilePath = null;
    private VideoEngine mEngine;
    private MediaPlayer mMediaPlayer;

    //拉活 线程
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private Runnable mAliveRunnable;
    private boolean isAliveThreadRun = false;

    public Engine onCreateEngine() {
        mEngine = new VideoEngine();
        if (mVideoFilePath == null) {
            mVideoFilePath = getSPPath();
        }
        return mEngine;
    }

    /**
     * 获取本地存的路径
     */
    public String getSPPath() {
        String filePath = null;
        try {
            Context c = getApplicationContext().createPackageContext(AP_APK_NAME, Context.CONTEXT_IGNORE_SECURITY);
            SharedPreferences sp = c.getSharedPreferences(SP_EXPORT_FILE, Context.MODE_WORLD_READABLE | Context.MODE_MULTI_PROCESS);
            filePath = sp.getString(SP_KEY_FIRST_SET_FILEPATH, null);

            if (filePath != null) {
                getApplicationContext().getSharedPreferences(VideoLiveWallpaper.VIDEO_WP_ENGINE_PRE, MODE_PRIVATE).edit().putString(SP_KEY_FIRST_SET_FILEPATH, filePath).commit();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        /**
         * 引擎保存的SP里面取
         */
        if (filePath == null) {
            filePath = getApplicationContext().getSharedPreferences(VideoLiveWallpaper.VIDEO_WP_ENGINE_PRE, MODE_PRIVATE).getString(SP_KEY_FIRST_SET_FILEPATH, null);
        }

        /**
         * 默认壁纸
         * 暂时先放个网络路径
         * 防止系统设置-显示-动态壁纸，选择手助壁纸后是黑的
         */
        if (filePath == null) {
            filePath = SP_VIDEO_FILE_DEFAULT;
        }
        return filePath;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mHandlerThread = new HandlerThread(THREAD_NAME_ALIVE_AP);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mAliveRunnable = new Runnable() {
            @Override
            public void run() {
                // 拉活代码......
                isAliveThreadRun = false;
            }
        };
    }

    @Override
    public void onDestroy() {
        if (mEngine != null) {
            mEngine.destroy();
        }
        if (mHandlerThread != null) {
            mAliveRunnable = null;
            mHandler.removeCallbacksAndMessages(null);
            mHandler = null;
            mHandlerThread.quit();
            mHandlerThread = null;
        }
        super.onDestroy();
    }

    private class VideoEngine extends Engine implements MediaPlayer.OnErrorListener {

        private BroadcastReceiver mVideoParamsControlReceiver;

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);

            IntentFilter intentFilter = new IntentFilter(VIDEO_PARAMS_CONTROL_ACTION);
            registerReceiver(mVideoParamsControlReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    int action = intent.getIntExtra(KEY_ACTION, -1);
                    getApplicationContext().getSharedPreferences(VideoLiveWallpaper.VIDEO_WP_ENGINE_PRE, MODE_PRIVATE).edit().putInt(KEY_VOLUME_WPSETTING, action).commit();
                    setVolumeByWPSetting();
                }
            }, intentFilter);
        }

        /**
         * 根据视频桌面插件里面的设置来判断
         * 本地存储下状态，方便开机时
         */
        private void setVolumeByWPSetting() {
            int action = getApplicationContext().getSharedPreferences(VideoLiveWallpaper.VIDEO_WP_ENGINE_PRE, MODE_PRIVATE).getInt(KEY_VOLUME_WPSETTING, -1);
            switch (action) {
                case ACTION_VOICE_NORMAL:
                    if (mMediaPlayer != null) {
                        mMediaPlayer.setVolume(1.0f, 1.0f);
                    }
                    break;
                case ACTION_VOICE_SILENCE:
                    if (mMediaPlayer != null) {
                        mMediaPlayer.setVolume(0, 0);
                    }
                    break;
            }
        }

        @Override
        public void onDestroy() {
            if (mVideoParamsControlReceiver != null) {
                unregisterReceiver(mVideoParamsControlReceiver);
            }
            super.onDestroy();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            if (mMediaPlayer != null) {
                if (visible) {
                    aliveAp();
//                    checkAPInstalled();
                    stateUsed();
                    startCheckPath();
                } else {
                    mMediaPlayer.pause();
                }
            }
        }

        /**
         * 是否安装了pkgName
         *
         * @param pkgName
         * @return
         */
        private boolean isAppInstalled(String pkgName) {
            boolean result = false;
            try {
                result = getPackageManager().getPackageInfo(pkgName, PackageManager.GET_DISABLED_COMPONENTS) != null;
            } catch (Exception e) {
                e.printStackTrace();
            }
            return result;
        }

        /**
         * 检测主工程是否安装了，否则清掉视频壁纸
         */
        private void checkAPInstalled() {
            if (!isAppInstalled(AP_APK_NAME) && isLiveWallpaperRunning()) {
                //手助卸载了，则清掉视频壁纸
                try {
                    WallpaperManager wallpaperManager = WallpaperManager.getInstance(getApplicationContext());
                    wallpaperManager.clear();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        /**
         * 拉活 主工程
         */
        private void aliveAp() {
            //拉活
//            long lastTime = getApplicationContext().getSharedPreferences(VideoLiveWallpaper.VIDEO_WP_ENGINE_PRE, MODE_PRIVATE).getLong(SP_KEY_ALIVE_AP_LAST_TIME, 0);
//            if (!DateUtils.isToday(lastTime)) {
//                getApplicationContext().getSharedPreferences(VideoLiveWallpaper.VIDEO_WP_ENGINE_PRE, MODE_PRIVATE).edit().putLong(SP_KEY_ALIVE_AP_LAST_TIME, System.currentTimeMillis()).commit();
//            }
            if (mHandler != null) {
                if (!isAliveThreadRun) {
                    isAliveThreadRun = true;
                    mHandler.post(mAliveRunnable);
                }
            }
        }

        /**
         * 视频壁纸 是否启动了
         *
         * @return
         */
        private boolean isLiveWallpaperRunning() {
            WallpaperManager wallpaperManager = WallpaperManager.getInstance(getApplicationContext());// 得到壁纸管理器
            WallpaperInfo wallpaperInfo = wallpaperManager.getWallpaperInfo();// 如果系统使用的壁纸是动态壁纸话则返回该动态壁纸的信息,否则会返回null
            if (wallpaperInfo != null) { // 如果是动态壁纸,则得到该动态壁纸的包名,并与想知道的动态壁纸包名做比较
                String currentLiveWallpaperPackageName = wallpaperInfo.getPackageName();
                if (currentLiveWallpaperPackageName.equals(getPackageName())) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 每天打点一次，用户还在使用视频桌面
         */
        private void stateUsed() {
            if (isLiveWallpaperRunning()) {
                Date date = new Date();
                String curDate = date.getYear() + "-" + date.getMonth() + "-" + date.getDay();
                String lastDate = getApplicationContext().getSharedPreferences(VideoLiveWallpaper.VIDEO_WP_ENGINE_PRE, MODE_PRIVATE).getString(SP_KEY_VIDEODESKTOP_USED_LAST_TIME, null);
                if (!curDate.equals(lastDate)) {
                    getApplicationContext().getSharedPreferences(VideoLiveWallpaper.VIDEO_WP_ENGINE_PRE, MODE_PRIVATE).edit().putString(SP_KEY_VIDEODESKTOP_USED_LAST_TIME, curDate).commit();
                    //打点......
                }
            }
        }

        private void startCheckPath() {
            if (mMediaPlayer != null) {
                String spPath = getSPPath();
                if (spPath != null && !spPath.equals(mVideoFilePath)) {
                    mVideoFilePath = spPath;
                    try {
                        mMediaPlayer.reset();
                        mMediaPlayer.setDataSource(mVideoFilePath);
                        if (Build.VERSION.SDK_INT >= 16) {
                            mMediaPlayer.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                        }
                        mMediaPlayer.setLooping(true);
                        setVolumeByWPSetting();
                        mMediaPlayer.prepare();
                        mMediaPlayer.start();
                    } catch (Exception e) {
                        e.printStackTrace();
                        onError(mMediaPlayer, 0, 0);
                    }
                } else {
                    mMediaPlayer.start();
                }
            }
        }


        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            destroy();
            if (mMediaPlayer == null) {
                mMediaPlayer = new MediaPlayer();
                mMediaPlayer.setOnErrorListener(this);
                mMediaPlayer.setSurface(holder.getSurface());
            }
            startCheckPath();
        }

        public boolean onError(MediaPlayer mp, int what, int extra) {
            //视频播放，莫名其妙 黑屏，重新设置；
            Toast.makeText(getApplicationContext(), R.string.video_wallpaper_play_error, Toast.LENGTH_LONG).show();
            try {
                //销毁内存
                destroy();

                //清除壁纸
                WallpaperManager wallpaperManager = WallpaperManager.getInstance(getApplicationContext());
                wallpaperManager.clear();

                //重新设置视频壁纸
                Intent localIntent = new Intent();
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) {
                    localIntent.setAction(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
                    localIntent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT
                            , new ComponentName(getApplicationContext(), VideoLiveWallpaper.class));
                } else {
                    localIntent.setAction(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER);
                }
                localIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                localIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                getApplicationContext().startActivity(localIntent);
            } catch (Exception e) {
                e.printStackTrace();
            }
            return true;
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            super.onSurfaceDestroyed(holder);
        }

        public void destroy() {
            if (mMediaPlayer != null) {
                mMediaPlayer.stop();
                mMediaPlayer.reset();
                mMediaPlayer.release();
                mMediaPlayer = null;
            }
            //path清空
            mVideoFilePath = null;
        }
    }

}