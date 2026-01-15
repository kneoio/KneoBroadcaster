package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.dto.ListenerDTO;
import io.kneo.broadcaster.dto.cnst.AiAgentStatus;
import io.kneo.broadcaster.dto.radio.SubmissionDTO;
import io.kneo.broadcaster.dto.radiostation.RadioStationStatusDTO;
import io.kneo.broadcaster.model.FileMetadata;
import io.kneo.broadcaster.model.aiagent.AiAgent;
import io.kneo.broadcaster.model.aiagent.LanguagePreference;
import io.kneo.broadcaster.model.brand.AiOverriding;
import io.kneo.broadcaster.model.brand.Brand;
import io.kneo.broadcaster.model.cnst.LanguageTag;
import io.kneo.broadcaster.model.cnst.ListenerType;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.cnst.RatingAction;
import io.kneo.broadcaster.model.cnst.SourceType;
import io.kneo.broadcaster.model.cnst.StreamStatus;
import io.kneo.broadcaster.model.cnst.SubmissionPolicy;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.model.stream.IStream;
import io.kneo.broadcaster.model.stream.OneTimeStream;
import io.kneo.broadcaster.repository.ContributionRepository;
import io.kneo.broadcaster.repository.OneTimeStreamRepository;
import io.kneo.broadcaster.repository.soundfragment.SoundFragmentRepository;
import io.kneo.broadcaster.service.exceptions.FileUploadException;
import io.kneo.broadcaster.service.exceptions.RadioStationException;
import io.kneo.broadcaster.service.maintenance.LocalFileCleanupService;
import io.kneo.broadcaster.service.soundfragment.SoundFragmentService;
import io.kneo.broadcaster.service.stream.IStreamManager;
import io.kneo.broadcaster.service.stream.RadioStationPool;
import io.kneo.broadcaster.service.util.AnimationService;
import io.kneo.broadcaster.service.util.GeolocationService;
import io.kneo.broadcaster.util.FileSecurityUtils;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.AnonymousUser;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.SuperUser;
import io.kneo.core.repository.exception.ext.UserAlreadyExistsException;
import io.kneo.core.service.UserService;
import io.kneo.core.util.WebHelper;
import io.kneo.officeframe.cnst.CountryCode;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

@ApplicationScoped
public class RadioService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RadioService.class);
    private final Random random = new Random();

    @Inject RadioStationPool radioStationPool;
    @Inject AiAgentService aiAgentService;
    @Inject BrandService brandService;
    @Inject AnimationService animationService;
    @Inject SoundFragmentRepository soundFragmentRepository;
    @Inject SoundFragmentService soundFragmentService;
    @Inject ContributionRepository contributionRepository;
    @Inject LocalFileCleanupService localFileCleanupService;
    @Inject BroadcasterConfig config;
    @Inject ListenerService listenerService;
    @Inject UserService userService;
    @Inject OneTimeStreamRepository oneTimeStreamRepository;

    public Uni<IStream> initializeStation(String brand) {
        return radioStationPool.initializeRadio(brand)
                .onFailure().invoke(f ->
                        radioStationPool.get(brand)
                                .subscribe().with(s -> {
                                    if (s != null) s.setStatus(StreamStatus.SYSTEM_ERROR);
                                })
                );
    }

    public Uni<IStream> stopStation(String brand) {
        return radioStationPool.stopAndRemove(brand);
    }

    public Uni<IStreamManager> getStreamManager(String brand) {
        return radioStationPool.get(brand)
                .onItem().ifNull().failWith(() ->
                        new RadioStationException(RadioStationException.ErrorType.STATION_NOT_ACTIVE))
                .onItem().transform(IStream::getStreamManager)
                .onItem().ifNull().failWith(() ->
                        new RadioStationException(RadioStationException.ErrorType.PLAYLIST_NOT_AVAILABLE));
    }

    public Uni<RadioStationStatusDTO> getStatus(String brand) {
        return radioStationPool.get(brand)
                .onItem().ifNull().failWith(() ->
                        new RadioStationException(RadioStationException.ErrorType.STATION_NOT_ACTIVE))
                .chain(s -> toStatusDTO(s, true, null));
    }

    public Uni<List<RadioStationStatusDTO>> getStations() {
        return Uni.combine().all().unis(getOnlineStations(), oneTimeStreamRepository.getAll(1000, 0))
                .asTuple()
                .chain(tuple -> {
                    List<IStream> online = tuple.getItem1();
                    List<OneTimeStream> allOneTimeStreams = tuple.getItem2();

                    List<Uni<RadioStationStatusDTO>> unis = new ArrayList<>();

                    online.forEach(s -> unis.add(toStatusDTO(s, false, null)));

                    List<String> onlineSlugs = online.stream()
                            .map(IStream::getSlugName)
                            .toList();

                    allOneTimeStreams.stream()
                            .filter(ots -> ots.getStatus() == StreamStatus.PENDING)
                            .filter(ots -> !onlineSlugs.contains(ots.getSlugName()))
                            .forEach(ots -> unis.add(oneTimeStreamToStatusDTO(ots)));

                    return unis.isEmpty()
                            ? Uni.createFrom().item(List.of())
                            : Uni.join().all(unis).andFailFast();
                });
    }

    public Uni<List<RadioStationStatusDTO>> getAllStations(Boolean onlineOnly) {
        return Uni.combine().all().unis(getOnlineStations(), brandService.getAll(1000, 0))
                .asTuple().chain(t -> {
                    List<IStream> online = t.getItem1();
                    List<Brand> all = t.getItem2();

                    List<Uni<RadioStationStatusDTO>> unis = all.stream()
                            .filter(b -> onlineOnly == null || !onlineOnly ||
                                    online.stream().anyMatch(o -> o.getSlugName().equals(b.getSlugName())))
                            .map(b -> {
                                IStream os = online.stream()
                                        .filter(o -> o.getSlugName().equals(b.getSlugName()))
                                        .findFirst().orElse(null);
                                return os != null
                                        ? toStatusDTO(os, false, b)
                                        : brandToStatusDTO(b, false);
                            })
                            .toList();

                    return unis.isEmpty()
                            ? Uni.createFrom().item(List.of())
                            : Uni.join().all(unis).andFailFast();
                });
    }

    public Uni<RadioStationStatusDTO> getStation(String slugName) {
        return brandService.getBySlugName(slugName)
                .chain(b -> {
                    if (b == null) {
                        return Uni.createFrom().nullItem();
                    }

                    return radioStationPool.get(b.getSlugName())
                            .chain(online -> {
                                return (online != null
                                        ? toStatusDTO(online, false, b)
                                        : brandToStatusDTO(b, false))
                                        .onItem().invoke(dto -> {
                                            dto.setDescription(b.getDescription());
                                            dto.setOneTimeStreamPolicy(b.getOneTimeStreamPolicy());
                                            dto.setSubmissionPolicy(b.getSubmissionPolicy());
                                            dto.setMessagingPolicy(b.getMessagingPolicy());
                                            dto.setBitRate(b.getBitRate());
                                            dto.setPopularityRate(b.getPopularityRate());
                                        });
                            });
                })
                .chain(dto -> {
                    if (dto != null) {
                        return Uni.createFrom().item(dto);
                    }
                    return radioStationPool.get(slugName)
                            .chain(online -> {
                                if (online != null) {
                                    return toStatusDTO(online, false, null);
                                }
                                return Uni.createFrom().nullItem();
                            });
                });
    }

    public Uni<SubmissionDTO> submit(String brand, SubmissionDTO dto, String ipHeader, String userAgent) {
        return brandService.getBySlugName(brand)
                .chain(b -> registerContributor(dto.getEmail(), brand)
                        .chain(u -> {
                            String[] ip = GeolocationService.parseIPHeader(ipHeader);
                            dto.setIpAddress(ip[0]);
                            dto.setCountry(CountryCode.valueOf(ip[1]));
                            dto.setUserAgent(userAgent);
                            return processSubmission(b, dto, u);
                        }));
    }

    private Uni<IUser> registerContributor(String email, String stationSlug) {
        ListenerDTO dto = new ListenerDTO();
        dto.setEmail(email);
        dto.getLocalizedName().put(LanguageCode.en, email);

        return listenerService
                .upsertWithStationSlug(
                        null,
                        dto,
                        stationSlug,
                        ListenerType.CONTRIBUTOR,
                        SuperUser.build()
                )
                .onFailure(UserAlreadyExistsException.class)
                .recoverWithUni(e -> {
                    String slugName = WebHelper.generateSlug(email);
                    return userService.findByLogin(slugName)
                            .onItem().transformToUni(existingUser -> {
                                if (existingUser == null || existingUser.getId() == 0) {
                                    return Uni.createFrom().failure(e);
                                }
                                ListenerDTO existingDto = new ListenerDTO();
                                existingDto.setUserId(existingUser.getId());
                                existingDto.setSlugName(slugName);
                                return Uni.createFrom().item(existingDto);
                            });
                })
                .onItem().transformToUni(listenerDto ->
                        userService.findById(listenerDto.getUserId())
                                .onItem().transform(opt ->
                                        opt.orElse(AnonymousUser.build())
                                )
                );
    }


    private Uni<SubmissionDTO> processSubmission(Brand brand, SubmissionDTO dto, IUser user) {
        SoundFragment entity = buildEntity(dto);
        entity.setSource(SourceType.CONTRIBUTION);

        if (dto.getNewlyUploaded() == null || dto.getNewlyUploaded().isEmpty()) {
            return Uni.createFrom().failure(new FileUploadException("At least one file must be uploaded"));
        }

        Path tempBase = Paths.get(config.getPathUploads(), "radio-controller", "anonymous", "temp");
        
        List<FileMetadata> files = dto.getNewlyUploaded().stream()
                .map(name -> {
                    FileMetadata m = new FileMetadata();
                    String sanitizedName = FileSecurityUtils.sanitizeFilename(name);
                    m.setFileOriginalName(sanitizedName);
                    Path filePath = FileSecurityUtils.secureResolve(tempBase, sanitizedName);
                    m.setFilePath(filePath);
                    return m;
                }).toList();

        entity.setFileMetadataList(files);

        return soundFragmentRepository.insert(entity, List.of(brand.getId()), user)
                .chain(doc -> moveFilesForNewEntity(doc, files, user).replaceWith(doc))
                .chain(this::mapToDTO)
                .onFailure().invoke(() ->
                        localFileCleanupService.cleanupTempFilesForUser("anonymous").subscribe());
    }

    private SoundFragment buildEntity(SubmissionDTO dto) {
        SoundFragment d = new SoundFragment();
        d.setType(PlaylistItemType.SONG);
        d.setStatus(50);
        d.setTitle(dto.getTitle());
        d.setArtist(dto.getArtist());
        d.setGenres(dto.getGenres());
        d.setLabels(dto.getLabels());
        d.setAlbum(dto.getAlbum());
        d.setDescription(dto.getDescription());
        d.setSlugName(WebHelper.generateSlug(dto.getTitle(), dto.getArtist()));
        return d;
    }

    private Uni<SoundFragment> moveFilesForNewEntity(
            SoundFragment doc, List<FileMetadata> files, IUser user) {
        try {
            Path anonymousTempBase = Paths.get(config.getPathUploads(), "radio-controller", "anonymous");
            Path userBase = Paths.get(config.getPathUploads(), "radio-controller", user.getUserName());
            Path entityDir = userBase.resolve(doc.getId().toString());
            Files.createDirectories(entityDir);

            for (FileMetadata m : files) {
                Path src = FileSecurityUtils.secureResolve(anonymousTempBase.resolve("temp"), m.getFileOriginalName());
                Path dst = FileSecurityUtils.secureResolve(entityDir, m.getFileOriginalName());
                Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                m.setFilePath(dst);
            }
            return Uni.createFrom().item(doc);
        } catch (Exception e) {
            return Uni.createFrom().failure(e);
        }
    }

    private Uni<SubmissionDTO> mapToDTO(SoundFragment doc) {
        return Uni.createFrom().item(
                SubmissionDTO.builder()
                        .title(doc.getTitle())
                        .artist(doc.getArtist())
                        .genres(doc.getGenres())
                        .album(doc.getAlbum())
                        .build());
    }

    public Uni<RadioStationStatusDTO> toStatusDTO(IStream stream, boolean anim, Brand brand) {
        if (stream == null) return Uni.createFrom().nullItem();
        return buildStatusDTO(
                stream.getLocalizedName().getOrDefault(
                        stream.getCountry().getPreferredLanguage(), stream.getSlugName()),
                stream.getSlugName(),
                stream.getManagedBy().toString(),
                stream.getAiAgentId(),
                stream.getAiOverriding(),
                stream.getAiAgentStatus(),
                stream.getStatus(),
                stream.getCountry().name(),
                stream.getColor(),
                stream.getDescription(),
                anim,
                brand != null ? brand.getOneTimeStreamPolicy() : null,
                brand != null ? brand.getSubmissionPolicy() : null,
                brand != null ? brand.getMessagingPolicy() : null,
                brand != null ? brand.getBitRate() : 0,
                brand != null ? brand.getPopularityRate() : 0.0);
    }

    public Uni<RadioStationStatusDTO> brandToStatusDTO(Brand brand, boolean anim) {
        if (brand == null) return Uni.createFrom().nullItem();
        return buildStatusDTO(
                brand.getLocalizedName().getOrDefault(
                        brand.getCountry().getPreferredLanguage(), brand.getSlugName()),
                brand.getSlugName(),
                brand.getManagedBy().toString(),
                brand.getAiAgentId(),
                brand.getAiOverriding(),
                brand.getAiAgentStatus(),
                brand.getStatus(),
                brand.getCountry().name(),
                brand.getColor(),
                brand.getDescription(),
                anim,
                brand.getOneTimeStreamPolicy(),
                brand.getSubmissionPolicy(),
                brand.getMessagingPolicy(),
                brand.getBitRate(),
                brand.getPopularityRate());
    }

    private Uni<RadioStationStatusDTO> oneTimeStreamToStatusDTO(OneTimeStream ots) {
        if (ots == null) return Uni.createFrom().nullItem();
        return buildStatusDTO(
                ots.getLocalizedName().getOrDefault(
                        ots.getCountry().getPreferredLanguage(), ots.getSlugName()),
                ots.getSlugName(),
                ots.getManagedBy().toString(),
                ots.getAiAgentId(),
                ots.getAiOverriding(),
                ots.getAiAgentStatus(),
                ots.getStatus(),
                ots.getCountry().name(),
                ots.getColor(),
                ots.getDescription(),
                false,
                ots.getMasterBrand().getOneTimeStreamPolicy(),
                ots.getMasterBrand().getSubmissionPolicy(),
                ots.getMasterBrand().getMessagingPolicy(),
                ots.getBitRate(),
                0.0);
    }

    private Uni<RadioStationStatusDTO> buildStatusDTO(
            String stationName,
            String slugName,
            String managedByType,
            UUID aiAgentId,
            AiOverriding overriddenAiDj,
            AiAgentStatus agentStatus,
            StreamStatus stationStatus,
            String stationCountryCode,
            String color,
            String description,
            boolean includeAnimation,
            SubmissionPolicy oneTimeStreamPolicy,
            SubmissionPolicy submissionPolicy,
            SubmissionPolicy messagingPolicy,
            long bitRate,
            double popularityRate
    ) {
        String currentStatus = stationStatus != null
                ? stationStatus.name()
                : StreamStatus.OFF_LINE.name();

        String resolvedAgentStatus = agentStatus != null
                ? agentStatus.name()
                : AiAgentStatus.UNDEFINED.name();

        if (aiAgentId == null) {
            RadioStationStatusDTO dto = new RadioStationStatusDTO(
                    stationName,
                    slugName,
                    managedByType,
                    null,
                    null,
                    resolvedAgentStatus,
                    currentStatus,
                    stationCountryCode,
                    color,
                    description,
                    0,
                    includeAnimation ? animationService.generateRandomAnimation() : null,
                    oneTimeStreamPolicy,
                    submissionPolicy,
                    messagingPolicy,
                    bitRate,
                    popularityRate
            );
            return Uni.createFrom().item(dto);
        }

        return aiAgentService
                .getById(aiAgentId, SuperUser.build(), LanguageCode.en)
                .onItem().transform(aiAgent -> {
                    LanguageTag selectedLang = selectLanguageByWeight(aiAgent);

                    String djName = (overriddenAiDj != null && overriddenAiDj.getName() != null)
                            ? overriddenAiDj.getName()
                            : aiAgent.getName();

                    return new RadioStationStatusDTO(
                            stationName,
                            slugName,
                            managedByType,
                            djName,
                            selectedLang.toLanguageCode().name(),
                            resolvedAgentStatus,
                            currentStatus,
                            stationCountryCode,
                            color,
                            description,
                            0,
                            includeAnimation ? animationService.generateRandomAnimation() : null,
                            oneTimeStreamPolicy,
                            submissionPolicy,
                            messagingPolicy,
                            bitRate,
                            popularityRate
                    );
                })
                .onFailure().recoverWithItem(() ->
                        new RadioStationStatusDTO(
                                stationName,
                                slugName,
                                managedByType,
                                null,
                                null,
                                resolvedAgentStatus,
                                currentStatus,
                                stationCountryCode,
                                color,
                                description,
                                0,
                                includeAnimation ? animationService.generateRandomAnimation() : null,
                                oneTimeStreamPolicy,
                                submissionPolicy,
                                messagingPolicy,
                                bitRate,
                                popularityRate
                        )
                );
    }


    public Uni<Integer> rateSoundFragmentByAction(
            String brand, UUID id, RatingAction action, String prev) {
        return soundFragmentService
                .rateSoundFragmentByAction(brand, id, action, prev, SuperUser.build());
    }

    private Uni<List<IStream>> getOnlineStations() {
        return Uni.createFrom().item(
                new ArrayList<>(radioStationPool.getOnlineStationsSnapshot()));
    }

    private LanguageTag selectLanguageByWeight(AiAgent agent) {

        List<LanguagePreference> p = agent.getPreferredLang();
        if (p == null || p.isEmpty()) return LanguageTag.EN_US;
        if (p.size() == 1) return p.getFirst().getLanguageTag();

        double total = p.stream().mapToDouble(LanguagePreference::getWeight).sum();
        double r = random.nextDouble() * total;
        double c = 0;
        for (LanguagePreference lp : p) {
            c += lp.getWeight();
            if (r <= c) return lp.getLanguageTag();
        }
        return p.getFirst().getLanguageTag();
    }
}
