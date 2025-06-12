document.addEventListener('DOMContentLoaded', () => {
    const backendStatusTextDiv = document.getElementById('backend-status-text');
    const radioPlayerTitleH1 = document.getElementById('radio-player-title');
    const playerContainer = document.querySelector('.player-container');

    const PARAMETER_NAME = 'radio';
    const STATUS_PATH_SUFFIX = '/radio/status';
    const STATUS_REFRESH_INTERVAL = 15000;
    const BRAND_NAME = '';

    if (!backendStatusTextDiv) {
        return;
    }

    const urlParams = new URLSearchParams(window.location.search);
    const dynamicRadioName = urlParams.get(PARAMETER_NAME);

    let STATUS_ENDPOINT = null;

    if (!dynamicRadioName) {
         const errorMessage = `Error: Missing URL parameter "${PARAMETER_NAME}".`;
         backendStatusTextDiv.textContent = errorMessage;
         backendStatusTextDiv.classList.add('error');
         if (radioPlayerTitleH1) {
             radioPlayerTitleH1.textContent = "Radio Not Found";
         }
    } else {
         STATUS_ENDPOINT = `${window.location.origin}/${dynamicRadioName}${STATUS_PATH_SUFFIX}`;
    }

    async function fetchBackendStatus() {
        if (!STATUS_ENDPOINT) {
            return;
        }

        backendStatusTextDiv.textContent = `Fetching ${BRAND_NAME} status...`;
        backendStatusTextDiv.classList.remove('error');

        try {
            const response = await fetch(STATUS_ENDPOINT);

            if (!response.ok) {
                const errorText = await response.text();
                const errorMessage = `Error ${response.status}: ${errorText.trim() || 'Unknown HTTP Error'}`;
                backendStatusTextDiv.textContent = `${BRAND_NAME} Status Failed: ${errorMessage}`;
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
                console.error("Error fetching status response not OK:", response.status, errorText); // Commented out
                return;
            }

            const statusData = await response.json();
            const stationName = statusData.name || "Unknown Radio";

            if (radioPlayerTitleH1) {
                radioPlayerTitleH1.textContent = stationName;
            }

            // console.log("Color received from backend:", statusData.color); // Commented out

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
                // console.warn("No valid color received from backend or playerContainer not found. Border color not applied dynamically."); // Commented out
            }

            let displayMessageParts = [];

            if (statusData.managedBy) {
                displayMessageParts.push(`Managed by: ${statusData.managedBy}`);
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
                displayMessageParts.push(`(${formattedStatus})`);
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
            const errorMessage = `Failed to fetch or parse ${BRAND_NAME} status: ${error.message}`;
            backendStatusTextDiv.textContent = `${BRAND_NAME} Status Failed: ${errorMessage}`;
            if (radioPlayerTitleH1) {
                radioPlayerTitleH1.textContent = "Radio Unavailable";
            }
            if (playerContainer) {
                playerContainer.style.border = '';
                playerContainer.style.removeProperty('--dynamic-border-color');
                playerContainer.style.removeProperty('--dynamic-border-color-alt');
                playerContainer.style.removeProperty('--dynamic-border-rgb');
            }
            console.error("Error fetching status:", error); // Commented out
        }
    }

    if (STATUS_ENDPOINT) {
        fetchBackendStatus();
        setInterval(fetchBackendStatus, STATUS_REFRESH_INTERVAL);
    } else if (backendStatusTextDiv && !dynamicRadioName) {
         backendStatusTextDiv.style.display = 'block';
    }
});