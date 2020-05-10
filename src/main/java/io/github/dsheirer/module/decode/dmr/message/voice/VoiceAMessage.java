package io.github.dsheirer.module.decode.dmr.message.voice;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.module.decode.dmr.DMRSyncPattern;

public class VoiceAMessage extends VoiceMessage
{

    /**
     * DMR message frame.  This message is comprised of a 24-bit prefix and a 264-bit message frame.  Outbound base
     * station frames transmit a Common Announcement Channel (CACH) in the 24-bit prefix, whereas Mobile inbound frames
     * do not use the 24-bit prefix.
     *
     * @param syncPattern
     * @param message containing 288-bit DMR message with preliminary bit corrections indicated.
     */
    public VoiceAMessage(DMRSyncPattern syncPattern, CorrectedBinaryMessage message, long timestamp, int timeslot)
    {
        super(syncPattern, message, timestamp, timeslot);
    }

    @Override
    public String toString()
    {
        return null;
    }

    @Override
    public boolean isValid()
    {
        return false;
    }

}