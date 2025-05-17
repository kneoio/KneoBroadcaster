// js/console.js

document.addEventListener('DOMContentLoaded', () => {
    const consoleOutput = document.getElementById('console-output');
    const clearButton = document.getElementById('clear-console');
    const logToggleCheckbox = document.getElementById('log-to-console-toggle');

    if (!consoleOutput || !logToggleCheckbox) {
        console.error("Console output div or log toggle checkbox not found!"); // This goes to native console
        return;
    }

    const LOGGING_ENABLED_KEY = 'bratan-app-console-logging-enabled';
    let isLoggingEnabled = false;

    const originalConsole = {
        log: console.log,
        info: console.info,
        warn: console.warn,
        error: console.error,
        debug: console.debug
    };

    function loadLoggingPreference() {
        const savedState = localStorage.getItem(LOGGING_ENABLED_KEY);
        if (savedState === 'true') {
            isLoggingEnabled = true;
            logToggleCheckbox.checked = true;
        } else {
            isLoggingEnabled = false;
            logToggleCheckbox.checked = false;
        }
    }

    function saveLoggingPreference() {
        localStorage.setItem(LOGGING_ENABLED_KEY, isLoggingEnabled);
    }

    logToggleCheckbox.addEventListener('change', () => {
        isLoggingEnabled = logToggleCheckbox.checked;
        saveLoggingPreference();
        if (isLoggingEnabled) {
            originalConsole.log("Logging to app console enabled."); // Logs to native browser console
            appendMessage('info', "Logging to in-app console STARTED.", 'console-info'); // *** ADDED THIS LINE *** Logs to your app console
        } else {
            originalConsole.log("Logging to app console disabled."); // Logs to native browser console
            // Optional: You could also add an appendMessage here if you want a "stopped" message in the app console.
            // appendMessage('info', "Logging to in-app console STOPPED.", 'console-info');
        }
    });

    const formatArgs = (args) => {
        return Array.from(args).map(arg => {
            if (typeof arg === 'object' && arg !== null) {
                try {
                    if (arg && arg.event && arg.data && arg.type && arg.type.startsWith('hls')) {
                        return `[HLS Event: ${arg.type}] (details in browser console)`;
                    }
                    let seen = new Set();
                    const jsonString = JSON.stringify(arg, (key, value) => {
                        if (typeof value === 'object' && value !== null) {
                            if (seen.has(value)) {
                                return '[Circular]';
                            }
                            seen.add(value);
                        }
                        return value;
                    }, 2);
                    seen.clear(); // Clear set for next potential stringify call
                    return jsonString.length > 300 ? jsonString.substring(0, 300) + '...' : jsonString;
                } catch (e) {
                    return String(arg) + ' (Error during stringify: ' + e.message + ')';
                }
            }
            return String(arg);
        }).join(' ');
    };

    const appendMessage = (type, message, className) => {
        const line = document.createElement('div');
        line.textContent = `[${type.toUpperCase()}] ${message}`;
        line.classList.add('console-line', className);

        consoleOutput.appendChild(line);

        const isScrolledToBottom = consoleOutput.scrollHeight - consoleOutput.clientHeight <= consoleOutput.scrollTop + 5;

        if (isScrolledToBottom) {
            consoleOutput.scrollTop = consoleOutput.scrollHeight;
        }
        while (consoleOutput.children.length > 500) {
            consoleOutput.removeChild(consoleOutput.firstChild);
        }
    };

    console.log = function(...args) {
        originalConsole.log.apply(console, args);
        if (isLoggingEnabled) {
            appendMessage('log', formatArgs(args), 'console-log');
        }
    };

    console.info = function(...args) {
        originalConsole.info.apply(console, args);
        if (isLoggingEnabled) {
            appendMessage('info', formatArgs(args), 'console-info');
        }
    };

    console.warn = function(...args) {
        originalConsole.warn.apply(console, args);
        if (isLoggingEnabled) {
            appendMessage('warn', formatArgs(args), 'console-warn');
        }
    };

    console.error = function(...args) {
        originalConsole.error.apply(console, args);
        if (isLoggingEnabled) {
            appendMessage('error', formatArgs(args), 'console-error');
        }
    };

    console.debug = function(...args) {
        originalConsole.debug.apply(console, args);
        if (isLoggingEnabled) {
            appendMessage('debug', formatArgs(args), 'console-log');
        }
    };

    if (clearButton) {
        clearButton.addEventListener('click', () => {
            consoleOutput.innerHTML = '';
            console.log("App console cleared by user."); // This will now only appear in app console if logging is enabled
        });
    }

    loadLoggingPreference();

    originalConsole.log("App console initialized. Logging to app console is currently " + (isLoggingEnabled ? "ENABLED." : "DISABLED."));
    if (isLoggingEnabled) {
        // If logging was already enabled from a previous session, confirm in app console.
        appendMessage('info', "App console previously enabled. Logging is active.", 'console-info');
    }
});