/*
 * Copyright 2018 Mitsunori Komatsu (komamitsu)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.komamitsu.fluency.buffer;

import org.komamitsu.fluency.BufferFullException;
import org.komamitsu.fluency.EventTime;
import org.komamitsu.fluency.ingester.Ingester;
import org.komamitsu.fluency.recordformat.RecordFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

public class Buffer
{
    private static final Logger LOG = LoggerFactory.getLogger(Buffer.class);
    private final FileBackup fileBackup;
    private final RecordFormatter recordFormatter;
    private final Config config;

    private final Map<String, RetentionBuffer> retentionBuffers = new HashMap<>();
    private final LinkedBlockingQueue<TaggableBuffer> flushableBuffers = new LinkedBlockingQueue<>();
    private final Queue<TaggableBuffer> backupBuffers = new ConcurrentLinkedQueue<>();
    private final BufferPool bufferPool;

    protected Buffer(final Config config, RecordFormatter recordFormatter)
    {
        this.config = config;

        if (config.getFileBackupDir() != null) {
            fileBackup = new FileBackup(new File(config.getFileBackupDir()), this, config.getFileBackupPrefix());
        }
        else {
            fileBackup = null;
        }

        this.recordFormatter = recordFormatter;

        if (config.getChunkInitialSize() > config.getChunkRetentionSize()) {
            LOG.warn("Initial Buffer Chunk Size ({}) shouldn't be more than Buffer Chunk Retention Size ({}) for better performance.",
                    config.getChunkInitialSize(), config.getChunkRetentionSize());
        }
        bufferPool = new BufferPool(
                config.getChunkInitialSize(), config.getMaxBufferSize(), config.jvmHeapBufferMode);
    }

    protected void init()
    {
        if (fileBackup != null) {
            for (FileBackup.SavedBuffer savedBuffer : fileBackup.getSavedFiles()) {
                savedBuffer.open((params, channel) -> {
                    try {
                        LOG.info("Loading buffer: params={}, buffer.size={}", params, channel.size());
                    }
                    catch (IOException e) {
                        LOG.error("Failed to access the backup file: params={}", params, e);
                    }
                    loadBufferFromFile(params, channel);
                });
            }
        }
    }

    protected void saveBuffer(List<String> params, ByteBuffer buffer)
    {
        if (fileBackup == null) {
            return;
        }
        LOG.info("Saving buffer: params={}, buffer={}", params, buffer);
        fileBackup.saveBuffer(params, buffer);
    }

    public void flush(Ingester ingester, boolean force)
            throws IOException
    {
        LOG.trace("flush(): force={}, bufferUsage={}", force, getBufferUsage());
        flushInternal(ingester, force);
    }

    public void close()
    {
        try {
            LOG.debug("Saving all buffers");
            saveAllBuffersToFile();
        }
        catch (Exception e) {
            LOG.warn("Failed to save all buffers", e);
        }
        LOG.debug("Closing buffers");
        closeInternal();
    }

    private long getMaxSize()
    {
        return config.getMaxBufferSize();
    }

    public float getBufferUsage()
    {
        return (float) getAllocatedSize() / getMaxSize();
    }

    public void clearBackupFiles()
    {
        if (fileBackup != null) {
            for (FileBackup.SavedBuffer buffer : fileBackup.getSavedFiles()) {
                buffer.remove();
            }
        }
    }

    public long getMaxBufferSize()
    {
        return config.getMaxBufferSize();
    }

    public String getFileBackupPrefix()
    {
        return config.getFileBackupPrefix();
    }

    public String getFileBackupDir()
    {
        return config.getFileBackupDir();
    }

    private RetentionBuffer prepareBuffer(String tag, int writeSize)
            throws BufferFullException
    {
        RetentionBuffer retentionBuffer = retentionBuffers.get(tag);
        if (retentionBuffer != null && retentionBuffer.getByteBuffer().remaining() > writeSize) {
            return retentionBuffer;
        }

        int existingDataSize = 0;
        int newBufferChunkRetentionSize;
        if (retentionBuffer == null) {
            newBufferChunkRetentionSize = config.getChunkInitialSize();
        }
        else{
            existingDataSize = retentionBuffer.getByteBuffer().position();
            newBufferChunkRetentionSize = (int) (retentionBuffer.getByteBuffer().capacity() * config.getChunkExpandRatio());
        }

        while (newBufferChunkRetentionSize < (writeSize + existingDataSize)) {
            newBufferChunkRetentionSize *= config.getChunkExpandRatio();
        }

        ByteBuffer acquiredBuffer = bufferPool.acquireBuffer(newBufferChunkRetentionSize);
        if (acquiredBuffer == null) {
            throw new BufferFullException("Buffer is full. config=" + config + ", bufferPool=" + bufferPool);
        }

        RetentionBuffer newBuffer = new RetentionBuffer(acquiredBuffer);
        if (retentionBuffer != null) {
            retentionBuffer.getByteBuffer().flip();
            newBuffer.getByteBuffer().put(retentionBuffer.getByteBuffer());
            newBuffer.getCreatedTimeMillis().set(System.currentTimeMillis());
            bufferPool.returnBuffer(retentionBuffer.getByteBuffer());
        }
        LOG.trace("prepareBuffer(): allocate a new buffer. tag={}, buffer={}", tag, newBuffer);

        retentionBuffers.put(tag, newBuffer);
        return newBuffer;
    }

    private void loadDataToRetentionBuffers(String tag, ByteBuffer src)
            throws IOException
    {
        synchronized (retentionBuffers) {
            RetentionBuffer buffer = prepareBuffer(tag, src.remaining());
            buffer.getByteBuffer().put(src);
            moveRetentionBufferIfNeeded(tag, buffer);
        }
    }

    protected void loadBufferFromFile(List<String> params, FileChannel channel)
    {
        if (params.size() != 1) {
            throw new IllegalArgumentException("The number of params should be 1: params=" + params);
        }
        String tag = params.get(0);

        try {
            MappedByteBuffer src = channel.map(FileChannel.MapMode.PRIVATE, 0, channel.size());
            loadDataToRetentionBuffers(tag, src);
        }
        catch (Exception e) {
            LOG.error("Failed to load data to flushableBuffers: params={}, channel={}", params, channel);
        }
    }

    private void saveBuffer(TaggableBuffer buffer)
    {
        saveBuffer(Collections.singletonList(buffer.getTag()), buffer.getByteBuffer());
    }

    protected void saveAllBuffersToFile()
            throws IOException
    {
        moveRetentionBuffersToFlushable(true);  // Just in case

        TaggableBuffer flushableBuffer;
        while ((flushableBuffer = flushableBuffers.poll()) != null) {
            saveBuffer(flushableBuffer);
        }
        while ((flushableBuffer = backupBuffers.poll()) != null) {
            saveBuffer(flushableBuffer);
        }
    }

    private void appendMapInternal(String tag, Object timestamp, Map<String, Object> data)
            throws IOException
    {
        loadDataToRetentionBuffers(tag,
                ByteBuffer.wrap(recordFormatter.format(tag, timestamp, data)));
    }

    private void appendMessagePackMapValueInternal(String tag, Object timestamp, byte[] mapValue, int offset, int len)
            throws IOException
    {
        loadDataToRetentionBuffers(tag,
                ByteBuffer.wrap(recordFormatter.formatFromMessagePack(tag, timestamp, mapValue, offset, len)));
    }

    private void appendMessagePackMapValueInternal(String tag, Object timestamp, ByteBuffer mapValue)
            throws IOException
    {
        loadDataToRetentionBuffers(tag,
                ByteBuffer.wrap(recordFormatter.formatFromMessagePack(tag, timestamp, mapValue)));
    }

    public void append(String tag, long timestamp, Map<String, Object> data)
            throws IOException
    {
        appendMapInternal(tag, timestamp, data);
    }

    public void append(String tag, EventTime timestamp, Map<String, Object> data)
            throws IOException
    {
        appendMapInternal(tag, timestamp, data);
    }

    public void appendMessagePackMapValue(String tag, long timestamp, byte[] mapValue, int offset, int len)
            throws IOException
    {
        appendMessagePackMapValueInternal(tag, timestamp, mapValue, offset, len);
    }

    public void appendMessagePackMapValue(String tag, EventTime timestamp, byte[] mapValue, int offset, int len)
            throws IOException
    {
        appendMessagePackMapValueInternal(tag, timestamp, mapValue, offset, len);
    }

    public void appendMessagePackMapValue(String tag, long timestamp, ByteBuffer mapValue)
            throws IOException
    {
        appendMessagePackMapValueInternal(tag, timestamp, mapValue);
    }

    public void appendMessagePackMapValue(String tag, EventTime timestamp, ByteBuffer mapValue)
            throws IOException
    {
        appendMessagePackMapValueInternal(tag, timestamp, mapValue);

    }

    private void moveRetentionBufferIfNeeded(String tag, RetentionBuffer buffer)
            throws IOException
    {
        if (buffer.getByteBuffer().position() > config.getChunkRetentionSize()) {
            moveRetentionBufferToFlushable(tag, buffer);
        }
    }

    private void moveRetentionBuffersToFlushable(boolean force)
            throws IOException
    {
        long expiredThreshold = System.currentTimeMillis() - config.getChunkRetentionTimeMillis();

        synchronized (retentionBuffers) {
            for (Map.Entry<String, RetentionBuffer> entry : retentionBuffers.entrySet()) {
                // it can be null because moveRetentionBufferToFlushable() can set null
                if (entry.getValue() != null) {
                    if (force || entry.getValue().getCreatedTimeMillis().get() < expiredThreshold) {
                        moveRetentionBufferToFlushable(entry.getKey(), entry.getValue());
                    }
                }
            }
        }
    }

    private void moveRetentionBufferToFlushable(String tag, RetentionBuffer buffer)
            throws IOException
    {
        try {
            LOG.trace("moveRetentionBufferToFlushable(): tag={}, buffer={}", tag, buffer);
            buffer.getByteBuffer().flip();
            flushableBuffers.put(new TaggableBuffer(tag, buffer.getByteBuffer()));
            retentionBuffers.put(tag, null);
        }
        catch (InterruptedException e) {
            throw new IOException("Failed to move retention buffer due to interruption", e);
        }
    }

    public void flushInternal(Ingester ingester, boolean force)
            throws IOException
    {
        moveRetentionBuffersToFlushable(force);

        TaggableBuffer flushableBuffer;
        while (!Thread.currentThread().isInterrupted() &&
                (flushableBuffer = flushableBuffers.poll()) != null) {
            boolean keepBuffer = false;
            try {
                LOG.trace("flushInternal(): bufferUsage={}, flushableBuffer={}", getBufferUsage(), flushableBuffer);
                String tag = flushableBuffer.getTag();
                ByteBuffer dataBuffer = flushableBuffer.getByteBuffer();
                ingester.ingest(tag, dataBuffer);
            }
            catch (IOException e) {
                LOG.warn("Failed to send data. The data is going to be saved into the buffer again: data={}", flushableBuffer);
                keepBuffer = true;
                throw e;
            }
            finally {
                if (keepBuffer) {
                    try {
                        flushableBuffers.put(flushableBuffer);
                    }
                    catch (InterruptedException e1) {
                        LOG.warn("Failed to save the data into the buffer. Trying to save it in extra buffer: chunk={}", flushableBuffer);
                        backupBuffers.add(flushableBuffer);
                    }
                }
                else {
                    bufferPool.returnBuffer(flushableBuffer.getByteBuffer());
                }
            }
        }
    }

    protected synchronized void closeInternal()
    {
        retentionBuffers.clear();
        bufferPool.releaseBuffers();
    }

    public long getAllocatedSize()
    {
        return bufferPool.getAllocatedSize();
    }

    public long getBufferedDataSize()
    {
        long size = 0;
        synchronized (retentionBuffers) {
            for (Map.Entry<String, RetentionBuffer> buffer : retentionBuffers.entrySet()) {
                if (buffer.getValue() != null && buffer.getValue().getByteBuffer() != null) {
                    size += buffer.getValue().getByteBuffer().position();
                }
            }
        }
        for (TaggableBuffer buffer : flushableBuffers) {
            if (buffer.getByteBuffer() != null) {
                size += buffer.getByteBuffer().remaining();
            }
        }
        return size;
    }

    public boolean getJvmHeapBufferMode()
    {
        return bufferPool.getJvmHeapBufferMode();
    }

    public String bufferFormatType()
    {
        // To keep backward compatibility
        return "packed_forward";
    }

    private static class RetentionBuffer
    {
        private final AtomicLong createdTimeMillis = new AtomicLong();
        private final ByteBuffer byteBuffer;

        RetentionBuffer(ByteBuffer byteBuffer)
        {
            this.byteBuffer = byteBuffer;
        }

        AtomicLong getCreatedTimeMillis()
        {
            return createdTimeMillis;
        }

        ByteBuffer getByteBuffer()
        {
            return byteBuffer;
        }

        @Override
        public String toString()
        {
            return "RetentionBuffer{" +
                    "createdTimeMillis=" + createdTimeMillis+
                    ", byteBuffer=" + byteBuffer +
                    '}';
        }
    }

    private static class TaggableBuffer
    {
        private final String tag;
        private final ByteBuffer byteBuffer;

        public TaggableBuffer(String tag, ByteBuffer byteBuffer)
        {
            this.tag = tag;
            this.byteBuffer = byteBuffer;
        }

        public String getTag()
        {
            return tag;
        }

        public ByteBuffer getByteBuffer()
        {
            return byteBuffer;
        }

        @Override
        public String toString()
        {
            return "TaggableBuffer{" +
                    "tag='" + tag + '\'' +
                    ", byteBuffer=" + byteBuffer +
                    '}';
        }
    }

    public int getChunkInitialSize()
    {
        return config.getChunkInitialSize();
    }

    public float getChunkExpandRatio()
    {
        return config.getChunkExpandRatio();
    }

    public int getChunkRetentionSize()
    {
        return config.getChunkRetentionSize();
    }

    public int getChunkRetentionTimeMillis()
    {
        return config.getChunkRetentionTimeMillis();
    }

    @Override
    public String toString()
    {
        return "PackedForwardBuffer{" +
                "retentionBuffers=" + retentionBuffers +
                ", flushableBuffers=" + flushableBuffers +
                ", backupBuffers=" + backupBuffers +
                ", bufferPool=" + bufferPool +
                ", config=" + config +
                "} " + super.toString();
    }

    public static class Config
        implements Instantiator
    {
        private long maxBufferSize = 512 * 1024 * 1024;
        private String fileBackupDir;
        private String fileBackupPrefix;  // Mainly for testing

        private int chunkInitialSize = 1024 * 1024;
        private float chunkExpandRatio = 2.0f;
        private int chunkRetentionSize = 4 * 1024 * 1024;
        private int chunkRetentionTimeMillis = 1000;
        private boolean jvmHeapBufferMode = false;

        public long getMaxBufferSize()
        {
            return maxBufferSize;
        }

        public Config setMaxBufferSize(long maxBufferSize)
        {
            this.maxBufferSize = maxBufferSize;
            return this;
        }

        public String getFileBackupDir()
        {
            return fileBackupDir;
        }

        public Config setFileBackupDir(String fileBackupDir)
        {
            this.fileBackupDir = fileBackupDir;
            return this;
        }

        public String getFileBackupPrefix()
        {
            return fileBackupPrefix;
        }

        public Config setFileBackupPrefix(String fileBackupPrefix)
        {
            this.fileBackupPrefix = fileBackupPrefix;
            return this;
        }


        public int getChunkInitialSize()
        {
            return chunkInitialSize;
        }

        public Config setChunkInitialSize(int chunkInitialSize)
        {
            this.chunkInitialSize = chunkInitialSize;
            return this;
        }

        public float getChunkExpandRatio()
        {
            return chunkExpandRatio;
        }

        public Config setChunkExpandRatio(float chunkExpandRatio)
        {
            this.chunkExpandRatio = chunkExpandRatio;
            return this;
        }

        public int getChunkRetentionSize()
        {
            return chunkRetentionSize;
        }

        public Config setChunkRetentionSize(int chunkRetentionSize)
        {
            this.chunkRetentionSize = chunkRetentionSize;
            return this;
        }

        public int getChunkRetentionTimeMillis()
        {
            return chunkRetentionTimeMillis;
        }

        public Config setChunkRetentionTimeMillis(int chunkRetentionTimeMillis)
        {
            this.chunkRetentionTimeMillis = chunkRetentionTimeMillis;
            return this;
        }

        public boolean getJvmHeapBufferMode()
        {
            return jvmHeapBufferMode;
        }

        public Config setJvmHeapBufferMode(boolean jvmHeapBufferMode)
        {
            this.jvmHeapBufferMode = jvmHeapBufferMode;
            return this;
        }

        @Override
        public String toString()
        {
            return "Config{" +
                    "maxBufferSize=" + maxBufferSize +
                    ", fileBackupDir='" + fileBackupDir + '\'' +
                    ", fileBackupPrefix='" + fileBackupPrefix + '\'' +
                    ", chunkInitialSize=" + chunkInitialSize +
                    ", chunkExpandRatio=" + chunkExpandRatio +
                    ", chunkRetentionSize=" + chunkRetentionSize +
                    ", chunkRetentionTimeMillis=" + chunkRetentionTimeMillis +
                    ", jvmHeapBufferMode=" + jvmHeapBufferMode +
                    '}';
        }

        @Override
        public Buffer createInstance(RecordFormatter recordFormatter)
        {
            Buffer buffer = new Buffer(this, recordFormatter);
            buffer.init();
            return buffer;
        }
    }

    public interface Instantiator
    {
        Buffer createInstance(RecordFormatter recordFormatter);
    }
}
