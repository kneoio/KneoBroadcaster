package io.kneo.broadcaster.service.stream;

import io.kneo.broadcaster.model.live.LiveSoundFragment;
import io.kneo.broadcaster.service.manipulation.FFmpegProvider;
import io.kneo.broadcaster.service.playlist.PlaylistManager;
import io.vertx.core.buffer.Buffer;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Path;

@ApplicationScoped
public class Mp3Streamer {
    private static final Logger LOGGER = LoggerFactory.getLogger(Mp3Streamer.class);

    @Inject
    FFmpegProvider ffmpegProvider;

    public Multi<Buffer> stream(PlaylistManager playlistManager) {
        return Multi.createFrom().emitter(emitter -> {
            Thread t = new Thread(() -> {
                Process process = null;
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        LiveSoundFragment fragment = playlistManager.getNextFragment();
                        if (fragment == null) {
                            try { Thread.sleep(300); } catch (InterruptedException e) { break; }
                            continue;
                        }
                        Path source = fragment.getSourceFilePath();
                        if (source == null) {
                            continue;
                        }
                        String ffmpegPath = ffmpegProvider.getFFmpeg().getPath();
                        ProcessBuilder pb = new ProcessBuilder(
                                ffmpegPath, "-re", "-i", source.toString(),
                                "-f", "mp3", "-b:a", "128k", "-"
                        );
                        try {
                            process = pb.start();
                            try (InputStream in = process.getInputStream()) {
                                byte[] buffer = new byte[4096];
                                int len;
                                while ((len = in.read(buffer)) != -1) {
                                    emitter.emit(Buffer.buffer(buffer).slice(0, len));
                                }
                            }
                        } catch (Exception e) {
                            LOGGER.error("MP3 stream error: {}", e.getMessage());
                            emitter.fail(e);
                            break;
                        } finally {
                            if (process != null) {
                                process.destroyForcibly();
                            }
                        }
                    }
                } catch (Exception e) {
                    LOGGER.error("MP3 stream error: {}", e.getMessage());
                    emitter.fail(e);
                } finally {
                    emitter.complete();
                }
            });
            t.start();
            emitter.onTermination(t::interrupt);
        });
    }
}
