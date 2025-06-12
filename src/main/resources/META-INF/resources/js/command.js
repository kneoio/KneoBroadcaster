// js/command.js

// The global window.radioApp object is created and populated by status.js
document.addEventListener('DOMContentLoaded', () => {
    const wakeUpButton = document.getElementById('wake-up-button');

    // Check if the button and the dynamic radio name exist before proceeding
    if (wakeUpButton && window.radioApp && window.radioApp.radioName) {

        // Construct the dynamic endpoint
        const WAKEUP_ENDPOINT = `/${window.radioApp.radioName}/radio/wakeup`;

        wakeUpButton.addEventListener('click', () => {
            console.log(`Wake up button clicked. Sending PUT request to ${WAKEUP_ENDPOINT}`);

            wakeUpButton.classList.add('sending');
            wakeUpButton.textContent = 'Sending...';
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
            })
            .catch(error => {
                console.error('Error sending wake up signal:', error);
            })
            .finally(() => {
                setTimeout(() => {
                    wakeUpButton.classList.remove('sending');
                    wakeUpButton.textContent = 'WAKE UP';
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