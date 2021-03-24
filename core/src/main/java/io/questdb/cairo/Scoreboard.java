/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2020 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.cairo;

import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.std.Files;
import io.questdb.std.FilesFacade;
import io.questdb.std.Transient;
import io.questdb.std.str.Path;

import java.io.Closeable;

public class Scoreboard implements Closeable {
    private static final Log LOG = LogFactory.getLog(Scoreboard.class);

    private final FilesFacade ff;
    private long fd;
    private long pScoreboard;
    private long size;
    private int readOnlyPartitionCount = 0;

    public Scoreboard(FilesFacade ff, @Transient Path path) {
        this.ff = ff;
        int plen = path.length();
        try {
            this.fd = ff.openRW(path.concat("scoreboard.d").$());
            if (fd == -1) {
                throw CairoException.instance(ff.errno()).put("Could not open scoreboard file [name=").put(path).put(']');
            }
            this.size = ff.length(fd);
            pScoreboard = ff.mmap(fd, size, 0, Files.MAP_RW);

            LOG.debug()
                    .$("open [path=").$(path)
                    .$(", fd=").$(fd)
                    .$(", size=").$(size)
                    .$(']').$();

        } catch (Throwable e) {
            close();
            throw e;
        } finally {
            path.trimTo(plen);
        }
    }

    public static native boolean addPartitionUnsafe(long pScoreboard, long timestamp, long txn);

    public static void createScoreboard(FilesFacade ff, Path path, int partitionBy) {
        // create scoreboard
        long scoreboardFd = -1;
        long memSize = 0;
        long pScoreboard = 0;
        try {
            scoreboardFd = ff.openRW(path.concat("scoreboard.d").$());
            if (scoreboardFd == -1) {
                throw CairoException.instance(ff.errno()).put("Could not open scoreboard file [name=").put(path).put(']');
            }
            memSize = getScoreboardSize(partitionBy == PartitionBy.NONE ? 1 : 0);
            if (!ff.allocate(scoreboardFd, memSize)) {
                throw CairoException.instance(ff.errno()).put("no space left on device [name=").put(path).put(", size=").put(memSize).put(']');
            }
            pScoreboard = ff.mmap(scoreboardFd, memSize, 0, Files.MAP_RW);
            if (partitionBy == PartitionBy.NONE) {
                addPartitionUnsafe(pScoreboard, 0, 0);
            }
        } finally {
            if (pScoreboard != 0) {
                ff.munmap(pScoreboard, memSize);
            }

            if (scoreboardFd != -1) {
                ff.close(scoreboardFd);
            }
        }
    }

    public static native long getHeaderAccessCounter(long pScoreboard);

    public static native int getPartitionIndex(long pScoreboard, long timestamp, long txn);

    public static native long getScoreboardSize(int partitionCount);

    public void acquireReadLock(long timestamp, long txn) {
        LOG.debug()
                .$("acquire read lock [ts=").$(timestamp)
                .$(", txn=").$(txn)
                .$(", fd=").$(fd)
                .$(']').$();
        checkAndExtend();
        acquireReadLock(pScoreboard, timestamp, txn);
    }

    public boolean acquireWriteLock(long timestamp, long txn) {
        LOG.debug()
                .$("acquire write lock [ts=").$(timestamp)
                .$(", txn=").$(txn)
                .$(", fd=").$(fd)
                .$(']').$();
        return acquireWriteLock(pScoreboard, timestamp, txn);
    }

    public boolean addPartition(long timestamp, long txn) {
        acquireHeaderLock(pScoreboard);
        try {
            long newSize = getScoreboardSize(getPartitionCount() + 1);
            resize(newSize);
            LOG.debug()
                    .$("add partition [ts=").$(timestamp)
                    .$(", txn=").$(txn)
                    .$(", fd=").$(fd)
                    .$(']').$();
            return addPartitionUnsafe(pScoreboard, timestamp, txn);
        } finally {
            releaseHeaderLock(pScoreboard);
        }
    }

    @Override
    public void close() {
        if (pScoreboard != 0) {
            ff.munmap(pScoreboard, size);
            pScoreboard = 0;
        }
        if (fd != -1) {
            ff.close(fd);
            LOG.debug().$("closed [fd=").$(fd).$(']').$();
            fd = -1;
        }
    }

    public long getAccessCounter(long timestamp, long txn) {
        return getAccessCounter(pScoreboard, timestamp, txn);
    }

    public long getActiveReaderCounter() {
        return getActiveReaderCounter(pScoreboard);
    }

    public int getPartitionCount() {
        return getPartitionCount(pScoreboard);
    }

    public int getPartitionIndex(long timestamp, long txn) {
        return getPartitionIndex(pScoreboard, timestamp, txn);
    }

    public void releaseReadLock(long timestamp, long txn) {
        LOG.debug()
                .$("release read lock [ts=").$(timestamp)
                .$(", txn=").$(txn)
                .$(", fd=").$(fd)
                .$(']').$();
        releaseReadLock(pScoreboard, timestamp, txn);
    }

    public void releaseWriteLock(long timestamp, long txn) {
        LOG.debug()
                .$("release write lock [ts=").$(timestamp)
                .$(", txn=").$(txn)
                .$(", fd=").$(fd)
                .$(']').$();
        releaseWriteLock(pScoreboard, timestamp, txn);
    }

    public boolean removePartition(long timestamp, long txn) {
        acquireHeaderLock(pScoreboard);
        try {
            LOG.debug()
                    .$("remove partition [ts=").$(timestamp)
                    .$(", txn=").$(txn)
                    .$(", fd=").$(fd)
                    .$(']').$();
            return removePartitionUnsafe(pScoreboard, timestamp, txn);
        } finally {
            releaseHeaderLock(pScoreboard);
        }
    }

    private static native boolean removePartitionUnsafe(long pScoreboard, long timestamp, long txn);

    private static native void acquireHeaderLock(long pScoreboard);

    private static native void releaseHeaderLock(long pScoreboard);

    private static native boolean acquireWriteLock(long pScoreboard, long timestamp, long txn);

    private static native void releaseWriteLock(long pScoreboard, long timestamp, long txn);

    private static native void acquireReadLock(long pScoreboard, long timestamp, long txn);

    private static native void releaseReadLock(long pScoreboard, long timestamp, long txn);

    private static native int getPartitionCount(long pScoreboard);

    private static native long getAccessCounter(long pScoreboard, long timestamp, long txn);

    private static native long getActiveReaderCounter(long pScoreboard);

    private void checkAndExtend() {
        int actualPartitionCount = getPartitionCount();
        if (readOnlyPartitionCount != actualPartitionCount) {
            long newSize = Math.max(getScoreboardSize(actualPartitionCount), ff.length(fd));
            resize(newSize);
            readOnlyPartitionCount = actualPartitionCount;
        }
    }

    private void resize(long newSize) {
        if (newSize > size) {
            resize0(2 * newSize);
        }
    }

    private void resize0(long newSize) {
        if (ff.allocate(fd, newSize)) {
            pScoreboard = ff.mremap(fd, pScoreboard, size, newSize, 0, Files.MAP_RW);
        } else {
            throw CairoException.instance(ff.errno()).put("could not resize scoreboard, no space left on device [fd=").put(fd).put(']');
        }
        size = newSize;
    }
}
