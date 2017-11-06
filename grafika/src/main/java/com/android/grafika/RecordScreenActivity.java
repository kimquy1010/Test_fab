package com.android.grafika;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioRecord;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
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

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;

/**
 * Created by Quynh on 10/31/2017.
 */

public class RecordScreenActivity extends Activity implements SurfaceTexture.OnFrameAvailableListener {

    private MediaProjectionManager mMediaProjectionManager;
    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;
    private int mWidth, mHeight, mDensity;
    private String TAG = "Record Screen";
    private static final int REQUEST_CODE_SCREEN_CAPTURE = 1;
    private volatile Surface mSurface;
    private int mTextureId, mBitRate;
    private VideoEncoderSimpleRtmp mVideoEncoder;
    private File outputFile;
    private SurfaceTexture mSurfaceTexture;
    private String mRtmpUrl = "rtmp://live-api-a.facebook.com:80/rtmp/1360183120773860?ds=1&a=ATgVJyrFDFSNL-cl";
    private static final int BIT_RATE = 1200 * 1024;
    private boolean mRecordingEnabled = true;
    private static AudioRecord mic;
    private static AcousticEchoCanceler aec;
    private static AutomaticGainControl agc;
    private boolean sendVideoOnly = false;
    private Thread aworker, vworker;
    private final Object txFrameLock = new Object();
    private Handler mHandler = new Handler();
    private Runnable record = new Runnable() {
        @Override
        public void run() {
            Log.d(TAG, "Start draining Encoder");
            mVideoEncoder.onGetVideoFrame(false);
            mHandler.postDelayed(this, 33);
        }
    };
    private byte[] mPcmBuffer = new byte[4096];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_record_screen);
        outputFile = new File(getFilesDir(), "screenshot-test.mp4");
        getParametersScreen();
        Log.d(TAG, "Create activity");

        //Tao moi mot encoder
        try {
            mVideoEncoder = new VideoEncoderSimpleRtmp(mWidth, mHeight, mBitRate, this, mRtmpUrl);
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
                    startEncode();
                } else stopEncode();
            }
        });
    }
    private void getParametersScreen(){
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        mWidth = metrics.widthPixels;
        mHeight = metrics.heightPixels;
        mDensity = metrics.densityDpi;
        mWidth = 720;
        mHeight = 1280;
        mBitRate = BIT_RATE;
    }

    private void startEncode() {
        Log.d(TAG,"Start encoder");
        if (!mVideoEncoder.start()) {
            return;
        }
        startVideo();
//        startAudio();
    }
    private void stopEncode(){
        Log.d(TAG, "Stop encoder");
        stopVideo();
        stopAudio();
    }



    private void startVideo() {
        mVirtualDisplay = mMediaProjection.createVirtualDisplay("Capturing Display",
                mWidth, mHeight, mDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mSurface, null, null);
//        vworker = new Thread(new Runnable() {
//            @Override
//            public void run() {
//                Log.d(TAG, "Start run video encoder");
////                android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
//                while (!Thread.interrupted()) {
//                    mVideoEncoder.onGetVideoFrame(false);
//                    try {
//                        Thread.sleep(33);
//                    } catch (InterruptedException e) {
//                        Log.e(TAG, "Broken thread");
//                        break;
//                    }
//                }
//                // Waiting for next frame
//                synchronized (txFrameLock) {
//                    try {
//                        // isEmpty() may take some time, so we set timeout to detect next frame
//                        txFrameLock.wait(500);
//                    } catch (InterruptedException ie) {
//                        vworker.interrupt();
//                    }
//                }
//            }
//        });
//        vworker.start();
        mHandler.post(record);
//        mVideoEncoder.onGetVideoFrame(false);
        Log.d(TAG, "Start recording");
        mRecordingEnabled = false;
    }

    private void stopVideo() {
        Log.d(TAG, "Stop recording");
        mHandler.removeCallbacks(record);
        if (vworker != null) {
            vworker.interrupt();
            try {
                vworker.join();
            } catch (InterruptedException e) {
                vworker.interrupt();
            }
            vworker = null;
        }
        mRecordingEnabled = true;
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
        if (mMediaProjection != null) {
            mMediaProjection.stop();
            mMediaProjection = null;
        }
        mVideoEncoder.onGetVideoFrame(true);
        mVideoEncoder.release();
    }

    public void startAudio() {
        mic = mVideoEncoder.chooseAudioRecord();
        if (mic == null) {
            return;
        }

        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(mic.getAudioSessionId());
            if (aec != null) {
                aec.setEnabled(true);
                Log.d(TAG,"Start audio echo");
            }
        }

        if (AutomaticGainControl.isAvailable()) {
            agc = AutomaticGainControl.create(mic.getAudioSessionId());
            if (agc != null) {
                agc.setEnabled(true);
                Log.d(TAG,"Start audio gain");
            }
        }

        aworker = new Thread(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG,"Start run mic");
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
                mic.startRecording();
                while (!Thread.interrupted()) {
                    if (sendVideoOnly) {
                        mVideoEncoder.onGetPcmFrame(mPcmBuffer, mPcmBuffer.length);
                        try {
                            // This is trivial...
                            Thread.sleep(20);
                        } catch (InterruptedException e) {
                            break;
                        }
                    } else {
                        int size = mic.read(mPcmBuffer, 0, mPcmBuffer.length);
                        if (size > 0) {
                            mVideoEncoder.onGetPcmFrame(mPcmBuffer, size);
                        }
                    }
                }
            }
        });
        aworker.start();
    }

    public void stopAudio() {
        if (aworker != null) {
            aworker.interrupt();
            try {
                aworker.join();
            } catch (InterruptedException e) {
                aworker.interrupt();
            }
            aworker = null;
        }

        if (mic != null) {
            mic.setRecordPositionUpdateListener(null);
            mic.stop();
            mic.release();
            mic = null;
        }

        if (aec != null) {
            aec.setEnabled(false);
            aec.release();
            aec = null;
        }

        if (agc != null) {
            agc.setEnabled(false);
            agc.release();
            agc = null;
        }
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
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        Log.d(TAG, "Have a frame in SurfaceTexture");
        mVideoEncoder.onGetVideoFrame(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "Activity has been paused");
    }



}
