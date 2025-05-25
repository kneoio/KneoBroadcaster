document.addEventListener('DOMContentLoaded', function() {
    console.log('[Player.js] DOMContentLoaded: Initializing Bratan Radio Player script.');

    var audio = document.getElementById('audioPlayer');
    var errorMessageDiv = document.getElementById('error-message');
    var streamUrlDisplayDiv = document.getElementById('stream-url-display');
    var songTitleDisplay = document.getElementById('song-title-display');

    var playPauseButton = document.getElementById('play-pause');
    var seekBar = document.getElementById('seek-bar');
    var currentTimeDisplay = document.getElementById('current-time');
    var durationDisplay = document.getElementById('duration');
    var volumeButton = document.getElementById('volume-button');
    var volumeBar = document.getElementById('volume-bar');
    var themeToggleButton = document.getElementById('theme-toggle');

    var audioSrc = null;
    let currentTheme = 'light';
    let retryCount = 0;
    const MAX_RETRIES = 3;
    let hls = null;

    const HLS_BASE_URL = window.location.origin;
    const HLS_PATH_SUFFIX = '/radio/stream.m3u8';
    const PARAMETER_NAME = 'radio';
    const THEME_STORAGE_KEY = 'bratan-radio-theme';

    const urlParams = new URLSearchParams(window.location.search);
    const dynamicRadioName = urlParams.get(PARAMETER_NAME);

    // Theme Toggle Functions
    function enableDarkTheme() {
        document.body.classList.add('dark-theme');
        currentTheme = 'dark';
        localStorage.setItem(THEME_STORAGE_KEY, 'dark');
        console.info('[Player.js] Theme changed to Dark.');
    }

    function enableLightTheme() {
        document.body.classList.remove('dark-theme');
        currentTheme = 'light';
        localStorage.setItem(THEME_STORAGE_KEY, 'light');
        console.info('[Player.js] Theme changed to Light.');
    }

    function toggleTheme() {
        if (currentTheme === 'light') {
            enableDarkTheme();
        } else {
            enableLightTheme();
        }
    }

    const savedTheme = localStorage.getItem(THEME_STORAGE_KEY);
    if (savedTheme === 'dark') {
        enableDarkTheme();
    } else {
        enableLightTheme();
    }
    themeToggleButton.addEventListener('click', toggleTheme);

    function displayMessage(element, message, isError = false) {
        element.textContent = message;
        element.style.display = message ? 'block' : 'none';

        if (isError) {
            element.classList.add('error');
            element.classList.remove('status');
        } else {
            element.classList.add('status');
            element.classList.remove('error');
        }

        if (message) {
            if (element === errorMessageDiv) {
                streamUrlDisplayDiv.style.display = 'none';
            } else {
                errorMessageDiv.style.display = 'none';
            }
        }
    }

    function clearMessages() {
        displayMessage(errorMessageDiv, '');
        displayMessage(streamUrlDisplayDiv, '');
    }

    function formatTime(seconds) {
        if (isNaN(seconds)) return '0:00';
        const minutes = Math.floor(seconds / 60);
        const remainingSeconds = Math.floor(seconds % 60);
        const formattedSeconds = remainingSeconds < 10 ? '0' + remainingSeconds : remainingSeconds;
        return minutes + ':' + formattedSeconds;
    }

    function updatePlayPauseButton() {
        if (audio.paused || audio.ended) {
            playPauseButton.classList.remove('paused');
            playPauseButton.classList.add('play');
        } else {
            playPauseButton.classList.add('paused');
            playPauseButton.classList.remove('play');
        }
    }

    function updateVolumeButton() {
        if (audio.muted || audio.volume === 0) {
            volumeButton.classList.add('muted');
            volumeButton.classList.remove('low-volume');
        } else if (audio.volume < 0.5) {
            volumeButton.classList.add('low-volume');
            volumeButton.classList.remove('muted');
        }
        else {
            volumeButton.classList.remove('muted', 'low-volume');
        }
    }

    function loadSourceWithRetry() {
        if (hls) {
            console.log(`[Player.js] Attempting to load source (retry ${retryCount}/${MAX_RETRIES})`);
            hls.loadSource(audioSrc);
            hls.startLoad();
        }
    }

    function initializeHlsPlayer() {
        if (hls) {
            hls.destroy();
        }

        hls = new Hls({
            maxBufferLength: 30,
            maxMaxBufferLength: 600,
            maxBufferSize: 60*1000*1000,
            maxBufferHole: 0.5,
            lowLatencyMode: false,
            abrEwmaDefaultEstimate: 500000,
            backBufferLength: 30
        });

        hls.attachMedia(audio);

        hls.on(Hls.Events.MANIFEST_PARSED, function(event, data) {
            console.log('[Player.js] HLS.js Event: MANIFEST_PARSED. Levels:', data.levels.length);
            retryCount = 0;
            displayMessage(streamUrlDisplayDiv, `Stream loaded. Click the ▶ button to play.`);
            seekBar.max = audio.duration;
            durationDisplay.textContent = formatTime(audio.duration);
            if(songTitleDisplay && (!songTitleDisplay.textContent || songTitleDisplay.textContent === 'Loading stream info...')) {
                songTitleDisplay.textContent = 'Press play to start radio';
            }
        });

        hls.on(Hls.Events.FRAG_CHANGED, function(event, data) {
            if (songTitleDisplay && data && data.frag && data.frag.title) {
                songTitleDisplay.textContent = data.frag.title;
            }
        });

        hls.on(Hls.Events.ERROR, function(event, data) {
            console.error('[Player.js] HLS.js error:', data);

            if (data.fatal) {
                switch(data.type) {
                    case Hls.ErrorTypes.NETWORK_ERROR:
                        console.log('[Player.js] Fatal network error encountered, trying to recover...');
                        displayMessage(errorMessageDiv, 'Connection lost. Attempting to reconnect...', true);
                        if (retryCount < MAX_RETRIES) {
                            retryCount++;
                            setTimeout(loadSourceWithRetry, 2000 * retryCount);
                        } else {
                            displayMessage(errorMessageDiv, 'Failed to reconnect after multiple attempts. Please reload page.', true);
                        }
                        break;
                    case Hls.ErrorTypes.MEDIA_ERROR:
                        console.log('[Player.js] Fatal media error encountered, trying to recover...');
                        displayMessage(errorMessageDiv, 'Playback error. Attempting to recover...', true);
                        hls.recoverMediaError();
                        break;
                    default:
                        console.error('[Player.js] Unrecoverable error');
                        displayMessage(errorMessageDiv, 'Unrecoverable playback error. Please reload page.', true);
                        break;
                }
            } else {
                if (data.details === 'bufferStalledError') {
                    console.log('[Player.js] Buffer stalled, attempting to recover...');
                    hls.startLoad();
                }
            }
        });

        loadSourceWithRetry();
    }

    if (!dynamicRadioName) {
        console.warn(`[Player.js] URL parameter "${PARAMETER_NAME}" is MISSING.`);
        displayMessage(errorMessageDiv, `Error: The required URL parameter "${PARAMETER_NAME}" is missing (e.g., ?${PARAMETER_NAME}=nunoscope).`, true);
        audio.style.display = 'none';
        playPauseButton.disabled = true;
        seekBar.disabled = true;
        volumeBar.disabled = true;
        volumeButton.disabled = true;
        if(songTitleDisplay) songTitleDisplay.textContent = 'Radio parameter missing';
    } else {
        console.log(`[Player.js] URL parameter "${PARAMETER_NAME}" found: ${dynamicRadioName}`);
        audioSrc = `${HLS_BASE_URL}/${dynamicRadioName}${HLS_PATH_SUFFIX}`;
        displayMessage(streamUrlDisplayDiv, `Attempting to load stream: ${audioSrc}`);
        if(songTitleDisplay) songTitleDisplay.textContent = 'Loading stream info...';

        if (Hls.isSupported()) {
            console.log('[Player.js] HLS.js is supported. Initializing HLS player.');
            initializeHlsPlayer();
        } else if (audio.canPlayType('application/vnd.apple.mpegurl')) {
            console.log('[Player.js] Native HLS playback is supported. Using native player.');
            audio.src = audioSrc;
            displayMessage(streamUrlDisplayDiv, `Stream loaded. Click the ▶ button to play.`);
        } else {
            console.warn('[Player.js] HLS playback not supported by this browser.');
            displayMessage(errorMessageDiv, 'Error: Your browser does not support HLS streaming.', true);
            playPauseButton.disabled = true;
        }

        audio.addEventListener('playing', function() {
            console.log('[Player.js] Audio event: playing');
            clearMessages();
        });

        playPauseButton.addEventListener('click', function() {
            if (audio.paused || audio.ended) {
                console.log('[Player.js] Play button clicked. Attempting to play.');
                audio.play().catch(function(error) {
                    console.error('[Player.js] Play failed after click:', error);
                    displayMessage(errorMessageDiv, 'Could not start playback after click. Try again.', true);
                });
            } else {
                console.log('[Player.js] Pause button clicked. Attempting to pause.');
                audio.pause();
            }
        });

        audio.addEventListener('play', function() {
            console.info('[Player.js] Audio event: play (playback has begun or resumed)');
            updatePlayPauseButton();
        });

        audio.addEventListener('pause', function() {
            console.info('[Player.js] Audio event: pause');
            updatePlayPauseButton();
            if (!audio.ended) {
                displayMessage(streamUrlDisplayDiv, `Paused: ${audioSrc}`);
            }
        });

        audio.addEventListener('ended', function() {
            console.log('[Player.js] Audio event: ended');
            updatePlayPauseButton();
            displayMessage(streamUrlDisplayDiv, `Stream ended.`);
            if(songTitleDisplay) songTitleDisplay.textContent = 'Stream ended';
        });

        audio.addEventListener('timeupdate', function() {
            if (!seekBar.dragging && audio.duration !== Infinity && !isNaN(audio.duration)) {
                seekBar.value = audio.currentTime;
                currentTimeDisplay.textContent = formatTime(audio.currentTime);
            } else if (audio.duration === Infinity) {
                currentTimeDisplay.textContent = '';
                durationDisplay.textContent = 'LIVE';
                seekBar.style.visibility = 'hidden';
            } else {
                currentTimeDisplay.textContent = formatTime(audio.currentTime);
                durationDisplay.textContent = formatTime(audio.duration);
                seekBar.style.visibility = 'visible';
            }
        });

        audio.addEventListener('stalled', function() {
            console.log('[Player.js] Audio stalled event');
            displayMessage(errorMessageDiv, 'Stream stalled. Attempting to recover...', true);
            if (hls) hls.startLoad();
        });

        audio.addEventListener('waiting', function() {
            console.log('[Player.js] Audio waiting event');
            displayMessage(streamUrlDisplayDiv, 'Buffering...', false);
        });

        seekBar.addEventListener('input', function() {
            seekBar.dragging = true;
            currentTimeDisplay.textContent = formatTime(seekBar.value);
        });

        seekBar.addEventListener('change', function() {
            console.log('[Player.js] Seek bar changed (value committed):', seekBar.value);
            seekBar.dragging = false;
            audio.currentTime = seekBar.value;
        });

        volumeBar.addEventListener('input', function() {
            audio.volume = volumeBar.value;
            audio.muted = false;
            updateVolumeButton();
        });

        let lastVolume = 1;
        volumeButton.addEventListener('click', function() {
            console.log('[Player.js] Volume button clicked.');
            if (audio.muted || audio.volume === 0) {
                lastVolume = volumeBar.value > 0 ? volumeBar.value : 1;
                audio.muted = false;
                audio.volume = lastVolume;
                volumeBar.value = audio.volume;
            } else {
                lastVolume = audio.volume;
                audio.muted = true;
                audio.volume = 0;
                volumeBar.value = 0;
            }
            updateVolumeButton();
        });

        volumeBar.value = audio.volume;
        updateVolumeButton();

        audio.addEventListener('volumechange', function() {
            if (!volumeBar.dragging) {
                volumeBar.value = audio.volume;
            }
            updateVolumeButton();
        });

        window.addEventListener('online', function() {
            console.log('[Player.js] Network connection restored');
            if (audio.paused && retryCount < MAX_RETRIES) {
                displayMessage(streamUrlDisplayDiv, 'Connection restored. Attempting to play...');
                if (hls) hls.startLoad();
                audio.play().catch(e => console.error('[Player.js] Play failed:', e));
            }
        });
    }
});