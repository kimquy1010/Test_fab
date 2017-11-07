/*
 * Copyright 2014 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.grafika.rtmpconnection1;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.util.Log;
import android.view.Surface;
import android.widget.Toast;

import com.android.grafika.MainActivity;
import com.android.grafika.SrsFlvMuxer;
import com.github.faucamp.simplertmp.RtmpHandler;

import java.io.IOException;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class wraps up the core components used for surface-input video encoding.
 * <p>
 * Once created, frames are fed to the input surface.  Remember to provide the presentation
 * time stamp, and always call drainEncoder() before swapBuffers() to ensure that the
 * producer side doesn't get backed up.
 * <p>
 * This class is not thread-safe, with one exception: it is valid to use the input surface
 * on one thread, and drain the output on a different thread.
 */
public class VideoEncoderSimpleRtmp implements RtmpHandler.RtmpListener {
    private static final String TAG = MainActivity.TAG;
    private static final boolean VERBOSE = true;

    // TODO: these ought to be configurable as well
    private static final String VCODEC = "video/avc";
    public static final String ACODEC = "audio/mp4a-latm";
    private static final int FRAME_RATE = 30;               // 30fps
    private static final int IFRAME_INTERVAL = 2;           // 2 seconds between I-frames

    private Surface mInputSurface;
    private SrsFlvMuxer mFlvMuxer;
    private MediaCodec mVideoEncoder, mAudioEncoder;
    private MediaCodec.BufferInfo mVideoBufferInfo, mAudioBufferInfo;
    private int mVideoFlvTrack, mAudioFlvTrack;
    private boolean mMuxerStarted;
    private Context mContext;
    private String mRtmpUrl;

    private long mStartTimeMillis;
    public static final int ASAMPLERATE = 44100;
    public static final int VGOP = 48;
    public static int aChannelConfig = AudioFormat.CHANNEL_IN_STEREO;
    private long mPresentTimeUs = 0, mTimeStamp;
    private int mWidth, mHeight, mBitRate;
    ByteBuffer[] mEncoderOutputBuffers;

    public static final int ABITRATE = 64 * 1024;  // 64 kbps

    /**
     * Configures encoder and muxer state, and prepares the input Surface.
     */
    public VideoEncoderSimpleRtmp(int width, int height, int bitRate, Context context, String rtmpUrl)
            throws IOException {
        mContext = context;
        mRtmpUrl = rtmpUrl;
        mVideoBufferInfo = new MediaCodec.BufferInfo();
        mAudioBufferInfo = new MediaCodec.BufferInfo();
        mWidth = width;
        mHeight = height;
        mBitRate = bitRate;

        //Start Encoders
        // Set some properties.  Failing to specify some of these can cause the MediaCodec
        // configure() call to throw an unhelpful exception.
        MediaFormat videoFormat = MediaFormat.createVideoFormat(VCODEC, mWidth, mHeight);
        videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        videoFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0);
        videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, mBitRate);
        videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        if (VERBOSE) Log.d(TAG, "format: " + videoFormat);

        //Setup AudioEncoder
        int ach = aChannelConfig == AudioFormat.CHANNEL_IN_STEREO ? 2 : 1;
        MediaFormat audioFormat = MediaFormat.createAudioFormat(ACODEC, ASAMPLERATE, ach);
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, ABITRATE);
        audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0);
        if (VERBOSE) Log.d(TAG, "format: " + audioFormat);

        // Create a MediaCodec encoder, and configure it with our format.  Get a Surface
        // we can use for input and wrap it with a class that handles the EGL work.

        try {
            Log.d(TAG, "Create video and audio encoder");
            mVideoEncoder = MediaCodec.createEncoderByType(VCODEC);
            mAudioEncoder = MediaCodec.createEncoderByType(ACODEC);
        } catch (IOException e) {
            Log.e(TAG, "create mEncoder failed.");
            e.printStackTrace();

        }
        //Configuration encoders type encoders
        mVideoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mAudioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        //Create surface for record screen
        mInputSurface = mVideoEncoder.createInputSurface();
        mVideoEncoder.start();
        mAudioEncoder.start();

        mPresentTimeUs = System.nanoTime() / 1000;

        //After configuration encoders, start muxer for these encoders
        try {
            mFlvMuxer = new SrsFlvMuxer(new RtmpHandler(this));
        } catch (Exception e) {
            Log.e(TAG, "Can not create FlvMuxer.");

        }

        // Declare Audio and Video track
        mAudioFlvTrack = mFlvMuxer.addTrack(audioFormat);
        mVideoFlvTrack = mFlvMuxer.addTrack(videoFormat);
        mMuxerStarted = false;
        Log.d(TAG, "Started encoder");
    }

    /**
     * Returns the encoder's input surface.
     */
    public Surface getInputSurface() {
        return mInputSurface;
    }


    /**
     * Extracts all pending data from the encoder and forwards it to the muxer.
     * <p>
     * If endOfStream is not set, this returns when there is no more data to drain.  If it
     * is set, we send EOS to the encoder, and then iterate until we see EOS on the output.
     * Calling this with endOfStream set should be done once, right before stopping the muxer.
     * <p>
     * We're just using the muxer to get a .mp4 file (instead of a raw H.264 stream).  We're
     * not recording audio.
     */
    public void onGetVideoFrame(boolean endOfStream) {
        final int TIMEOUT_USEC = 10000;
        if (VERBOSE) Log.d(TAG, "Get video frame(" + endOfStream + ")");
        //If end of stream
        if (endOfStream) {
            if (VERBOSE) Log.d(TAG, "sending EOS to encoder");
            mVideoEncoder.signalEndOfInputStream();
        }

        //Get output buffer
        while (true) {
            long pts = System.nanoTime() / 1000 - mPresentTimeUs;
            Log.d(TAG, "Sent pts: "+pts);
            mVideoBufferInfo.presentationTimeUs = pts;
            //Set timestamp to output buffer
            int encoderStatus = mVideoEncoder.dequeueOutputBuffer(mVideoBufferInfo, TIMEOUT_USEC);
            mVideoBufferInfo.presentationTimeUs = pts;
//            if (mPresentTimeUs == 0) {
//                mPresentTimeUs = System.nanoTime() / 1000;
//            } else mTimeStamp= System.nanoTime() / 1000 - mPresentTimeUs;
//            mVideoBufferInfo.presentationTimeUs = mTimeStamp;
            Log.d(TAG, "Encoder status: " + encoderStatus);
//            Log.d(TAG, "Buffer info: " + mVideoBufferInfo);

            //Check encoder status
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no output available yet
                if (!endOfStream) {
                    break;      // out of while
                } else {
                    if (VERBOSE) Log.d(TAG, "no output available, spinning to await EOS");
                }

                //If output buffer change, we need to get again address of buffer
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // not expected for an encoder
                mEncoderOutputBuffers = mVideoEncoder.getOutputBuffers();

                //If format of video change, need to send this format to muxer
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // should happen before receiving buffers, and should only happen once
                if (mMuxerStarted) {
                    throw new RuntimeException("format changed twice");
                }
                MediaFormat newFormat = mVideoEncoder.getOutputFormat();
                Log.d(TAG, "encoder output format changed: " + newFormat);
                ByteBuffer byteBufferSPS = newFormat.getByteBuffer("csd-0");
                ByteBuffer byteBufferPPS = newFormat.getByteBuffer("csd-1");
                Log.d(TAG, "Test SPS: " + newFormat.getByteBuffer("csd-0"));
                Log.d(TAG, "Test PPS: " + newFormat.getByteBuffer("csd-1"));
                mFlvMuxer.start(mRtmpUrl);
                mMuxerStarted = true;
                Log.d(TAG, "Muxer started: " + mMuxerStarted);

                //Other situation we dont known
            } else if (encoderStatus < 0) {
                Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: " +
                        encoderStatus);
                // let's ignore it

                //All ok, if status >= 0, this mean we have frame, need to start send data to server
            } else if (encoderStatus >= 0) {
//                mEncoderOutputBuffers = mVideoEncoder.getOutputBuffers();
                Log.d(TAG, "Test Encoder output buffers: "+ mEncoderOutputBuffers);
                //Get data by encoder status
//                ByteBuffer videoEncodedData = mEncoderOutputBuffers[encoderStatus];
                ByteBuffer videoEncodedData = mVideoEncoder.getOutputBuffer(encoderStatus);
                //If data ==0, dont do any thing
                if (videoEncodedData == null) {
                    throw new RuntimeException("encoderOutputBuffer " + encoderStatus +
                            " was null");
                }

                //When flag of format changes, we will send it to serve can known
                if ((mVideoBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
//                    if (!configFirstTime) return;
                    // The codec config data was pulled out and fed to the muxer when we got
                    // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                    if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");

                    videoEncodedData.position(mVideoBufferInfo.offset);
                    Log.d(TAG, "Buffer offset: " + mVideoBufferInfo.offset + ", Buffer size: " + mVideoBufferInfo.size);
                    videoEncodedData.limit(mVideoBufferInfo.offset + mVideoBufferInfo.size);
                    //Write data to muxer now
                    mFlvMuxer.writeSampleData(mVideoFlvTrack, videoEncodedData, mVideoBufferInfo);
                    mVideoBufferInfo.size = 0;
                }

                //When have data with size = buffer size
                if (mVideoBufferInfo.size != 0) {

                    if (!mMuxerStarted) {
                        throw new RuntimeException("muxer hasn't started");
                    }

                    // adjust the ByteBuffer values to match BufferInfo (not needed?)
                    videoEncodedData.position(mVideoBufferInfo.offset);
                    Log.d(TAG, "Buffer offset: " + mVideoBufferInfo.offset + ", Buffer size: " + mVideoBufferInfo.size);
                    videoEncodedData.limit(mVideoBufferInfo.offset + mVideoBufferInfo.size);
                    //Write data to muxer now
                    mFlvMuxer.writeSampleData(mVideoFlvTrack, videoEncodedData, mVideoBufferInfo);
                }
                if (VERBOSE) {
                    Log.d(TAG, "sent " + mVideoBufferInfo.size + " bytes to muxer, ts=" +
                            mVideoBufferInfo.presentationTimeUs);
                }
                mVideoEncoder.releaseOutputBuffer(encoderStatus, false);
            }

            //Flag end of stream
            if ((mVideoBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                if (!endOfStream) {
                    Log.w(TAG, "reached end of stream unexpectedly");
                } else {
                    if (VERBOSE) Log.d(TAG, "end of stream reached");
                }
                break;      // out of while
            }
        }

    }

    public AudioRecord chooseAudioRecord() {
        Log.d(TAG, "Choose audio");
        AudioRecord mic = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, ASAMPLERATE,
                AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, getPcmBufferSize() * 4);
        if (mic.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.d(TAG, "Cant initialized audio to stereo");
            mic = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, ASAMPLERATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, getPcmBufferSize() * 4);
            if (mic.getState() != AudioRecord.STATE_INITIALIZED) {
                mic = null;
                Log.d(TAG, "Cant initialized audio to mono");
            } else {
                VideoEncoderSimpleRtmp.aChannelConfig = AudioFormat.CHANNEL_IN_MONO;
                Log.d(TAG, "Choose audio mono");
            }
        } else {
            VideoEncoderSimpleRtmp.aChannelConfig = AudioFormat.CHANNEL_IN_STEREO;
            Log.d(TAG, "Choose audio stereo");
        }

        return mic;
    }

    public void onGetPcmFrame(byte[] data, int size) {
        Log.d(TAG, "Start get pcm frame");
        // Check video frame cache number to judge the networking situation.
        // Just cache GOP / FPS seconds data according to latency.
        AtomicInteger videoFrameCacheNumber = mFlvMuxer.getVideoFrameCacheNumber();
        Log.d(TAG, "Check video frame cache number: " + videoFrameCacheNumber);
        if (videoFrameCacheNumber != null && videoFrameCacheNumber.get() < VGOP) {
            //Put data to audio buffer
            ByteBuffer[] inBuffers = mAudioEncoder.getInputBuffers();
            ByteBuffer[] outBuffers = mAudioEncoder.getOutputBuffers();
            int inBufferIndex = mAudioEncoder.dequeueInputBuffer(-1);
            if (inBufferIndex >= 0) {
                Log.d(TAG, "Get audio frame");
                ByteBuffer bb = inBuffers[inBufferIndex];
                bb.clear();
                bb.put(data, 0, size);
                long pts = System.nanoTime() / 1000 - mPresentTimeUs;
                mAudioEncoder.queueInputBuffer(inBufferIndex, 0, size, pts, 0);
            }

            //Get data from audio buffer
            for (; ; ) {
                Log.d(TAG, "Encode audio frame");
                int outBufferIndex = mAudioEncoder.dequeueOutputBuffer(mAudioBufferInfo, 0);
                if (outBufferIndex >= 0) {
                    ByteBuffer audioEncodedData = outBuffers[outBufferIndex];
                    mFlvMuxer.writeSampleData(mAudioFlvTrack, audioEncodedData, mAudioBufferInfo);
                    mAudioEncoder.releaseOutputBuffer(outBufferIndex, false);
                } else {
                    break;
                }
            }
        }
    }


    private int getPcmBufferSize() {
        int pcmBufSize = AudioRecord.getMinBufferSize(ASAMPLERATE, AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT) + 8191;
        return pcmBufSize - (pcmBufSize % 8192);
    }

    // Implementation of SrsRtmpListener.

    private void handleException(Exception e) {
        try {
            Toast.makeText(mContext, e.getMessage(), Toast.LENGTH_SHORT).show();
        } catch (Exception e1) {
            //
        }
    }

    @Override
    public void onRtmpConnecting(String msg) {
        Toast.makeText(mContext, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRtmpConnected(String msg) {
        Toast.makeText(mContext, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRtmpVideoStreaming() {
    }

    @Override
    public void onRtmpAudioStreaming() {
    }

    @Override
    public void onRtmpStopped() {
        Toast.makeText(mContext, "Stopped", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRtmpDisconnected() {
        Toast.makeText(mContext, "Disconnected", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRtmpVideoFpsChanged(double fps) {
        Log.i(TAG, String.format("Output Fps: %f", fps));
    }

    @Override
    public void onRtmpVideoBitrateChanged(double bitrate) {
        int rate = (int) bitrate;
        if (rate / 1000 > 0) {
            Log.i(TAG, String.format("Video bitrate: %f kbps", bitrate / 1000));
        } else {
            Log.i(TAG, String.format("Video bitrate: %d bps", rate));
        }
    }

    @Override
    public void onRtmpAudioBitrateChanged(double bitrate) {
        int rate = (int) bitrate;
        if (rate / 1000 > 0) {
            Log.i(TAG, String.format("Audio bitrate: %f kbps", bitrate / 1000));
        } else {
            Log.i(TAG, String.format("Audio bitrate: %d bps", rate));
        }
    }

    @Override
    public void onRtmpSocketException(SocketException e) {
        handleException(e);
    }

    @Override
    public void onRtmpIOException(IOException e) {
        handleException(e);
    }

    @Override
    public void onRtmpIllegalArgumentException(IllegalArgumentException e) {
        handleException(e);
    }

    @Override
    public void onRtmpIllegalStateException(IllegalStateException e) {
        handleException(e);
    }


    /**
     * Releases encoder resources.
     */
    public void release() {
        if (VERBOSE) Log.d(TAG, "releasing encoder objects");
        if (mVideoEncoder != null) {
            mVideoEncoder.stop();
            mVideoEncoder.release();
            mVideoEncoder = null;
        }
        if (mAudioEncoder != null) {
            // TODO: stop() throws an exception if you haven't fed it any data.  Keep track
            //       of frames submitted, and don't call stop() if we haven't written anything.
            mAudioEncoder.stop();
            mAudioEncoder.release();
            mAudioEncoder = null;
        }

        if (mFlvMuxer != null) {
            mFlvMuxer.stop();
            mFlvMuxer = null;
        }
    }


}
