// js/player.js
document.addEventListener('DOMContentLoaded', function() {
    // ---- TEST LOG ----
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

    const HLS_BASE_URL = window.location.origin;
    const HLS_PATH_SUFFIX = '/radio/stream.m3u8';
    const PARAMETER_NAME = 'radio';
    const THEME_STORAGE_KEY = 'bratan-radio-theme';

    const urlParams = new URLSearchParams(window.location.search);
    const dynamicRadioName = urlParams.get(PARAMETER_NAME);

    // --- Theme Toggle Functions ---
    function enableDarkTheme() {
        document.body.classList.add('dark-theme');
        currentTheme = 'dark';
        localStorage.setItem(THEME_STORAGE_KEY, 'dark');
        // ---- TEST LOG ----
        console.info('[Player.js] Theme changed to Dark.');
    }

    function enableLightTheme() {
        document.body.classList.remove('dark-theme');
        currentTheme = 'light';
        localStorage.setItem(THEME_STORAGE_KEY, 'light');
        // ---- TEST LOG ----
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
        enableLightTheme(); // Default to light if no saved theme or saved is light
    }
    themeToggleButton.addEventListener('click', toggleTheme);
    // --- End Theme Toggle Functions ---

    function displayMessage(element, message, isError = false) {
        // ... (rest of the function remains the same)
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
        // ... (rest of the function remains the same)
        if (isNaN(seconds)) return '0:00';
        const minutes = Math.floor(seconds / 60);
        const remainingSeconds = Math.floor(seconds % 60);
        const formattedSeconds = remainingSeconds < 10 ? '0' + remainingSeconds : remainingSeconds;
        return minutes + ':' + formattedSeconds;
    }

    function updatePlayPauseButton() {
        // ... (rest of the function remains the same)
        if (audio.paused || audio.ended) {
            playPauseButton.classList.remove('paused');
            playPauseButton.classList.add('play');
        } else {
            playPauseButton.classList.add('paused');
            playPauseButton.classList.remove('play');
        }
    }

    function updateVolumeButton() {
        // ... (rest of the function remains the same)
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

    if (!dynamicRadioName) {
        // ---- TEST LOG (using warn for this case) ----
        console.warn(`[Player.js] URL parameter "${PARAMETER_NAME}" is MISSING.`);
        displayMessage(errorMessageDiv, `Error: The required URL parameter "${PARAMETER_NAME}" is missing (e.g., ?${PARAMETER_NAME}=nunoscope).`, true);
        audio.style.display = 'none';
        playPauseButton.disabled = true;
        seekBar.disabled = true;
        volumeBar.disabled = true;
        volumeButton.disabled = true;
        if(songTitleDisplay) songTitleDisplay.textContent = 'Radio parameter missing';
    } else {
        // ---- TEST LOG ----
        console.log(`[Player.js] URL parameter "${PARAMETER_NAME}" found: ${dynamicRadioName}`);
        audioSrc = `${HLS_BASE_URL}/${dynamicRadioName}${HLS_PATH_SUFFIX}`;
        displayMessage(streamUrlDisplayDiv, `Attempting to load stream: ${audioSrc}`);
        if(songTitleDisplay) songTitleDisplay.textContent = 'Loading stream info...';

        if (Hls.isSupported()) {
            // ---- TEST LOG ----
            console.log('[Player.js] HLS.js is supported. Initializing HLS player.');
            var hls = new Hls({
                // debug: true // You can also enable HLS.js internal debugging if needed
            });
            hls.loadSource(audioSrc);
            hls.attachMedia(audio);

            hls.on(Hls.Events.MANIFEST_PARSED, function(event, data) {
                // ---- TEST LOG ----
                console.log('[Player.js] HLS.js Event: MANIFEST_PARSED. Levels:', data.levels.length);
                displayMessage(streamUrlDisplayDiv, `Stream loaded. Click the ▶ button to play.`);
                seekBar.max = audio.duration;
                durationDisplay.textContent = formatTime(audio.duration);
                if(songTitleDisplay && (!songTitleDisplay.textContent || songTitleDisplay.textContent === 'Loading stream info...')) {
                    songTitleDisplay.textContent = 'Press play to start radio';
                }
            });

            hls.on(Hls.Events.FRAG_CHANGED, function(event, data) {
                // ---- TEST LOG ----
                // console.debug('[Player.js] HLS.js Event: FRAG_CHANGED. Title:', data.frag.title); // Using debug for frequent events
                if (songTitleDisplay && data && data.frag && data.frag.title) {
                    songTitleDisplay.textContent = data.frag.title;
                }
            });

            hls.on(Hls.Events.ERROR, function(event, data) {
                console.error('[Player.js] HLS.js error:', data); // Already a console call
                if (data.fatal) {
                    // ... (rest of error handling)
                }
            });

        } else if (audio.canPlayType('application/vnd.apple.mpegurl')) {
            // ---- TEST LOG ----
            console.log('[Player.js] Native HLS playback is supported. Using native player.');
            // ... (rest of native HLS handling)
        } else {
            // ---- TEST LOG (using warn) ----
            console.warn('[Player.js] HLS playback not supported by this browser.');
            // ... (rest of no support handling)
        }


        audio.addEventListener('playing', function() {
            // ---- TEST LOG ----
            console.log('[Player.js] Audio event: playing');
            clearMessages();
            if (songTitleDisplay && songTitleDisplay.textContent === 'Press play to start radio') {
                // songTitleDisplay.textContent = 'Loading song...';
            }
        });


        playPauseButton.addEventListener('click', function() {
            if (audio.paused || audio.ended) {
                // ---- TEST LOG ----
                console.log('[Player.js] Play button clicked. Attempting to play.');
                audio.play().catch(function(error) {
                    console.error('[Player.js] Play failed after click:', error);
                    displayMessage(errorMessageDiv, 'Could not start playback after click. Try again.', true);
                });
            } else {
                // ---- TEST LOG ----
                console.log('[Player.js] Pause button clicked. Attempting to pause.');
                audio.pause();
            }
        });

        audio.addEventListener('play', function() {
            // ---- TEST LOG ----
            console.info('[Player.js] Audio event: play (playback has begun or resumed)');
            updatePlayPauseButton();
        });
        audio.addEventListener('pause', function() {
            // ---- TEST LOG ----
            console.info('[Player.js] Audio event: pause');
            updatePlayPauseButton();
            if (!audio.ended) {
                displayMessage(streamUrlDisplayDiv, `Paused: ${audioSrc}`);
            }
        });
        // ... (rest of player.js)
        audio.addEventListener('ended', function() {
            console.log('[Player.js] Audio event: ended');
            updatePlayPauseButton();
            displayMessage(streamUrlDisplayDiv, `Stream ended.`);
            if(songTitleDisplay) songTitleDisplay.textContent = 'Stream ended';
        });

        audio.addEventListener('timeupdate', function() {
            // console.debug('[Player.js] Audio event: timeupdate', audio.currentTime); // This is very frequent, use debug
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

        seekBar.addEventListener('input', function() {
             // console.debug('[Player.js] Seek bar input:', seekBar.value); // Frequent, use debug
             seekBar.dragging = true;
             currentTimeDisplay.textContent = formatTime(seekBar.value);
        });

        seekBar.addEventListener('change', function() {
             console.log('[Player.js] Seek bar changed (value committed):', seekBar.value);
             seekBar.dragging = false;
             audio.currentTime = seekBar.value;
        });

        volumeBar.addEventListener('input', function() {
             // console.debug('[Player.js] Volume bar input:', volumeBar.value); // Frequent, use debug
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
             // console.debug('[Player.js] Audio event: volumechange', audio.volume); // Frequent, use debug
             if (!volumeBar.dragging) { // Ensure this flag exists or handle appropriately
                 volumeBar.value = audio.volume;
             }
             updateVolumeButton();
         });
    }
});