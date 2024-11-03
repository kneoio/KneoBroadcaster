class RadioPlayer {
    constructor() {
        this.audio = document.getElementById('audio');
        this.status = document.getElementById('status');
        this.hls = null;
        this.streamUrl = `${window.location.protocol}//${window.location.host}/radio/stream`;
    }

    start() {
        this.status.textContent = 'Connecting to stream...';

        if (Hls.isSupported()) {
            if (!this.hls) {
                this.hls = new Hls({
                    manifestLoadingTimeOut: 20000,
                    manifestLoadingMaxRetry: 3,
                    manifestLoadingRetryDelay: 500,
                });

                this.hls.loadSource(this.streamUrl);
                this.hls.attachMedia(this.audio);

                this.hls.on(Hls.Events.MANIFEST_PARSED, () => {
                    this.status.textContent = 'Stream loaded successfully';
                    this.audio.play()
                        .catch(error => {
                            this.status.textContent = `Playback error: ${error.message}`;
                        });
                });

                this.hls.on(Hls.Events.ERROR, (event, data) => {
                    if (data.fatal) {
                        switch(data.type) {
                            case Hls.ErrorTypes.NETWORK_ERROR:
                                this.status.textContent = `Network error: ${data.details}`;
                                this.hls.startLoad();
                                break;
                            case Hls.ErrorTypes.MEDIA_ERROR:
                                this.status.textContent = `Media error: ${data.details}`;
                                this.hls.recoverMediaError();
                                break;
                            default:
                                this.status.textContent = `Fatal error: ${data.type}`;
                                this.stop();
                                break;
                        }
                    }
                });
            } else {
                this.audio.play()
                    .catch(error => {
                        this.status.textContent = `Playback error: ${error.message}`;
                    });
            }
        } else if (this.audio.canPlayType('application/vnd.apple.mpegurl')) {
            this.audio.src = this.streamUrl;
            this.audio.play()
                .catch(error => {
                    this.status.textContent = `Playback error: ${error.message}`;
                });
        } else {
            this.status.textContent = 'HLS playback not supported in this browser';
        }
    }

    stop() {
        this.audio.pause();
        if (this.hls) {
            this.hls.destroy();
            this.hls = null;
        }
        this.status.textContent = 'Playback stopped';
    }
}

const player = new RadioPlayer();

window.startPlaying = () => player.start();
window.stopPlaying = () => player.stop();