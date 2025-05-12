// js/console.js

document.addEventListener('DOMContentLoaded', () => {
    const consoleOutput = document.getElementById('console-output');
    const clearButton = document.getElementById('clear-console'); // Get the clear button

    if (!consoleOutput) {
        console.error("Console output div not found!");
        return;
    }
    // Clear button is optional, so no need to return if not found,
    // but we won't add the listener if it doesn't exist.

    // Store original console methods
    const originalConsole = {
        log: console.log,
        info: console.info,
        warn: console.warn,
        error: console.error
    };

    // Function to format arguments into a string
    const formatArgs = (args) => {
        return Array.from(args).map(arg => {
            if (typeof arg === 'object' && arg !== null) {
                try {
                    // Limit stringified output length for brevity in the visual console
                    const jsonString = JSON.stringify(arg, null, 2);
                    return jsonString.length > 200 ? jsonString.substring(0, 200) + '...' : jsonString;
                } catch (e) {
                    return String(arg); // Fallback for circular or complex objects
                }
            }
            return String(arg);
        }).join(' ');
    };

    // Function to append a message to the console output div
    const appendMessage = (type, message, className) => {
        const line = document.createElement('div');
        // Use textContent to prevent HTML injection if message contains HTML-like strings
        line.textContent = `[${type.toUpperCase()}] ${message}`;
        line.classList.add('console-line', className);

        consoleOutput.appendChild(line);

        // Auto-scroll to the bottom, but only if the user hasn't scrolled up manually
        // Check if the scrollbar is near the bottom before appending
        const isScrolledToBottom = consoleOutput.scrollHeight - consoleOutput.clientHeight <= consoleOutput.scrollTop + 1; // Add a small buffer

        if (isScrolledToBottom) {
             consoleOutput.scrollTop = consoleOutput.scrollHeight;
        }
        // Optional: Limit the number of lines to prevent performance issues
        while (consoleOutput.children.length > 500) { // Keep max 500 lines
             consoleOutput.removeChild(consoleOutput.firstChild);
        }
    };

    // Override console methods
    console.log = function(...args) {
        originalConsole.log.apply(console, args);
        appendMessage('log', formatArgs(args), 'console-log');
    };

    console.info = function(...args) {
        originalConsole.info.apply(console, args);
        appendMessage('info', formatArgs(args), 'console-info');
    };

    console.warn = function(...args) {
        originalConsole.warn.apply(console, args);
        appendMessage('warn', formatArgs(args), 'console-warn');
    };

    console.error = function(...args) {
        originalConsole.error.apply(console, args);
        appendMessage('error', formatArgs(args), 'console-error');
    };

    // --- Add Clear button functionality ---
    if (clearButton) {
        clearButton.addEventListener('click', () => {
            consoleOutput.innerHTML = ''; // Clear the content of the output div
            originalConsole.log("Console cleared."); // Log to the original console as well
        });
    }
    // --- End Clear button functionality ---


    // Optional: Add a welcome message to the custom console
    console.log("App console initialized.");
});