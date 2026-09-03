package app.kernelpanic.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "sessions")
public class SessionEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public long timestampEpochMs;
    public long durationMs;
    public int detectedPopEvents;
    public Long firstPopMs;
    public double peakPopRate;
    public Double finalIntervalSeconds;
    public boolean donenessDetected;
    public String completionReason;

    public SessionEntity(long timestampEpochMs, long durationMs, int detectedPopEvents, Long firstPopMs,
                         double peakPopRate, Double finalIntervalSeconds, boolean donenessDetected,
                         String completionReason) {
        this.timestampEpochMs = timestampEpochMs;
        this.durationMs = durationMs;
        this.detectedPopEvents = detectedPopEvents;
        this.firstPopMs = firstPopMs;
        this.peakPopRate = peakPopRate;
        this.finalIntervalSeconds = finalIntervalSeconds;
        this.donenessDetected = donenessDetected;
        this.completionReason = completionReason;
    }
}
