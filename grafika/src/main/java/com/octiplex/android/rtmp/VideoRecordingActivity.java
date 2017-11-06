package com.octiplex.android.rtmp;

import android.app.Activity;
import android.media.MediaCodec;
import android.os.AsyncTask;
import android.support.annotation.NonNull;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Created by Quynh on 11/1/2017.
 */

public class VideoRecordingActivity extends Activity
{
    private MediaCodec mEncoder;
    private MediaCodec.BufferInfo mBufferInfo;

    private RtmpMuxer muxer; // Already started muxer

    /**
     * Extracts all pending data from the encoder and forwards it to the muxer.
     */
    public void drainEncoder()
    {
        final int TIMEOUT_USEC = 10000;

        ByteBuffer[] encoderOutputBuffers = mEncoder.getOutputBuffers();
        while (true)
        {
            int encoderStatus = mEncoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);

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
                    break;      // out of while
                }
            }
        }
    }
}
