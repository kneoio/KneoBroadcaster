// js/status.js

window.radioApp = window.radioApp || {};

document.addEventListener('DOMContentLoaded', () => {
    const backendStatusTextDiv = document.getElementById('backend-status-text');
    const radioPlayerTitleH1 = document.getElementById('radio-player-title');
    const playerContainer = document.querySelector('.player-container');
    const wakeUpContainer = document.querySelector('.wake-up-container');


    const PARAMETER_NAME = 'radio';
    const STATUS_PATH_SUFFIX = '/radio/status';
    const STATUS_REFRESH_INTERVAL = 15000;
    const BRAND_NAME = '';

    if (!backendStatusTextDiv) {
        return;
    }

    const urlParams = new URLSearchParams(window.location.search);
    const dynamicRadioName = urlParams.get(PARAMETER_NAME);

    window.radioApp.radioName = dynamicRadioName;

    let STATUS_ENDPOINT = null;

    if (!window.radioApp.radioName) {
         const errorMessage = `Error: Missing URL parameter "${PARAMETER_NAME}".`;
         backendStatusTextDiv.textContent = errorMessage;
         backendStatusTextDiv.classList.add('error');
         if (radioPlayerTitleH1) {
             radioPlayerTitleH1.textContent = "Radio Not Found";
         }
         // Show wake up button if radio name is missing
         if (wakeUpContainer) {
             wakeUpContainer.classList.remove('hidden'); // Ensure visible
         }
    } else {
         STATUS_ENDPOINT = `/${window.radioApp.radioName}${STATUS_PATH_SUFFIX}`;
         if (wakeUpContainer) {
             wakeUpContainer.classList.add('hidden'); // Assume hidden until status dictates otherwise
         }
    }

    async function fetchBackendStatus() {
        if (!STATUS_ENDPOINT) {
            return;
        }

        backendStatusTextDiv.textContent = `Fetching status...`;
        backendStatusTextDiv.classList.remove('error');

        try {
            const response = await fetch(STATUS_ENDPOINT);

            if (!response.ok) {
                const errorText = await response.text();
                const errorMessage = `Error ${response.status}: ${errorText.trim() || 'Unknown HTTP Error'}`;
                backendStatusTextDiv.textContent = `Status Failed: ${errorMessage}`;
                backendStatusTextDiv.classList.add('error');
                if (radioPlayerTitleH1) {
                    radioPlayerTitleH1.textContent = "Error Loading Radio";
                }
                if (playerContainer) {
                    playerContainer.style.border = '';
                    playerContainer.style.removeProperty('--dynamic-border-color');
                    playerContainer.style.removeProperty('--dynamic-border-color-alt');
                    playerContainer.style.removeProperty('--dynamic-border-rgb');
                }
                console.error("Error fetching status response not OK:", response.status, errorText);
                // Show wake up button on error
                if (wakeUpContainer) {
                    wakeUpContainer.classList.remove('hidden');
                }
                return;
            }

            if (response.ok) {
                if (wakeUpContainer) {
                    wakeUpContainer.classList.add('hidden');
                }
            }

            const statusData = await response.json();
            const stationName = statusData.name || "Unknown Radio";

            if (radioPlayerTitleH1) {
                radioPlayerTitleH1.textContent = stationName;
            }

            if (playerContainer && statusData.color && statusData.color.match(/^#[0-9a-fA-F]{6}$/)) {
                playerContainer.style.borderWidth = '1px';
                playerContainer.style.borderStyle = 'solid';
                playerContainer.style.setProperty('--dynamic-border-color', statusData.color);

                const hex = statusData.color.substring(1);
                const r = parseInt(hex.substring(0, 2), 16);
                const g = parseInt(hex.substring(2, 4), 16);
                const b = parseInt(hex.substring(4, 6), 16);
                playerContainer.style.setProperty('--dynamic-border-rgb', `${r}, ${g}, ${b}`);

                const lightenColor = (hexColor, percent) => {
                    let f = parseInt(hexColor.slice(1), 16),
                        t = percent < 0 ? 0 : 255,
                        p = percent < 0 ? percent * -1 : percent,
                        R = f >> 16,
                        G = (f >> 8) & 0x00ff,
                        B = f & 0x0000ff;
                    return "#" + (0x1000000 + (Math.round((t - R) * p) + R) * 0x10000 + (Math.round((t - G) * p) + G) * 0x100 + (Math.round((t - B) * p) + B))
                        .toString(16)
                        .slice(1);
                };
                playerContainer.style.setProperty('--dynamic-border-color-alt', lightenColor(statusData.color, -0.2));

            } else if (playerContainer) {
                playerContainer.style.border = '';
                playerContainer.style.removeProperty('--dynamic-border-color');
                playerContainer.style.removeProperty('--dynamic-border-color-alt');
                playerContainer.style.removeProperty('--dynamic-border-rgb');
            }

            let displayMessageParts = [];

            if (statusData.managedBy) {
                displayMessageParts.push(`Mode: ${statusData.managedBy}`);
            }

            if (statusData.countryCode) {
                displayMessageParts.push(`Country: ${statusData.countryCode}`);
            }

            if (statusData.managedBy === 'AI_AGENT' && statusData.djName) {
                let djInfo = `DJ: ${statusData.djName}`;
                if (statusData.djPreferredLang) {
                    djInfo += `(${statusData.djPreferredLang})`;
                }
                displayMessageParts.push(djInfo);
            }

            if (statusData.currentStatus) {
                const formattedStatus = statusData.currentStatus.replace(/_/g, ' ').toLowerCase();
                displayMessageParts.push(`${formattedStatus}`);
            }

            let finalDisplayMessage = displayMessageParts.join(', ');
            if (!finalDisplayMessage) {
                if (statusData.currentStatus) {
                    finalDisplayMessage = `Status: ${statusData.currentStatus.replace(/_/g, ' ').toLowerCase()}`;
                } else {
                    finalDisplayMessage = "Status information available.";
                }
            }

            backendStatusTextDiv.textContent = finalDisplayMessage;
            backendStatusTextDiv.classList.remove('error');

        } catch (error) {
            const errorMessage = `Failed to fetch or parse status: ${error.message}`;
            backendStatusTextDiv.textContent = `Status Failed: ${errorMessage}`;
            if (radioPlayerTitleH1) {
                radioPlayerTitleH1.textContent = "Radio Unavailable";
            }
            if (playerContainer) {
                playerContainer.style.border = '';
                playerContainer.style.removeProperty('--dynamic-border-color');
                playerContainer.style.removeProperty('--dynamic-border-color-alt');
                playerContainer.style.removeProperty('--dynamic-border-rgb');
            }
            console.error("Error fetching status:", error);
            if (wakeUpContainer) {
                wakeUpContainer.classList.remove('hidden');
            }
        }
    }

    if (STATUS_ENDPOINT) {
        fetchBackendStatus();
        setInterval(fetchBackendStatus, STATUS_REFRESH_INTERVAL);
    } else if (backendStatusTextDiv && !dynamicRadioName) {
         backendStatusTextDiv.style.display = 'block';
         if (wakeUpContainer) {
             wakeUpContainer.classList.remove('hidden');
         }
    }
});