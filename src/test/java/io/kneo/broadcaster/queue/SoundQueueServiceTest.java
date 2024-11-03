package io.kneo.broadcaster.queue;



class SoundQueueServiceTest {

  /*  @Mock
    private Playlist mockPlaylist;

    @InjectMocks
    private SoundQueueService soundQueueService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testGetNextFile_IteratesThroughPlaylist() {
        List<SoundFragment> playlist = Arrays.asList(
                SoundFragment.builder().localPath(QUEUE_FILES + "\\file1.wav").build(),
                SoundFragment.builder().localPath(QUEUE_FILES + "\\file2.wav").build(),
                SoundFragment.builder().localPath(QUEUE_FILES + "\\file3.wav").build());
        when(mockPlaylist.getPlayList()).thenReturn(playlist);

        assertEquals(Paths.get(QUEUE_FILES, "file1.wav").toString(), soundQueueService.getNextFile());
        assertEquals(Paths.get(QUEUE_FILES, "file2.wav").toString(), soundQueueService.getNextFile());
        assertEquals(Paths.get(QUEUE_FILES, "file3.wav").toString(), soundQueueService.getNextFile());
        assertEquals(Paths.get(QUEUE_FILES, "file1.wav").toString(), soundQueueService.getNextFile());
    }



    @Test
    void testGetNextFile_ReturnsFillerAudioWhenPlaylistIsEmpty() {
        try (MockedStatic<Playlist> mockedPlaylist = Mockito.mockStatic(Playlist.class)) {
            when(mockPlaylist.getPlayList()).thenReturn(Collections.emptyList());
            assertEquals(FILLER_AUDIO, soundQueueService.getNextFile());
        }
    }

    @Test
    void testGetNextFile_IncrementsIterationCorrectly() {
        try (MockedStatic<Playlist> mockedPlaylist = Mockito.mockStatic(Playlist.class)) {
            List<SoundFragment> playlist = Arrays.asList(
                    SoundFragment.builder().localPath(QUEUE_FILES + "\\file1.wav").build(),
                    SoundFragment.builder().localPath(QUEUE_FILES + "\\file2.wav").build());
            when(mockPlaylist.getPlayList()).thenReturn(playlist);

            assertEquals(Paths.get(QUEUE_FILES, "file1.wav").toString(), soundQueueService.getNextFile());
            assertEquals(Paths.get(QUEUE_FILES, "file2.wav").toString(), soundQueueService.getNextFile());
            assertEquals(Paths.get(QUEUE_FILES, "file1.wav").toString(), soundQueueService.getNextFile());
        }
    }*/
}
