class RadioPlayer {
    constructor() {
        this.audio = document.getElementById('audio');
        this.status = document.getElementById('status');
        this.hls = null;
        this.streamUrl = `${window.location.protocol}//${window.location.host}/radio/stream`;
        this.consoleLogElement = document.getElementById('consoleLogs'); // Make sure this exists in your HTML

        this.initPlayer();
    }

    initPlayer() {
        console.log('Initializing player...');

        if (Hls.isSupported()) {
            console.log('Using HLS.js');
            this.hls = new Hls({
                maxAudioFramesDrift: 1,
                forceKeyFrameOnDiscontinuity: false
            });
            this.hls.loadSource(this.streamUrl);
            this.hls.attachMedia(this.audio);

            // Add event listeners
            this.addEventListeners();

            // Start loading the stream
            this.hls.startLoad();
        } else if (this.audio.canPlayType('application/vnd.apple.mpegurl')) {
            console.log('Falling back to regular HLS');
            this.audio.src = this.streamUrl;
            this.audio.play().catch(error => {
                console.error(`Playback error: ${error.message}`);
                this.status.textContent = `Playback error: ${error.message}`;
            });
        } else {
            console.error('HLS playback not supported in this browser');
            this.status.textContent = 'HLS playback not supported in this browser';
        }
    }

    addEventListeners() {
        console.log('Adding event listeners...');
        this.hls.on(Hls.Events.MANIFEST_PARSED, () => {
            console.log('Manifest parsed successfully');
            this.status.textContent = 'Stream loaded successfully';
            this.checkAudioPlayback();
        });

        this.hls.on(Hls.Events.ERROR, (event, data) => {
            if (data.fatal) {
                console.error(`Fatal error: ${data.type}`);
                switch(data.type) {
                    case Hls.ErrorTypes.NETWORK_ERROR:
                        console.error(`Network error: ${data.details}`);
                        this.status.textContent = `Network error: ${data.details}`;
                        this.hls.startLoad();
                        break;
                    case Hls.ErrorTypes.MEDIA_ERROR:
                        console.error(`Media error: ${data.details}`);
                        this.status.textContent = `Media error: ${data.details}`;
                        this.hls.recoverMediaError();
                        break;
                    default:
                        console.error(`Fatal error: ${data.type}`);
                        this.stop();
                        break;
                }
            }
        });
    }

    checkAudioPlayback() {
        console.log('Checking audio playback...');
        setTimeout(() => {
            if (!this.audio.playedDuration || this.audio.playedDuration === 0) {
                console.warn('No audio detected after manifest parsed');
                this.hls.forceKeyFrameOnDiscontinuity = true;
                this.hls.startLoad();
            }
        }, 5000);
    }

    stop() {
        console.log('Stopping playback...');
        this.audio.pause();
        if (this.hls) {
            this.hls.destroy();
            this.hls = null;
        }
        this.status.textContent = 'Playback stopped';
    }
}

window.startPlaying = () => {
    console.log('Starting playback...');
    new RadioPlayer().start();
};

window.stopPlaying = () => {
    console.log('Stopping playback...');
    new RadioPlayer().stop();
};

// If this script is loaded as a module, export the RadioPlayer class
if (typeof module !== 'undefined') {
    module.exports = { RadioPlayer };
}
