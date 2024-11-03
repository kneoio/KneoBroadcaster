package io.kneo.broadcaster.queue;

import io.kneo.broadcaster.model.SoundFragment;
import org.jboss.logging.Logger;

import java.io.File;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.kneo.broadcaster.queue.cnst.SoundFolder.QUEUE_FILES;

public class Playlist {
    private static final Logger LOGGER = Logger.getLogger(Playlist.class);
    private static final List<SoundFragment> filesList = new CopyOnWriteArrayList<>();

    public void update() {
        LOGGER.info("Scanning directory for new files...");
        File queueDir = Paths.get(QUEUE_FILES).toFile();

        if (queueDir.exists() && queueDir.isDirectory()) {

            String[] files = queueDir.list();
            if (files != null) {
               for (String path: files) {
                   addFragment(path);
               }
            }
        } else {
            LOGGER.warn("Directory " + QUEUE_FILES + " not found.");
        }
    }

    public void setPlayList(SoundFragment[] files) {
        filesList.clear();
        filesList.addAll(Arrays.asList(files));
        LOGGER.info("Files found: " + filesList);
    }

    public List<SoundFragment> getPlayList() {
        return filesList;
    }

    private void addFragment(String fileName) {
        SoundFragment fragment = new SoundFragment();
        fragment.setName(fileName);
        fragment.setLocalPath(QUEUE_FILES + File.separator + fileName);
        filesList.add(fragment);
    }
}
