package in.omerjerk.remotedroid.app;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import com.koushikdutta.async.ByteBufferList;
import com.koushikdutta.async.DataEmitter;
import com.koushikdutta.async.callback.CompletedCallback;
import com.koushikdutta.async.callback.DataCallback;
import com.koushikdutta.async.http.WebSocket;
import com.koushikdutta.async.http.server.AsyncHttpServer;
import com.koushikdutta.async.http.server.AsyncHttpServerRequest;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import in.umairkhan.remotedroid.R;

public class ServerService extends Service {

    private MediaCodec encoder = null;

    private static final String TAG = "omerjerk";
    public static final int BIT_RATE = 1024 * 1024;
    public static final int FPS = 24;

    static MediaProjection mMediaProjection;
    //private MediaProjectionManager mMediaProjectionManager;

    private int serverPort;
    private float bitrateRatio;

    private AsyncHttpServer server;
    private List<WebSocket> _sockets = new ArrayList<WebSocket>();

    Thread encoderThread = null;

    Handler mHandler;

    SharedPreferences preferences;

    static int deviceWidth;
    static int deviceHeight;
    Point resolution = new Point();

    private static boolean LOCAL_DEBUG = false;
    VideoWindow videoWindow = null;
    private VirtualDisplay virtualDisplay;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            //mMediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        }

    }

    public static final int NOTIFICATION_ID = 6000;
    public void createNotification() {
        Intent notificationIntent = new Intent(this, ServerService.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            App.getInstance().createNotificationChannel();

            NotificationCompat.Builder notificationBuilder = (new NotificationCompat.Builder(this, App.NOTIFICATION_CHANNEL_ID))
                    .setLargeIcon(BitmapFactory.decodeResource(this.getResources(), R.drawable.ic_stat_cast))
                    .setSmallIcon(R.drawable.ic_stat_cast)
                    .setContentTitle("Content Title")
                    .setContentText("Content Text")
                    .setTicker("ticker test")
                    .setContentIntent(pendingIntent);
            Notification notification = notificationBuilder.build();
            NotificationChannel channel = new NotificationChannel(App.NOTIFICATION_CHANNEL_ID, App.NOTIFICATION_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(App.NOTIFICATION_CHANNEL_DESC);
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

            notificationManager.createNotificationChannel(channel);
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private class ToastRunnable implements Runnable {
        String mText;

        public ToastRunnable(String text) {
            mText = text;
        }

        @Override
        public void run() {
            Toast.makeText(getApplicationContext(), mText, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Main Entry Point of the server code.
     * Create a WebSocket server and start the encoder.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            Intent data = intent.getParcelableExtra("data");
            int resultCode = intent.getIntExtra("resultCode", -1);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && data != null) {
                ServerService.mMediaProjection = MainActivity.mMediaProjectionManager.getMediaProjection(resultCode, data);
            }
        }

        if (intent != null && intent.getAction() == "STOP") {
            dispose();
            return START_NOT_STICKY;
        }
        if (server == null && intent.getAction().equals("START")) {
            preferences = PreferenceManager.getDefaultSharedPreferences(this);
            LOCAL_DEBUG = preferences.getBoolean("local_debugging", false);
            DisplayMetrics dm = new DisplayMetrics();
            Display mDisplay = ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
            mDisplay.getMetrics(dm);
            deviceWidth = dm.widthPixels;
            deviceHeight = dm.heightPixels;
            float resolutionRatio = Float.parseFloat(
                    preferences.getString(SettingsActivity.KEY_RESOLUTION_PREF, "0.25"));
            mDisplay.getRealSize(resolution);
            resolution.x = (int) (resolution.x * resolutionRatio);
            resolution.y = (int) (resolution.y * resolutionRatio);

            if (!LOCAL_DEBUG) {
                server = new AsyncHttpServer();
                server.websocket("/", null, websocketCallback);
                serverPort = Integer.parseInt(preferences.getString(SettingsActivity.KEY_PORT_PREF, "6060"));
                bitrateRatio = Float.parseFloat(preferences.getString(SettingsActivity.KEY_BITRATE_PREF, "1"));
                updateNotification("Streaming is live at");
                server.listen(serverPort);
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        showToast("Starting main touch server");
                        new MainStarter(ServerService.this).start();
                        showToast("started main touch server");
                    }
                }).start();
            } else {
                final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);

                params.gravity = Gravity.TOP | Gravity.LEFT;
                params.height = WindowManager.LayoutParams.WRAP_CONTENT;
                params.width = WindowManager.LayoutParams.WRAP_CONTENT;

                WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
                LayoutInflater inflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                videoWindow = (VideoWindow) inflater.inflate(R.layout.video_window, null);
                windowManager.addView(videoWindow, params);
                videoWindow.inflateSurfaceView();

                if (encoderThread == null) {
                    encoderThread = new Thread(new EncoderWorker(), "Encoder Thread");
                    encoderThread.start();
                }
            }

            mHandler = new Handler();
        }
        return START_NOT_STICKY;
    }

    private AsyncHttpServer.WebSocketRequestCallback websocketCallback = new AsyncHttpServer.WebSocketRequestCallback() {

        @Override
        public void onConnected(final WebSocket webSocket, AsyncHttpServerRequest request) {
            _sockets.add(webSocket);
            showToast("Someone just connected");
            //Start rendering display on the surface and setting up the encoder
            if (encoderThread == null) {
                startDisplayManager();
                encoderThread = new Thread(new EncoderWorker(), "Encoder Thread");
                encoderThread.start();
            }
            //Use this to clean up any references to the websocket
            webSocket.setClosedCallback(new CompletedCallback() {
                @Override
                public void onCompleted(Exception ex) {
                    try {
                        if (ex != null)
                            ex.printStackTrace();
                    } finally {
                        _sockets.clear();
                    }
                    showToast("Disconnected");
                    dispose();
                }
            });

            webSocket.setStringCallback(new WebSocket.StringCallback() {
                @Override
                public void onStringAvailable(String s) {
                    Log.d(TAG, "String received. No idea what to do with it.");
                }
            });

            webSocket.setDataCallback(new DataCallback() {
                @Override
                public void onDataAvailable(DataEmitter dataEmitter, ByteBufferList byteBufferList) {
                    byteBufferList.recycle();
                }
            });
        }
    };

    /**
     * Create the display surface out of the encoder. The data to encoder will be fed from this
     * Surface itself.
     * @return
     * @throws IOException
     */
    @TargetApi(19)
    private Surface createDisplaySurface() throws IOException {
        MediaFormat mMediaFormat = MediaFormat.createVideoFormat(CodecUtils.MIME_TYPE,
                CodecUtils.WIDTH, CodecUtils.HEIGHT);
        mMediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        mMediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        mMediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        mMediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        Log.i(TAG, "Starting encoder");
        encoder = MediaCodec.createEncoderByType(CodecUtils.MIME_TYPE);
        encoder.configure(mMediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        Surface surface = encoder.createInputSurface();
        return surface;
    }

    @TargetApi(19)
    public void startDisplayManager() {
        DisplayManager mDisplayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Surface encoderInputSurface = null;
        try {
            encoderInputSurface = createDisplaySurface();
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            virtualDisplay = mDisplayManager.createVirtualDisplay("Remote Droid", CodecUtils.WIDTH, CodecUtils.HEIGHT, 50,
                    encoderInputSurface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE);
        } else {
            if (mMediaProjection != null) {
                virtualDisplay = mMediaProjection.createVirtualDisplay("Remote Droid",
                        CodecUtils.WIDTH, CodecUtils.HEIGHT, 50,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        encoderInputSurface, null, null);
            } else {
                showToast("Something went wrong. Please restart the app.");
            }
        }

        encoder.start();
    }

    @TargetApi(19)
    private class EncoderWorker implements Runnable {

        @Override
        public void run() {
            startDisplayManager();
            ByteBuffer[] encoderOutputBuffers = encoder.getOutputBuffers();

            boolean encoderDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            String infoString;
            while (!encoderDone) {
                int encoderStatus;
                try {
                    encoderStatus = encoder.dequeueOutputBuffer(info, CodecUtils.TIMEOUT_USEC);
                } catch (IllegalStateException e) {
                    e.printStackTrace();
                    break;
                }

                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // no output available yet
                    //Log.d(TAG, "no output from encoder available");
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    // not expected for an encoder
                    encoderOutputBuffers = encoder.getOutputBuffers();
                    Log.d(TAG, "encoder output buffers changed");
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // not expected for an encoder
                    MediaFormat newFormat = encoder.getOutputFormat();
                    Log.d(TAG, "encoder output format changed: " + newFormat);
                } else if (encoderStatus < 0) {
                    break;
                } else {
                    ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                    if (encodedData == null) {
                        Log.d(TAG, "============It's NULL. BREAK!=============");
                        return;
                    }
                    if (!LOCAL_DEBUG) {
                        for (WebSocket socket : _sockets) {
                            infoString = info.offset + "," + info.size + "," +
                                    info.presentationTimeUs + "," + info.flags;
                            socket.send(infoString.getBytes());

                            byte[] b = new byte[info.size];
                            try {
                                if (info.size != 0) {
                                    encodedData.limit(info.offset + info.size);
                                    encodedData.position(info.offset);
                                    encodedData.get(b, info.offset, info.offset + info.size);
                                    socket.send(b);
                                }

                            } catch (BufferUnderflowException e) {
                                e.printStackTrace();
                            }
                        }
                    } else {
                        if (info.size != 0) {
                            encodedData.position(info.offset);
                            encodedData.limit(info.offset + info.size);
                        }
                        videoWindow.setData(CodecUtils.clone(encodedData), info);

                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            Log.w(TAG, "config flag received");
                        }
                    }

                    encoderDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;

                    try {
                        encoder.releaseOutputBuffer(encoderStatus, false);
                    } catch (IllegalStateException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        dispose();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void showToast(final String message) {
        mHandler.post(new ToastRunnable(message));
    }

    /**
     * Display the notification
     * @param message
     */
    private void updateNotification(String message) {
        Intent intent = new Intent(this, ServerService.class);
        intent.setAction("STOP");
        PendingIntent stopServiceIntent = PendingIntent.getService(this, 0, intent, 0);
        NotificationCompat.Builder mBuilder =
                new NotificationCompat.Builder(this, App.NOTIFICATION_CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_launcher)
                        .setOngoing(true)
                        .addAction(R.drawable.ic_media_stop, "Stop", stopServiceIntent)
                        .setContentTitle(message)
                        .setContentText(Utils.getIPAddress(true) + ":" + serverPort);
        startForeground(NOTIFICATION_ID, mBuilder.build());
    }

    private void dispose() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (virtualDisplay != null)
                virtualDisplay.release();
        }
        if (encoder != null) {
            encoder.signalEndOfInputStream();
            encoder.stop();
            encoder.release();
            encoder = null;
        }
        if (server != null) {
            server.stop();
            server = null;
        }
        stopForeground(true);
        stopSelf();
    }
}
