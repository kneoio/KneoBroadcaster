// js/console.js

document.addEventListener('DOMContentLoaded', () => {
    const consoleOutput = document.getElementById('console-output');
    const clearButton = document.getElementById('clear-console'); // Get the clear button

    if (!consoleOutput) {
        console.error("Console output div not found!");
        return;
    }

    const originalConsole = {
        log: console.log,
        info: console.info,
        warn: console.warn,
        error: console.error
    };

    const formatArgs = (args) => {
        return Array.from(args).map(arg => {
            if (typeof arg === 'object' && arg !== null) {
                try {

                    const jsonString = JSON.stringify(arg, null, 2);
                    return jsonString.length > 200 ? jsonString.substring(0, 200) + '...' : jsonString;
                } catch (e) {
                    return String(arg);
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

        const isScrolledToBottom = consoleOutput.scrollHeight - consoleOutput.clientHeight <= consoleOutput.scrollTop + 1; // Add a small buffer

        if (isScrolledToBottom) {
             consoleOutput.scrollTop = consoleOutput.scrollHeight;
        }
        while (consoleOutput.children.length > 500) {
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

     if (clearButton) {
        clearButton.addEventListener('click', () => {
            consoleOutput.innerHTML = '';
            originalConsole.log("Console cleared.");
        });
    }


    console.log("App console initialized.");
});