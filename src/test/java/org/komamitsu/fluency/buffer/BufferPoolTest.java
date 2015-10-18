package org.komamitsu.fluency.buffer;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class BufferPoolTest
{
    private static final Logger LOG = LoggerFactory.getLogger(BufferPoolTest.class);

    @Test
    public void testAcquireAndRelease()
            throws IOException
    {
        BufferPool bufferPool = new BufferPool(8 * 1024, 256 * 1024);
        ByteBuffer buffer0 = bufferPool.acquireBuffer(100 * 1024);
        assertEquals(128 * 1024, buffer0.capacity());
        assertEquals(0, buffer0.position());
        assertEquals(128 * 1024, bufferPool.getAllocatedSize());

        ByteBuffer buffer1 = bufferPool.acquireBuffer(40 * 1024);
        assertEquals(64 * 1024, buffer1.capacity());
        assertEquals(0, buffer1.position());
        assertEquals(192 * 1024, bufferPool.getAllocatedSize());

        bufferPool.returnBuffer(buffer0);
        assertEquals(192 * 1024, bufferPool.getAllocatedSize());

        ByteBuffer buffer2 = bufferPool.acquireBuffer(20 * 1024);
        assertEquals(32 * 1024, buffer2.capacity());
        assertEquals(0, buffer2.position());
        assertEquals(224 * 1024, bufferPool.getAllocatedSize());

        bufferPool.returnBuffer(buffer2);
        assertEquals(224 * 1024, bufferPool.getAllocatedSize());

        bufferPool.returnBuffer(buffer1);
        assertEquals(224 * 1024, bufferPool.getAllocatedSize());

        long totalBufferSize = getActualTotalBufferSize(bufferPool);
        assertEquals(224 * 1024, totalBufferSize);

        bufferPool.releaseBuffers(0.5f);
        assertEquals(96 * 1024, bufferPool.getAllocatedSize());
        totalBufferSize = getActualTotalBufferSize(bufferPool);
        assertEquals(96 * 1024, totalBufferSize);

        bufferPool.releaseBuffers(0);
        assertEquals(0, bufferPool.getAllocatedSize());
        totalBufferSize = getActualTotalBufferSize(bufferPool);
        assertEquals(0, totalBufferSize);
    }

    @Test
    public void testAcquireWithBufferFull()
    {
        BufferPool bufferPool = new BufferPool(8 * 1024, 256 * 1024);
        ByteBuffer buffer = bufferPool.acquireBuffer(64 * 1024);
        assertEquals(64 * 1024, buffer.capacity());
        assertEquals(0, buffer.position());
        assertEquals(64 * 1024, bufferPool.getAllocatedSize());

        buffer = bufferPool.acquireBuffer(64 * 1024);
        assertEquals(64 * 1024, buffer.capacity());
        assertEquals(0, buffer.position());
        assertEquals(128 * 1024, bufferPool.getAllocatedSize());

        buffer = bufferPool.acquireBuffer(64 * 1024);
        assertEquals(64 * 1024, buffer.capacity());
        assertEquals(0, buffer.position());
        assertEquals(192 * 1024, bufferPool.getAllocatedSize());

        buffer = bufferPool.acquireBuffer(64 * 1024);
        assertEquals(64 * 1024, buffer.capacity());
        assertEquals(0, buffer.position());
        assertEquals(256 * 1024, bufferPool.getAllocatedSize());

        buffer = bufferPool.acquireBuffer(64 * 1024);
        assertNull(buffer);
    }

    @Test
    public void testAcquireAndReleaseWithMultiThread()
            throws InterruptedException
    {
        final int concurrency = 8;
        final BufferPool bufferPool = new BufferPool(1024, 512 * 1024);
        final CountDownLatch countDownLatch = new CountDownLatch(concurrency);
        Runnable task = new Runnable()
        {
            @Override
            public void run()
            {
                LinkedList<ByteBuffer> buffers = new LinkedList<ByteBuffer>();
                for (int i = 0; i < 1000; i++) {
                    if ((i / 10) % 2 == 0) {
                        int size = ((i % 2) + 1) * 1024;
                        ByteBuffer buffer = bufferPool.acquireBuffer(size);
                        assertEquals(size, buffer.capacity());
                        assertEquals(0, buffer.position());
                        buffers.add(buffer);
                    }
                    else {
                        ByteBuffer buffer = buffers.pollFirst();
                        assertNotNull(buffer);
                        bufferPool.returnBuffer(buffer);
                    }
                }
                assertEquals(0, buffers.size());
                countDownLatch.countDown();
            }
        };
        ExecutorService executorService = Executors.newCachedThreadPool();
        for (int i = 0; i < concurrency; i++) {
            executorService.execute(task);
        }
        assertTrue(countDownLatch.await(10, TimeUnit.SECONDS));

        long totalBufferSize = getActualTotalBufferSize(bufferPool);
        LOG.debug("bufferPool.getAllocatedSize() = {}, actual buffered total size={}", bufferPool.getAllocatedSize(), totalBufferSize);
        assertEquals(bufferPool.getAllocatedSize(), totalBufferSize);
    }

    private long getActualTotalBufferSize(BufferPool bufferPool)
    {
        long totalBufferedSize = 0;
        for (Map.Entry<Integer, LinkedBlockingQueue<ByteBuffer>> entry : bufferPool.bufferPool.entrySet()) {
            for (ByteBuffer buffer : entry.getValue()) {
                totalBufferedSize += buffer.capacity();
            }
        }
        return totalBufferedSize;
    }
}