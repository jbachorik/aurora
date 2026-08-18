package mediacenter.playback;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Stand-in for VLC so the playback lifecycle can be tested without a player. */
final class FakePlayerLauncher implements PlayerLauncher {

    private final List<Path> played = new ArrayList<>();
    private final List<List<Path>> queues = new ArrayList<>();
    private final Function<Path, PlaybackResult> behaviour;

    private FakePlayerLauncher(Function<Path, PlaybackResult> behaviour) {
        this.behaviour = behaviour;
    }

    static FakePlayerLauncher succeeding() {
        return new FakePlayerLauncher(file -> new PlaybackResult.Completed(0, Duration.ofMinutes(90)));
    }

    static FakePlayerLauncher failingWith(String message) {
        return new FakePlayerLauncher(file -> PlaybackResult.Failed.of(message));
    }

    static FakePlayerLauncher behavingAs(Function<Path, PlaybackResult> behaviour) {
        return new FakePlayerLauncher(behaviour);
    }

    List<Path> played() {
        return List.copyOf(played);
    }

    /** The queue handed over with each play, in call order. */
    List<List<Path>> queues() {
        return List.copyOf(queues);
    }

    @Override
    public PlaybackResult play(Path mediaFile, List<Path> playOnwards) {
        played.add(mediaFile);
        queues.add(List.copyOf(playOnwards));
        return behaviour.apply(mediaFile);
    }
}
