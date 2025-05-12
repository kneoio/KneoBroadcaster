// js/status.js

document.addEventListener('DOMContentLoaded', () => {
    const backendStatusTextDiv = document.getElementById('backend-status-text');

    const PARAMETER_NAME = 'radio';
    const STATUS_PATH_SUFFIX = '/radio/status';
    const STATUS_REFRESH_INTERVAL = 15000;

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
    } else {
         STATUS_ENDPOINT = `${window.location.origin}/${dynamicRadioName}${STATUS_PATH_SUFFIX}`;
    }

    async function fetchBackendStatus() {
        if (!STATUS_ENDPOINT) {
            return;
        }

        backendStatusTextDiv.textContent = `Workspaceing status...`;
        backendStatusTextDiv.classList.remove('error');

        try {
            const response = await fetch(STATUS_ENDPOINT);
            const statusText = await response.text();

            if (!response.ok) {
                const errorMessage = `Error ${response.status}: ${statusText.trim() || 'Unknown HTTP Error'}`;
                backendStatusTextDiv.textContent = errorMessage;
                backendStatusTextDiv.classList.add('error');
            } else {
                backendStatusTextDiv.textContent = statusText;
                backendStatusTextDiv.classList.remove('error');
            }
        } catch (error) {
            const errorMessage = `Workspace Failed: ${error.message}`;
            backendStatusTextDiv.textContent = errorMessage;
            backendStatusTextDiv.classList.add('error');
        }
    }

    if (STATUS_ENDPOINT) {
        fetchBackendStatus();
        setInterval(fetchBackendStatus, STATUS_REFRESH_INTERVAL);
    } else if (backendStatusTextDiv && !dynamicRadioName) {
         backendStatusTextDiv.style.display = 'block';
    }
});