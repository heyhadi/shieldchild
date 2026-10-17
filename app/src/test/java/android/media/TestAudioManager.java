package android.media;

public class TestAudioManager extends AudioManager {
    private final int mode;

    public TestAudioManager(int mode) {
        super();
        this.mode = mode;
    }

    @Override
    public int getMode() {
        return this.mode;
    }
}
