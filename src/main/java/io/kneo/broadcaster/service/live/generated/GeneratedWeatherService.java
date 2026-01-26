package io.kneo.broadcaster.service.live.generated;

import io.kneo.broadcaster.agent.ElevenLabsClient;
import io.kneo.broadcaster.agent.GCPTTSClient;
import io.kneo.broadcaster.agent.ModelslabClient;
import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.model.aiagent.AiAgent;
import io.kneo.broadcaster.model.aiagent.Voice;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.repository.soundfragment.SoundFragmentRepository;
import io.kneo.broadcaster.service.AiAgentService;
import io.kneo.broadcaster.service.PromptService;
import io.kneo.broadcaster.service.live.scripting.DraftFactory;
import io.kneo.broadcaster.service.manipulation.FFmpegProvider;
import io.kneo.broadcaster.service.manipulation.mixing.AudioConcatenator;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class GeneratedWeatherService extends AbstractGeneratedContentService {

    @Inject
    public GeneratedWeatherService(
            PromptService promptService,
            SoundFragmentService soundFragmentService,
            ElevenLabsClient elevenLabsClient,
            ModelslabClient modelslabClient,
            GCPTTSClient gcpttsClient,
            BroadcasterConfig config,
            DraftFactory draftFactory,
            AiAgentService aiAgentService,
            SoundFragmentRepository soundFragmentRepository,
            FFmpegProvider ffmpegProvider,
            AudioConcatenator audioConcatenator
    ) {
        super(promptService, soundFragmentService, elevenLabsClient, modelslabClient,
                gcpttsClient, config, draftFactory, aiAgentService, soundFragmentRepository,
                ffmpegProvider, audioConcatenator);
    }

    public GeneratedWeatherService() {
        super(null, null, null, null, null, null, null, null, null, null, null);
    }

    @Override
    protected String getIntroJingleResource() {
        return "Weather_Intro_Jingle.wav";
    }

    @Override
    protected String getBackgroundMusicResource() {
        return "Weather_Background_Loop.wav";
    }

    @Override
    protected PlaylistItemType getContentType() {
        return PlaylistItemType.WEATHER;
    }

    @Override
    protected Voice getVoice(AiAgent agent) {
        return agent.getTtsSetting().getWeatherReporter();
    }

    @Override
    protected String getSystemPrompt() {
        return "You are a professional radio weather presenter";
    }
}
