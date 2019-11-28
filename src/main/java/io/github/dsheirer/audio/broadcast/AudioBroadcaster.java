/*
 *
 *  * ******************************************************************************
 *  * Copyright (C) 2014-2020 Dennis Sheirer
 *  *
 *  * This program is free software: you can redistribute it and/or modify
 *  * it under the terms of the GNU General Public License as published by
 *  * the Free Software Foundation, either version 3 of the License, or
 *  * (at your option) any later version.
 *  *
 *  * This program is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  * GNU General Public License for more details.
 *  *
 *  * You should have received a copy of the GNU General Public License
 *  * along with this program.  If not, see <http://www.gnu.org/licenses/>
 *  * *****************************************************************************
 *
 *
 */
package io.github.dsheirer.audio.broadcast;

import io.github.dsheirer.audio.convert.ISilenceGenerator;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.util.ThreadPool;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Queue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class AudioBroadcaster implements Listener<AudioRecording>
{
    private final static Logger mLog = LoggerFactory.getLogger(AudioBroadcaster.class);

    public static final int PROCESSOR_RUN_INTERVAL_MS = 1000;

    private ScheduledFuture mRecordingQueueProcessorFuture;

    private RecordingQueueProcessor mRecordingQueueProcessor = new RecordingQueueProcessor();
    private Queue<AudioRecording> mAudioRecordingQueue = new LinkedTransferQueue<>();

    private ISilenceGenerator mSilenceGenerator;

    private Listener<BroadcastEvent> mBroadcastEventListener;
    private ObjectProperty<BroadcastState> mBroadcastState = new SimpleObjectProperty<>(BroadcastState.READY);

    private int mStreamedAudioCount = 0;
    private int mAgedOffAudioCount = 0;
    private BroadcastConfiguration mBroadcastConfiguration;
    private long mDelay;
    private long mMaximumRecordingAge;
    private AtomicBoolean mStreaming = new AtomicBoolean();

    /**
     * AudioBroadcaster for streaming audio recordings to a remote streaming audio server.  Audio recordings are
     * generated by an internal StreamManager that converts an inbound stream of AudioPackets into a recording of the
     * desired audio format (e.g. MP3) and nominates the recording to an internal recording queue for streaming.  The
     * broadcaster supports receiving audio packets from multiple audio sources.  Each audio packet's internal audio
     * metadata source string is used to reassemble each packet stream.  Recordings are capped at 30 seconds length.
     * If a source audio packet stream exceeds 30 seconds in length, it will be chunked into 30 second recordings.
     *
     * This broadcaster supports a time delay setting for delaying broadcast of audio recordings.  The delay setting is
     * defined in the broadcast configuration.  When this delay is greater than zero, the recording will remain in the
     * audio broadcaster queue until the recording start time + delay elapses.  Audio recordings are processed in a FIFO
     * manner.
     *
     * Use the start() and stop() methods to connect to/disconnect from the remote server.  Audio recordings will be
     * streamed to the remote server when available.  One second silence frames will be broadcast to the server when
     * there are no recordings available, in order to maintain a connection with the remote server.  Any audio packet
     * streams received while the broadcaster is stopped will be ignored.
     *
     * The last audio packet's metadata is automatically attached to the closed audio recording when it is enqueued for
     * broadcast.  That metadata will be updated on the remote server once the audio recording is opened for streaming.
     */
    public AudioBroadcaster(BroadcastConfiguration broadcastConfiguration)
    {
        mBroadcastConfiguration = broadcastConfiguration;
        mDelay = mBroadcastConfiguration.getDelay();
        mMaximumRecordingAge = mBroadcastConfiguration.getMaximumRecordingAge();
        mSilenceGenerator = BroadcastFactory.getSilenceGenerator(broadcastConfiguration.getBroadcastFormat());
    }

    /**
     * Observable broadcast state property
     */
    public ObjectProperty<BroadcastState> broadcastStateProperty()
    {
        return mBroadcastState;
    }

    public void dispose()
    {
    }

    /**
     * Broadcast binary audio data frames or sequences.
     */
    protected abstract void broadcastAudio(byte[] audio);

    /**
     * Protocol-specific metadata updater
     */
    protected abstract IBroadcastMetadataUpdater getMetadataUpdater();

    /**
     * Broadcasts the next song's audio metadata prior to streaming the next song.
     *
     * @param identifierCollection for the next recording that will be streamed
     */
    protected void broadcastMetadata(IdentifierCollection identifierCollection)
    {
        IBroadcastMetadataUpdater metadataUpdater = getMetadataUpdater();

        if(metadataUpdater != null)
        {
            metadataUpdater.update(identifierCollection);
        }
    }

    /**
     * Disconnects the broadcaster from the remote server for a reset or final stop.
     */
    protected abstract void disconnect();

    /**
     * Connects to the remote server specified by the broadcast configuration and starts audio streaming.
     */
    public void start()
    {
        if(mStreaming.compareAndSet(false, true))
        {
            if(mRecordingQueueProcessorFuture == null)
            {
                mRecordingQueueProcessorFuture = ThreadPool.SCHEDULED.scheduleAtFixedRate(mRecordingQueueProcessor,
                    0, PROCESSOR_RUN_INTERVAL_MS, TimeUnit.MILLISECONDS);
            }
        }
    }

    /**
     * Disconnects from the remote server.
     */
    public void stop()
    {
        if(mStreaming.compareAndSet(true, false))
        {
            if(mRecordingQueueProcessorFuture != null)
            {
                mRecordingQueueProcessorFuture.cancel(true);
                mRecordingQueueProcessorFuture = null;
            }

            disconnect();
        }
    }

    /**
     * Stream name for the broadcast configuration for this broadcaster
     *
     * @return stream name or null
     */
    public String getStreamName()
    {
        BroadcastConfiguration config = getBroadcastConfiguration();

        if(config != null)
        {
            return config.getName();
        }

        return null;
    }

    /**
     * Size of recording queue for recordings awaiting streaming
     */
    public int getQueueSize()
    {
        return mAudioRecordingQueue.size();
    }

    /**
     * Number of audio recordings streamed to remote server
     */
    public int getStreamedAudioCount()
    {
        return mStreamedAudioCount;
    }

    /**
     * Number of audio recordings that were removed for exceeding age limit
     */
    public int getAgedOffAudioCount()
    {
        return mAgedOffAudioCount;
    }

    /**
     * Primary insert method for the stream manager to nominate completed audio recordings for broadcast.
     *
     * @param recording to queue for broadcasting
     */
    public void receive(AudioRecording recording)
    {
        if(connected())
        {
            mAudioRecordingQueue.offer(recording);
            broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_QUEUE_CHANGE));
        }
        else
        {
            recording.removePendingReplay();
        }
    }

    /**
     * Broadcast configuration used by this broadcaster
     */
    public BroadcastConfiguration getBroadcastConfiguration()
    {
        return mBroadcastConfiguration;
    }

    /**
     * Registers the listener to receive broadcast events/state changes
     */
    public void setListener(Listener<BroadcastEvent> listener)
    {
        mBroadcastEventListener = listener;
    }

    /**
     * Removes the listener from receiving broadcast events/state changes
     */
    public void removeListener()
    {
        mBroadcastEventListener = null;
    }

    /**
     * Broadcasts the event to any registered listener
     */
    public void broadcast(BroadcastEvent event)
    {
        if(mBroadcastEventListener != null)
        {
            mBroadcastEventListener.receive(event);
        }
    }

    /**
     * Sets the state of the broadcastAudio connection
     */
    protected void setBroadcastState(BroadcastState state)
    {
        if(mBroadcastState.get() != state)
        {
            if(state == BroadcastState.CONNECTED || state == BroadcastState.DISCONNECTED)
            {
                mLog.info("[" + getStreamName() + "] status: " + state);
            }

            mBroadcastState.setValue(state);

            broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_STATE_CHANGE));

            if(mBroadcastState.get().isErrorState())
            {
                stop();
            }

            if(!connected())
            {
                //Remove all pending audio recordings
                while(!mAudioRecordingQueue.isEmpty())
                {
                    try
                    {
                        AudioRecording recording = mAudioRecordingQueue.remove();
                        recording.removePendingReplay();
                    }
                    catch(Exception e)
                    {
                        //Ignore
                    }
                }
            }
        }
    }

    /**
     * Current state of the broadcastAudio connection
     */
    public BroadcastState getBroadcastState()
    {
        return mBroadcastState.get();
    }

    /**
     * Indicates if the broadcaster is currently connected to the remote server
     */
    protected boolean connected()
    {
        return getBroadcastState() == BroadcastState.CONNECTED;
    }

    /**
     * Indicates if this broadcaster can connect and is not currently in an error state or a connected state.
     */
    public boolean canConnect()
    {
        BroadcastState state = getBroadcastState();

        return state != BroadcastState.CONNECTED && !state.isErrorState();
    }

    /**
     * Indicates if the current broadcast state is an error state, meaning that it cannot recover or connect using the
     * current configuration.
     */
    protected boolean isErrorState()
    {
        return getBroadcastState().isErrorState();
    }


    /**
     * Audio recording queue processor.  Fetches recordings from the queue and chunks the recording byte content
     * to subclass implementations for broadcast in the appropriate manner.
     */
    public class RecordingQueueProcessor implements Runnable
    {
        private AtomicBoolean mProcessing = new AtomicBoolean();
        private ByteArrayInputStream mInputStream;
        private long mFinalSilencePadding = 0;
        private int mBytesStreamedActual = 0;
        private int mBytesStreamedRequired = 0;

        @Override
        public void run()
        {
            if(mProcessing.compareAndSet(false, true))
            {
                try
                {
                    if(mInputStream == null || mInputStream.available() <= 0)
                    {
                        if(mFinalSilencePadding > 0)
                        {
                            broadcastAudio(mSilenceGenerator.generate(mFinalSilencePadding));
                            mFinalSilencePadding = 0;
                        }

                        nextRecording();
                    }

                    if(mInputStream != null)
                    {
                        //We need to stream at 13.888 fps (144 byte frame) to achieve 2000 Bps or 16 kbps
                        mBytesStreamedRequired += 2000;  //2000 bytes per second for 16 kbps data rate
                        int bytesToStream = mBytesStreamedRequired - mBytesStreamedActual;

                        //Trim length to whole-frame intervals (144 byte frame)
                        bytesToStream -= (bytesToStream % 144);

                        int length = Math.min(bytesToStream, mInputStream.available());

                        byte[] audio = new byte[length];

                        try
                        {
                            mBytesStreamedActual += mInputStream.read(audio);

                            broadcastAudio(audio);
                        }
                        catch(IOException ioe)
                        {
                            mLog.error("Error reading from in-memory audio recording input stream", ioe);
                        }
                    }
                    else
                    {
                        broadcastAudio(mSilenceGenerator.generate(PROCESSOR_RUN_INTERVAL_MS));
                    }
                }
                catch(Throwable t)
                {
                    mLog.error("Error while processing audio streaming queue", t);
                }

                mProcessing.set(false);
            }
        }

        /**
         * Loads the next recording for broadcast
         */
        private void nextRecording()
        {
            mBytesStreamedActual = 0;
            mBytesStreamedRequired = 0;

            boolean metadataUpdateRequired = false;

            if(mInputStream != null)
            {
                mStreamedAudioCount++;
                broadcast(new BroadcastEvent(AudioBroadcaster.this,
                    BroadcastEvent.Event.BROADCASTER_STREAMED_COUNT_CHANGE));
                metadataUpdateRequired = true;
            }

            mInputStream = null;

            //Peek at the next recording but don't remove it from the queue yet, so we can inspect the start time for
            //age limits and/or delay elapsed
            AudioRecording nextRecording = mAudioRecordingQueue.peek();

            //Purge any recordings that have exceeded maximum recording age limit
            while(nextRecording != null &&
                (nextRecording.getStartTime() + mDelay + mMaximumRecordingAge) < java.lang.System.currentTimeMillis())
            {
                nextRecording = mAudioRecordingQueue.remove();
                nextRecording.removePendingReplay();
                mAgedOffAudioCount++;
                broadcast(new BroadcastEvent(AudioBroadcaster.this,
                    BroadcastEvent.Event.BROADCASTER_AGED_OFF_COUNT_CHANGE));
                nextRecording = mAudioRecordingQueue.peek();
            }

            if(nextRecording != null && nextRecording.getStartTime() + mDelay <= System.currentTimeMillis())
            {
                nextRecording = mAudioRecordingQueue.remove();

                try
                {
                    if(Files.exists(nextRecording.getPath()))
                    {
                        byte[] audio = Files.readAllBytes(nextRecording.getPath());

                        if(audio != null && audio.length > 0)
                        {
                            mInputStream = new ByteArrayInputStream(audio);

                            mFinalSilencePadding = PROCESSOR_RUN_INTERVAL_MS -
                                (nextRecording.getRecordingLength() % PROCESSOR_RUN_INTERVAL_MS);

                            while(mFinalSilencePadding >= PROCESSOR_RUN_INTERVAL_MS)
                            {
                                mFinalSilencePadding -= PROCESSOR_RUN_INTERVAL_MS;
                            }

                            if(connected())
                            {
                                broadcastMetadata(nextRecording.getIdentifierCollection());
                            }

                            metadataUpdateRequired = false;
                        }
                    }
                }
                catch(IOException ioe)
                {
                    mLog.error("Stream [" + getBroadcastConfiguration().getName() + "] error reading temporary audio " +
                        "stream recording [" + nextRecording.getPath().toString() + "] - skipping recording - ", ioe);

                    mInputStream = null;
                    metadataUpdateRequired = false;
                }

                nextRecording.removePendingReplay();

                broadcast(new BroadcastEvent(AudioBroadcaster.this, BroadcastEvent.Event.BROADCASTER_QUEUE_CHANGE));
            }

            //If we closed out a recording and don't have a new/next recording, send an empty metadata update
            if(metadataUpdateRequired && connected())
            {
                broadcastMetadata(null);
            }
        }
    }
}
