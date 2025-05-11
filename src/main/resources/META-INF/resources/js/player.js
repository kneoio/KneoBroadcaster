document.addEventListener('DOMContentLoaded', function() {
    var audio = document.getElementById('audioPlayer');
    var errorMessageDiv = document.getElementById('error-message');
    var streamUrlDisplayDiv = document.getElementById('stream-url-display');

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
    }

    function enableLightTheme() {
        document.body.classList.remove('dark-theme');
        currentTheme = 'light';
        localStorage.setItem(THEME_STORAGE_KEY, 'light');
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
    // --- End Theme Toggle Functions ---


    // Function to display messages
    // Clears the other message div when displaying a new message
    function displayMessage(element, message, isError = false) {
        element.textContent = message;
        element.style.display = message ? 'block' : 'none';

        // Set CSS classes based on message type
        if (isError) {
            element.classList.add('error');
            element.classList.remove('status');
        } else {
            element.classList.add('status');
            element.classList.remove('error');
        }

        // Hide the other message div when showing a new one
        if (message) { // Only hide the other if a message is actually being displayed
            if (element === errorMessageDiv) {
                streamUrlDisplayDiv.style.display = 'none';
            } else {
                errorMessageDiv.style.display = 'none';
            }
        }
    }

    // Function to clear all messages
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

    if (!dynamicRadioName) {
        displayMessage(errorMessageDiv, `Error: The required URL parameter "${PARAMETER_NAME}" is missing (e.g., ?${PARAMETER_NAME}=nunoscope).`, true);
        audio.style.display = 'none';
         playPauseButton.disabled = true;
         seekBar.disabled = true;
         volumeBar.disabled = true;
         volumeButton.disabled = true;
    } else {
        audioSrc = `${HLS_BASE_URL}/${dynamicRadioName}${HLS_PATH_SUFFIX}`;
        displayMessage(streamUrlDisplayDiv, `Attempting to load stream: ${audioSrc}`);

        if (Hls.isSupported()) {
            var hls = new Hls();
            hls.loadSource(audioSrc);
            hls.attachMedia(audio);

            hls.on(Hls.Events.MANIFEST_PARSED, function() {
                displayMessage(streamUrlDisplayDiv, `Stream loaded. Click the ▶ button to play.`);
                 seekBar.max = audio.duration;
                 durationDisplay.textContent = formatTime(audio.duration);
            });

             hls.on(Hls.Events.ERROR, function(event, data) {
                console.error('Hls.js error:', data);
                // Always display fatal errors
                if (data.fatal) {
                    displayMessage(errorMessageDiv, 'Fatal HLS.js error: ' + (data.details || 'Unknown error'), true);
                     // Attempt specific recoveries
                    switch(data.type) {
                        case Hls.ErrorTypes.NETWORK_ERROR:
                             console.log('Attempting network error recovery...');
                             // Only attempt startLoad if it's a network error
                            hls.startLoad();
                            break;
                        case Hls.ErrorTypes.MEDIA_ERROR:
                             console.log('Attempting media error recovery...');
                             hls.recoverMediaError(); // Try to recover media errors
                            break;
                         case Hls.ErrorTypes.KEY_SYSTEM_ERROR:
                              errorMessageDiv.textContent = 'DRM Key System Error: ' + (data.details || 'Unknown error');
                              hls.destroy(); // Cannot recover from key system errors
                              break;
                         case Hls.ErrorTypes.MUX_ERROR:
                             errorMessageDiv.textContent = 'Muxing Error: ' + (data.details || 'Unknown error');
                              hls.destroy(); // Mux errors usually unrecoverable by player
                             break;
                        default:
                            // For other fatal errors, destroy Hls.js
                            hls.destroy();
                            errorMessageDiv.textContent = 'Unrecoverable HLS.js error: ' + (data.details || 'Unknown error');
                            break;
                    }
                } else {
                     // Display non-fatal errors/warnings in the error message area
                     // These are the ones we'll clear on playback
                     displayMessage(errorMessageDiv, 'HLS.js warning: ' + (data.details || 'Unknown warning'), false); // Use error div for warnings too
                }
            });


        } else if (audio.canPlayType('application/vnd.apple.mpegurl')) {
            audio.src = audioSrc;
            audio.addEventListener('loadedmetadata', function() {
                displayMessage(streamUrlDisplayDiv, `Stream loaded (native). Click the ▶ button to play.`);
                 seekBar.max = audio.duration;
                 durationDisplay.textContent = formatTime(audio.duration);
            });
             audio.addEventListener('error', function() {
                 console.error('Native audio error');
                 displayMessage(errorMessageDiv, 'Native audio playback error.', true);
             });
        } else {
            displayMessage(errorMessageDiv, 'Your browser does not support HLS audio playback.', true);
            audio.style.display = 'none';
            playPauseButton.disabled = true;
            seekBar.disabled = true;
            volumeBar.disabled = true;
            volumeButton.disabled = true;
        }

        // --- Event Listener to Clear Messages on Playback ---
        // When the audio starts playing, clear any existing warning/error messages
        audio.addEventListener('playing', function() {
             clearMessages(); // Clear both error and status messages
             // You could potentially display a brief "Playing..." status here if needed
        });

         // Optional: Clear messages also on 'canplay' or 'canplaythrough'
         // audio.addEventListener('canplay', clearMessages);
         // audio.addEventListener('canplaythrough', clearMessages);
        // --- End Message Clearing Listener ---


        // --- Add Event Listeners for Custom Controls ---

        playPauseButton.addEventListener('click', function() {
            if (audio.paused || audio.ended) {
                // Attempt to play
                audio.play().catch(function(error) {
                    console.error('Play failed after click:', error);
                     displayMessage(errorMessageDiv, 'Could not start playback after click. Try again.', true);
                });
            } else {
                // Pause
                audio.pause();
            }
        });

        audio.addEventListener('play', function() {
             updatePlayPauseButton();
             // Message is cleared by 'playing' event listener
        });
        audio.addEventListener('pause', function() {
            updatePlayPauseButton();
             if (!audio.ended) {
                 displayMessage(streamUrlDisplayDiv, `Paused: ${audioSrc}`);
             }
        });
        audio.addEventListener('ended', function() {
            updatePlayPauseButton();
             displayMessage(streamUrlDisplayDiv, `Stream ended.`);
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

        seekBar.addEventListener('input', function() {
             seekBar.dragging = true;
             currentTimeDisplay.textContent = formatTime(seekBar.value);
        });

        seekBar.addEventListener('change', function() {
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
                 updateVolumeButton();
             }
         });
    }
});