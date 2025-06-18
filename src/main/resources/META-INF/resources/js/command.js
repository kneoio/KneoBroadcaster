// js/command.js

document.addEventListener('DOMContentLoaded', () => {
    const wakeUpButton = document.getElementById('wake-up-button');
    const audioPlayer = document.getElementById('audioPlayer');

    if (wakeUpButton && window.radioApp && window.radioApp.radioName) {

        const WAKEUP_ENDPOINT = `/${window.radioApp.radioName}/radio/wakeup`;

        wakeUpButton.addEventListener('click', () => {
            console.log(`Wake up button clicked. Sending PUT request to ${WAKEUP_ENDPOINT}`);

            wakeUpButton.classList.add('sending');
            wakeUpButton.textContent = 'Waking up...';
            wakeUpButton.disabled = true;

            fetch(WAKEUP_ENDPOINT, {
                method: 'PUT',
                headers: {
                    'Content-Type': 'application/json',
                },
            })
            .then(response => {
                if (response.ok) {
                    console.log('Wake up signal sent successfully.');
                    return response.json().catch(() => ({}));
                } else {
                    console.error('Failed to send wake up signal. Status:', response.status);
                    throw new Error(`Server responded with status: ${response.status}`);
                }
            })
            .then(data => {
                console.log('Received response data:', data);

                setTimeout(() => {
                    if (audioPlayer && (audioPlayer.paused || audioPlayer.ended)) {
                        console.log('Auto-starting audio player after wake up...');
                        audioPlayer.play().catch(error => {
                            console.error('Failed to auto-start audio player:', error);
                            const streamUrlDisplay = document.getElementById('stream-url-display');
                            if (streamUrlDisplay) {
                                streamUrlDisplay.textContent = 'Wake up successful! Click play to start listening.';
                                streamUrlDisplay.style.display = 'block';
                            }
                        });
                    }
                }, 2000);
            })
            .catch(error => {
                console.error('Error sending wake up signal:', error);
            })
            .finally(() => {
                setTimeout(() => {
                    wakeUpButton.classList.remove('sending');
                    wakeUpButton.textContent = 'Wait a bit ...';
                    wakeUpButton.disabled = false;
                }, 1000);
            });
        });
    } else {
        // If the radio name is missing, hide or disable the button
        if(wakeUpButton) {
            wakeUpButton.style.display = 'none';
        }
        console.log('Wake up button disabled because no radio name was found in the URL.');
    }
});