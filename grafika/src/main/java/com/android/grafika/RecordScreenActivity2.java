package com.android.grafika;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import com.android.grafika.MiscUtils;
import com.android.grafika.R;
import com.android.grafika.RecordFBOActivity;
import com.android.grafika.VideoEncoderCore;
import com.android.grafika.rtmpconnection1.VideoEncoderSimpleRtmp;
import com.android.grafika.rtmpconnection2.VideoEncoderRtmp;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;

/**
 * Created by Quynh on 10/31/2017.
 */

public class RecordScreenActivity2 extends Activity {

    private MediaProjectionManager mMediaProjectionManager;
    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;
    private int mWidth, mHeight, mDensity;
    private String TAG = "Record Screen";
    private static final int REQUEST_CODE_SCREEN_CAPTURE = 1;
    private volatile Surface mSurface;
    private int mTextureId, mBitRate;
    private VideoEncoderRtmp mVideoEncoder;
    private File outputFile;
    private SurfaceTexture mSurfaceTexture;
    private String mRtmpUrl = "rtmp://live-api-a.facebook.com:80/rtmp/1355739464551559?ds=1&a=ATiwTJab6sk21CQu";
    private static final int BIT_RATE =4000000;
    private RenderThread mRenderThread;
    private boolean mRecordingEnabled = true;
    private Handler mHandler =new Handler();
    private Runnable record = new Runnable() {
        @Override
        public void run() {
            Log.d(TAG, "Start draining Encoder");
            mVideoEncoder.drainEncoder(false);
            mHandler.postDelayed(this,10);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_record_screen);
        outputFile = new File(getFilesDir(), "screenshot-test.mp4");
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        mWidth = metrics.widthPixels;
        mHeight = metrics.heightPixels;
        mDensity = metrics.densityDpi;
        mBitRate = BIT_RATE;
        Log.d(TAG, "Create activity");

        //Tao moi mot encoder
        try {
            mVideoEncoder = new VideoEncoderRtmp(mWidth, mHeight, mBitRate, outputFile);
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
        mSurface = mVideoEncoder.getInputSurface();
        Log.d(TAG, "Set Surface");

        //Get permission to start recording
        mMediaProjectionManager = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        Intent permissionIntent = mMediaProjectionManager.createScreenCaptureIntent();
        startActivityForResult(permissionIntent, REQUEST_CODE_SCREEN_CAPTURE);

        Button btnRecord = (Button) findViewById(R.id.button_record_screen);
        btnRecord.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.d(TAG, "Status record: " + mRecordingEnabled);
                if (mRecordingEnabled) {
                    startRecording();
                } else stopRecording();
            }
        });
    }

    private static class RenderThread extends Thread {
        private String TAG = "Record Screen";
        private volatile RenderHandler mHandler;
        private Object mStartLock = new Object();
        private boolean mReady = false;
        private Surface mSurface;
        private File mOutputFile;
        private long mRefreshPeriodNanos;
        private VideoEncoderRtmp mVideoEncoder;


        public RenderThread(Surface surface, File outputFile,
                            long refreshPeriodNs, VideoEncoderRtmp videoEncoder) {
            mSurface = surface;
            mOutputFile = outputFile;
            mRefreshPeriodNanos = refreshPeriodNs;
            mVideoEncoder = videoEncoder;
        }


//        @Override
//        public void run() {
//            Log.d(TAG, "Start drawing");
//            Looper.prepare();
//            mHandler = new RenderHandler(this);
//            synchronized (mStartLock) {
//                mReady = true;
//                mStartLock.notify();    // signal waitUntilReady()
//                mVideoEncoder.drainEncoder(false);
//            }
//            Log.d(TAG,""+mReady);
//            Looper.loop();
//            Log.d(TAG, "looper quit");
//            synchronized (mStartLock) {
//                mReady = false;
//            }
//        }

        /**
         * Waits until the render thread is ready to receive messages.
         * <p>
         * Call from the UI thread.
         */
        public void waitUntilReady() {
            synchronized (mStartLock) {
                while (!mReady) {
                    try {
                        mStartLock.wait();
                    } catch (InterruptedException ie) { /* not expected */ }
                }
            }
        }



    }



    private static class RenderHandler extends Handler {
        private WeakReference<RenderThread> mWeakRenderThread;
        private static final int MSG_SURFACE_CREATED = 0;
        private static final int MSG_SURFACE_CHANGED = 1;
        private static final int MSG_SHUTDOWN = 5;

        /**
         * Call from render thread.
         */
        public RenderHandler(RenderThread rt) {
            mWeakRenderThread = new WeakReference<RenderThread>(rt);
        }
    }


    public void clickToggleRecording(@SuppressWarnings("unused") View unused) {
        mRecordingEnabled = !mRecordingEnabled;
        updateControls();
    }

    private void updateControls() {
        Button toggleRelease = (Button) findViewById(R.id.button_record_screen);
        int id = mRecordingEnabled ?
                R.string.toggleRecordingOff : R.string.toggleRecordingOn;
        toggleRelease.setText(id);

        //CheckBox cb = (CheckBox) findViewById(R.id.rebindHack_checkbox);
        //cb.setChecked(TextureRender.sWorkAroundContextProblem);
    }


    private void startRecording() {
        mVirtualDisplay = mMediaProjection.createVirtualDisplay("Capturing Display",
                mWidth, mHeight, mDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mSurface, null, null);
        mVideoEncoder.drainEncoder(false);
        Log.d(TAG, "Start recording");
        mRecordingEnabled = false;
    }

    private void stopRecording() {
        Log.d(TAG, "Stop recording");
        mHandler.removeCallbacks(record);
        mRecordingEnabled = true;
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
        if (mMediaProjection != null) {
            mMediaProjection.stop();
            mMediaProjection = null;
        }
        mVideoEncoder.drainEncoder(true);
        mVideoEncoder.release();
//        mSurfaceTexture.release();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (REQUEST_CODE_SCREEN_CAPTURE == requestCode) {
            if (resultCode != RESULT_OK) {
                Toast.makeText(this, "permission denied", Toast.LENGTH_LONG).show();
                return;
            } else
                mMediaProjection =
                        mMediaProjectionManager.getMediaProjection(resultCode, data);
            Log.d(TAG, "Start projection");
        }
    }


    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "Activity has been paused");
    }



}
