package dev.jacobwasbeast.component;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

public class RadioComponent implements Component<EntityStore> {
    public static final BuilderCodec<RadioComponent> CODEC = BuilderCodec
            .builder(RadioComponent.class, RadioComponent::new)
            .append(new KeyedCodec<>("TrackId", Codec.STRING), (component, v) -> component.trackId = v,
                    component -> component.trackId)
            .add()
            .append(new KeyedCodec<>("IsPlaying", Codec.BOOLEAN), (component, v) -> component.isPlaying = v,
                    component -> component.isPlaying)
            .add()
            .append(new KeyedCodec<>("Title", Codec.STRING), (component, v) -> component.title = v,
                    component -> component.title)
            .add()
            .append(new KeyedCodec<>("Artist", Codec.STRING), (component, v) -> component.artist = v,
                    component -> component.artist)
            .add()
            .append(new KeyedCodec<>("ThumbnailUrl", Codec.STRING), (component, v) -> component.thumbnailUrl = v,
                    component -> component.thumbnailUrl)
            .add()
            .append(new KeyedCodec<>("Duration", Codec.LONG), (component, v) -> component.duration = v,
                    component -> component.duration)
            .add()
            .append(new KeyedCodec<>("CurrentTime", Codec.LONG), (component, v) -> component.currentTime = v,
                    component -> component.currentTime)
            .add()
            .append(new KeyedCodec<>("Volume", Codec.LONG), (component, v) -> component.volume = v.intValue(),
                    component -> (long) component.volume)
            .add()
            .build();

    private String trackId = "";
    private boolean isPlaying = false;
    private String title = "Unknown Title";
    private String artist = "Unknown Artist";
    private String thumbnailUrl = "";
    private long duration = 0;
    private long currentTime = 0;
    private int volume = 100;

    // ComponentType must be registered in the plugin
    public static ComponentType<EntityStore, RadioComponent> COMPONENT_TYPE;

    public RadioComponent() {
    }

    public RadioComponent(String trackId, boolean isPlaying, String title, String artist, String thumbnailUrl,
            long duration, long currentTime, int volume) {
        this.trackId = trackId;
        this.isPlaying = isPlaying;
        this.title = title;
        this.artist = artist;
        this.thumbnailUrl = thumbnailUrl;
        this.duration = duration;
        this.currentTime = currentTime;
        this.volume = volume;
    }

    public String getTrackId() {
        return trackId;
    }

    public void setTrackId(String trackId) {
        this.trackId = trackId;
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public void setPlaying(boolean playing) {
        isPlaying = playing;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getArtist() {
        return artist;
    }

    public void setArtist(String artist) {
        this.artist = artist;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public void setThumbnailUrl(String thumbnailUrl) {
        this.thumbnailUrl = thumbnailUrl;
    }

    public long getDuration() {
        return duration;
    }

    public void setDuration(long duration) {
        this.duration = duration;
    }

    public long getCurrentTime() {
        return currentTime;
    }

    public void setCurrentTime(long currentTime) {
        this.currentTime = currentTime;
    }

    public int getVolume() {
        return volume;
    }

    public void setVolume(int volume) {
        this.volume = volume;
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        return new RadioComponent(trackId, isPlaying, title, artist, thumbnailUrl, duration, currentTime, volume);
    }
}
