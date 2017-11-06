package com.android.grafika.rtmpconnection2;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.AsyncTask;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.Surface;
import android.widget.Toast;


import com.android.grafika.MainActivity;
import com.octiplex.android.rtmp.H264VideoFrame;
import com.octiplex.android.rtmp.RtmpConnectionListener;
import com.octiplex.android.rtmp.RtmpMuxer;
import com.octiplex.android.rtmp.Time;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Created by Quynh on 11/1/2017.
 */

public class VideoEncoderRtmp
{
    private RtmpMuxer muxer; // Already started muxer

    private static final String TAG = MainActivity.TAG;
    private static final boolean VERBOSE = true;

    // TODO: these ought to be configurable as well
    private static final String MIME_TYPE = "video/avc";    // H.264 Advanced Video Coding
    private static final int FRAME_RATE = 30;               // 30fps
    private static final int IFRAME_INTERVAL = 5;           // 5 seconds between I-frames

    private Surface mInputSurface;
    private RtmpMuxer mRtmpMuxer;
    private MediaMuxer mMuxer;
    private MediaCodec mEncoder;
    private MediaCodec.BufferInfo mBufferInfo;
    private int mTrackIndex;
    private boolean mMuxerStarted;


    /**
     * Configures encoder and muxer state, and prepares the input Surface.
     */
    public VideoEncoderRtmp(int width, int height, int bitRate, File outputFile)
            throws IOException {
        mBufferInfo = new MediaCodec.BufferInfo();

        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);

        // Set some properties.  Failing to specify some of these can cause the MediaCodec
        // configure() call to throw an unhelpful exception.
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        if (VERBOSE) Log.d(TAG, "format: " + format);

        // Create a MediaCodec encoder, and configure it with our format.  Get a Surface
        // we can use for input and wrap it with a class that handles the EGL work.
        mEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mInputSurface = mEncoder.createInputSurface();
        mEncoder.start();

        // Create a MediaMuxer.  We can't add the video track and start() the muxer here,
        // because our MediaFormat doesn't have the Magic Goodies.  These can only be
        // obtained from the encoder after it has started processing data.
        //
        // We're not actually interested in multiplexing audio.  We just want to convert
        // the raw H.264 elementary stream we get from MediaCodec into a .mp4 file.
        mMuxer = new MediaMuxer(outputFile.toString(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        muxer = new RtmpMuxer("live-api-a.facebook.com", 80, new Time() {
            long mPrevTimeNanos, timeStampNanos;

            @Override
            public long getCurrentTimestamp() {
                return System.nanoTime();
            }
        });

        muxer.start(new RtmpConnectionListener() {
            @Override
            public void onConnected() {
                Log.d(TAG,"Connected to server");
            }

            @Override
            public void onReadyToPublish() {
                Log.d(TAG,"Ready to post");
            }

            @Override
            public void onConnectionError(@NonNull IOException e) {
                Log.e(TAG,"Cant connect to server");
            }
        },"rtmp",null,null);
        muxer.createStream("1355739464551559?ds=1&a=ATiwTJab6sk21CQu");
        mTrackIndex = -1;
        mMuxerStarted = false;
    }

    /**
     * Returns the encoder's input surface.
     */
    public Surface getInputSurface() {
        return mInputSurface;
    }

    /**
     * Releases encoder resources.
     */
    public void release() {
        if (VERBOSE) Log.d(TAG, "releasing encoder objects");
        if (mEncoder != null) {
            mEncoder.stop();
            mEncoder.release();
            mEncoder = null;
        }
        if (mMuxer != null) {
            // TODO: stop() throws an exception if you haven't fed it any data.  Keep track
            //       of frames submitted, and don't call stop() if we haven't written anything.
            mMuxer.stop();
            mMuxer.release();
            mMuxer = null;
        }
        if (muxer!=null){
            try {
                muxer.deleteStream();
            } catch (Exception e){
                e.printStackTrace();
            }
            muxer.stop();
            muxer = null;
        }
    }

    /**
     * Extracts all pending data from the encoder and forwards it to the muxer.
     */
    public void drainEncoder(boolean endOfStream)
    {
        final int TIMEOUT_USEC = 10000;
        if (VERBOSE) Log.d(TAG, "drainEncoder(" + endOfStream + ")");
        if (endOfStream) {
            if (VERBOSE) Log.d(TAG, "sending EOS to encoder");
            mEncoder.signalEndOfInputStream();
        }

        ByteBuffer[] encoderOutputBuffers = mEncoder.getOutputBuffers();
        while (true)
        {
            int encoderStatus = mEncoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no output available yet
                if (!endOfStream) {
                    break;      // out of while
                } else {
                    if (VERBOSE) Log.d(TAG, "no output available, spinning to await EOS");
                }
            } else

            if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED)
            {
                // not expected for an encoder
                encoderOutputBuffers = mEncoder.getOutputBuffers();
            }
            else
            {
                if (mBufferInfo.size != 0)
                {
                    final boolean isHeader = (mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;

                    final boolean isKeyframe = (mBufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 && !isHeader;

                    final long timestamp = mBufferInfo.presentationTimeUs;

                    final ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                    encodedData.position(mBufferInfo.offset);
                    encodedData.limit(mBufferInfo.offset + mBufferInfo.size);

                    // Extract video data
                    final byte[] b = new byte[mBufferInfo.size];
                    encodedData.get(b);

                    // Always call postVideo method from a background thread.
                    new AsyncTask<Void, Void, Void>()
                    {
                        @Override
                        protected Void doInBackground(Void... params)
                        {
                            try
                            {
                                muxer.postVideo(new H264VideoFrame()
                                {
                                    @Override
                                    public boolean isHeader()
                                    {
                                        return isHeader;
                                    }

                                    @Override
                                    public long getTimestamp()
                                    {
                                        return timestamp;
                                    }

                                    @NonNull
                                    @Override
                                    public byte[] getData()
                                    {
                                        return b;
                                    }

                                    @Override
                                    public boolean isKeyframe()
                                    {
                                        return isKeyframe;
                                    }
                                });
                            }
                            catch(IOException e)
                            {
                                // An error occured while sending the video frame to the server
                            }

                            return null;
                        }
                    }.execute();
                }

                mEncoder.releaseOutputBuffer(encoderStatus, false);

                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
                {
                    if (!endOfStream) {
                        Log.w(TAG, "reached end of stream unexpectedly");
                    } else {
                        if (VERBOSE) Log.d(TAG, "end of stream reached");
                    }
                    break;      // out of while
                }
            }
        }
    }
}
