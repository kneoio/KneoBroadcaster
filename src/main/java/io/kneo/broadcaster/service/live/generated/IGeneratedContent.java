package io.kneo.broadcaster.service.live.generated;

import io.kneo.broadcaster.model.aiagent.AiAgent;
import io.kneo.broadcaster.model.cnst.LanguageTag;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.model.stream.IStream;
import io.kneo.broadcaster.model.stream.LiveScene;
import io.smallrye.mutiny.Uni;

import java.util.UUID;

public interface IGeneratedContent {
    Uni<SoundFragment> generateFragment(
            UUID promptId,
            AiAgent agent,
            IStream stream,
            UUID brandId,
            LiveScene activeEntry,
            LanguageTag broadcastingLanguage
    );
}
