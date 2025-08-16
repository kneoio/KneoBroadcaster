package io.kneo.broadcaster.util;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

public class InputStreamReadStream implements ReadStream<Buffer> {
    private static final Logger LOGGER = LoggerFactory.getLogger(InputStreamReadStream.class);
    
    private final Vertx vertx;
    private final InputStream inputStream;
    private final int bufferSize;
    private Handler<Buffer> dataHandler;
    private Handler<Void> endHandler;
    private Handler<Throwable> exceptionHandler;
    private boolean paused = false;
    private boolean ended = false;
    private long timerId = -1;

    public InputStreamReadStream(Vertx vertx, InputStream inputStream, int bufferSize) {
        this.vertx = vertx;
        this.inputStream = inputStream;
        this.bufferSize = bufferSize;
    }

    @Override
    public ReadStream<Buffer> handler(Handler<Buffer> handler) {
        this.dataHandler = handler;
        if (handler != null && !paused && !ended) {
            scheduleRead();
        }
        return this;
    }

    @Override
    public ReadStream<Buffer> pause() {
        paused = true;
        if (timerId != -1) {
            vertx.cancelTimer(timerId);
            timerId = -1;
        }
        return this;
    }

    @Override
    public ReadStream<Buffer> resume() {
        if (paused && !ended) {
            paused = false;
            scheduleRead();
        }
        return this;
    }

    @Override
    public ReadStream<Buffer> endHandler(Handler<Void> endHandler) {
        this.endHandler = endHandler;
        return this;
    }

    @Override
    public ReadStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
        this.exceptionHandler = handler;
        return this;
    }

    @Override
    public ReadStream<Buffer> fetch(long amount) {
        return resume();
    }

    private void scheduleRead() {
        if (paused || ended || timerId != -1) {
            return;
        }
        
        timerId = vertx.setTimer(1, id -> {
            timerId = -1;
            doRead();
        });
    }

    private void doRead() {
        vertx.executeBlocking(promise -> {
            try {
                byte[] buffer = new byte[bufferSize];
                int bytesRead = inputStream.read(buffer);
                
                if (bytesRead == -1) {
                    promise.complete(null); // End of stream
                } else {
                    // Create buffer with exact size to avoid copying
                    Buffer vertxBuffer = Buffer.buffer(bytesRead);
                    vertxBuffer.appendBytes(buffer, 0, bytesRead);
                    promise.complete(vertxBuffer);
                }
            } catch (IOException e) {
                promise.fail(e);
            }
        }, false, ar -> {
            if (ar.succeeded()) {
                Buffer result = (Buffer) ar.result();
                if (result == null) {
                    // End of stream
                    ended = true;
                    try {
                        inputStream.close();
                    } catch (IOException e) {
                        LOGGER.warn("Error closing input stream", e);
                    }
                    if (endHandler != null) {
                        endHandler.handle(null);
                    }
                } else {
                    // Data available
                    if (dataHandler != null) {
                        dataHandler.handle(result);
                    }
                    if (!paused && !ended) {
                        scheduleRead();
                    }
                }
            } else {
                // Error occurred
                ended = true;
                try {
                    inputStream.close();
                } catch (IOException e) {
                    LOGGER.warn("Error closing input stream after failure", e);
                }
                if (exceptionHandler != null) {
                    exceptionHandler.handle(ar.cause());
                }
            }
        });
    }
}
