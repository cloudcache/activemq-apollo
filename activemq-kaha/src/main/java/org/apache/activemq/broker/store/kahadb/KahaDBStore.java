/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.broker.store.kahadb;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.activemq.broker.store.Store;
import org.apache.activemq.broker.store.kahadb.Data.MapAdd;
import org.apache.activemq.broker.store.kahadb.Data.MapEntryPut;
import org.apache.activemq.broker.store.kahadb.Data.MapEntryRemove;
import org.apache.activemq.broker.store.kahadb.Data.MapRemove;
import org.apache.activemq.broker.store.kahadb.Data.MessageAdd;
import org.apache.activemq.broker.store.kahadb.Data.QueueAdd;
import org.apache.activemq.broker.store.kahadb.Data.QueueAddMessage;
import org.apache.activemq.broker.store.kahadb.Data.QueueRemove;
import org.apache.activemq.broker.store.kahadb.Data.QueueRemoveMessage;
import org.apache.activemq.broker.store.kahadb.Data.SubscriptionAdd;
import org.apache.activemq.broker.store.kahadb.Data.SubscriptionRemove;
import org.apache.activemq.broker.store.kahadb.Data.Trace;
import org.apache.activemq.broker.store.kahadb.Data.Type;
import org.apache.activemq.broker.store.kahadb.Data.MapAdd.MapAddBean;
import org.apache.activemq.broker.store.kahadb.Data.MapEntryPut.MapEntryPutBean;
import org.apache.activemq.broker.store.kahadb.Data.MapEntryRemove.MapEntryRemoveBean;
import org.apache.activemq.broker.store.kahadb.Data.MapRemove.MapRemoveBean;
import org.apache.activemq.broker.store.kahadb.Data.MessageAdd.MessageAddBean;
import org.apache.activemq.broker.store.kahadb.Data.QueueAdd.QueueAddBean;
import org.apache.activemq.broker.store.kahadb.Data.QueueAddMessage.QueueAddMessageBean;
import org.apache.activemq.broker.store.kahadb.Data.QueueRemove.QueueRemoveBean;
import org.apache.activemq.broker.store.kahadb.Data.QueueRemoveMessage.QueueRemoveMessageBean;
import org.apache.activemq.broker.store.kahadb.Data.SubscriptionAdd.SubscriptionAddBean;
import org.apache.activemq.broker.store.kahadb.Data.SubscriptionRemove.SubscriptionRemoveBean;
import org.apache.activemq.broker.store.kahadb.Data.Type.TypeCreatable;
import org.apache.activemq.protobuf.InvalidProtocolBufferException;
import org.apache.activemq.protobuf.MessageBuffer;
import org.apache.activemq.protobuf.PBMessage;
import org.apache.activemq.queue.QueueDescriptor;
import org.apache.activemq.util.LockFile;
import org.apache.activemq.util.buffer.AsciiBuffer;
import org.apache.activemq.util.buffer.Buffer;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.kahadb.journal.Journal;
import org.apache.kahadb.journal.Location;
import org.apache.kahadb.page.Page;
import org.apache.kahadb.page.PageFile;
import org.apache.kahadb.page.Transaction;
import org.apache.kahadb.util.DataByteArrayInputStream;
import org.apache.kahadb.util.DataByteArrayOutputStream;

public class KahaDBStore implements Store {

    private static final int BEGIN_UNIT_OF_WORK = -1;
    private static final int END_UNIT_OF_WORK = -2;
    private static final int FLUSH = -3;
    private static final int CANCEL_UNIT_OF_WORK = -4;

    private static final Log LOG = LogFactory.getLog(KahaDBStore.class);
    private static final int DATABASE_LOCKED_WAIT_DELAY = 10 * 1000;

    private static final Buffer BEGIN_UNIT_OF_WORK_DATA = new Buffer(new byte[] { BEGIN_UNIT_OF_WORK });
    private static final Buffer END_UNIT_OF_WORK_DATA = new Buffer(new byte[] { END_UNIT_OF_WORK });
    private static final Buffer CANCEL_UNIT_OF_WORK_DATA = new Buffer(new byte[] { CANCEL_UNIT_OF_WORK });
    private static final Buffer FLUSH_DATA = new Buffer(new byte[] { FLUSH });

    public static final int CLOSED_STATE = 1;
    public static final int OPEN_STATE = 2;

    protected PageFile pageFile;
    protected Journal journal;

    protected RootEntity rootEntity = new RootEntity();

    protected boolean failIfDatabaseIsLocked;
    protected boolean deleteAllMessages;
    protected File directory;
    protected Thread checkpointThread;
    protected boolean enableJournalDiskSyncs = true;
    long checkpointInterval = 5 * 1000;
    long cleanupInterval = 30 * 1000;
    int journalMaxFileLength = Journal.DEFAULT_MAX_FILE_LENGTH;
    boolean enableIndexWriteAsync = false;
    int setIndexWriteBatchSize = PageFile.DEFAULT_WRITE_BATCH_SIZE;

    protected AtomicBoolean started = new AtomicBoolean();
    protected AtomicBoolean opened = new AtomicBoolean();
    private LockFile lockFile;
    private Location nextRecoveryPosition;
    private Location lastRecoveryPosition;
    private AtomicLong trackingGen = new AtomicLong(0);

    protected final ReentrantReadWriteLock indexLock = new ReentrantReadWriteLock();
    private final HashSet<Integer> journalFilesBeingReplicated = new HashSet<Integer>();
    private boolean recovering;

    private static class UoWOperation {
        public TypeCreatable bean;
        public Location location;
        public Buffer data;
    }

    // /////////////////////////////////////////////////////////////////
    // Lifecylce methods
    // /////////////////////////////////////////////////////////////////
    public void start() throws Exception {
        if (started.compareAndSet(false, true)) {
            try {
                load();
            } catch (Exception e) {
                LOG.error("Error loading store", e);
            }
        }
    }

    public void stop() throws Exception {
        if (started.compareAndSet(true, false)) {
            flush();
            unload();
        }
    }

    /**
     * @return a unique sequential store tracking number.
     */
    public long allocateStoreTracking() {
        return trackingGen.incrementAndGet();
    }

    public boolean isTransactional() {
        return true;
    }

    private void loadPageFile() throws IOException {
        indexLock.writeLock().lock();
        try {
            final PageFile pageFile = getPageFile();
            pageFile.load();
            pageFile.tx().execute(new Transaction.Closure<IOException>() {
                public void execute(Transaction tx) throws IOException {
                    if (pageFile.getPageCount() == 0) {
                        rootEntity.allocate(tx);
                    } else {
                        Page<RootEntity> page = tx.load(0, RootEntity.MARSHALLER);
                        rootEntity = page.get();
                        rootEntity.setPageId(0);
                    }
                    rootEntity.load(tx);
                }
            });
            pageFile.flush();

        } finally {
            indexLock.writeLock().unlock();
        }
    }

    /**
     * @throws IOException
     */
    public void open() throws IOException {
        if (opened.compareAndSet(false, true)) {
            if (directory == null) {
                throw new IllegalArgumentException("The directory property must be set.");
            }

            File lockFileName = new File(directory, "lock");
            lockFile = new LockFile(lockFileName, true);
            if (failIfDatabaseIsLocked) {
                lockFile.lock();
            } else {
                while (true) {
                    try {
                        lockFile.lock();
                        break;
                    } catch (IOException e) {
                        LOG.info("Database " + lockFileName + " is locked... waiting " + (DATABASE_LOCKED_WAIT_DELAY / 1000) + " seconds for the database to be unlocked.");
                        try {
                            Thread.sleep(DATABASE_LOCKED_WAIT_DELAY);
                        } catch (InterruptedException e1) {
                        }
                    }
                }
            }

            if (deleteAllMessages) {
                getJournal().start();
                journal.delete();
                journal.close();
                journal = null;
                getPageFile().delete();
                rootEntity = new RootEntity();
                LOG.info("Persistence store purged.");
                deleteAllMessages = false;
            }

            getJournal().start();

            loadPageFile();

            checkpointThread = new Thread("ActiveMQ Journal Checkpoint Worker") {
                public void run() {
                    try {
                        long lastCleanup = System.currentTimeMillis();
                        long lastCheckpoint = System.currentTimeMillis();

                        // Sleep for a short time so we can periodically check
                        // to see if we need to exit this thread.
                        long sleepTime = Math.min(checkpointInterval, 500);
                        while (opened.get()) {
                            Thread.sleep(sleepTime);
                            long now = System.currentTimeMillis();
                            if (now - lastCleanup >= cleanupInterval) {
                                checkpointCleanup(true);
                                lastCleanup = now;
                                lastCheckpoint = now;
                            } else if (now - lastCheckpoint >= checkpointInterval) {
                                checkpointCleanup(false);
                                lastCheckpoint = now;
                            }
                        }
                    } catch (InterruptedException e) {
                        // Looks like someone really wants us to exit this
                        // thread...
                    }
                }
            };
            checkpointThread.start();
            recover();
            trackingGen.set(rootEntity.getLastMessageTracking() + 1);
        }
    }

    private void load() throws IOException {
        indexLock.writeLock().lock();
        try {
            open();

            store(new Trace.TraceBean().setMessage(new AsciiBuffer("LOADED " + new Date())), null);
        } finally {
            indexLock.writeLock().unlock();
        }

    }

    public void close() throws IOException, InterruptedException {
        if (opened.compareAndSet(true, false)) {

            indexLock.writeLock().lock();
            try {
                pageFile.unload();
                rootEntity = new RootEntity();
                journal.close();
            } finally {
                indexLock.writeLock().unlock();
            }

            checkpointThread.join();
            lockFile.unlock();
            lockFile = null;
        }
    }

    public void unload() throws IOException, InterruptedException {
        if (pageFile.isLoaded()) {
            indexLock.writeLock().lock();
            try {
                rootEntity.setState(CLOSED_STATE);
                pageFile.tx().execute(new Transaction.Closure<IOException>() {
                    public void execute(Transaction tx) throws IOException {
                        // Set the last update to the next update (otherwise
                        // we'll replay the last update
                        // since location marshaller doesn't marshal the
                        // location's size:
                        rootEntity.setLastUpdate(journal.getNextLocation(rootEntity.getLastUpdate()));
                        rootEntity.store(tx);
                    }
                });
            } finally {
                indexLock.writeLock().unlock();
            }
            close();
        }
    }

    // /////////////////////////////////////////////////////////////////
    // Recovery methods
    // /////////////////////////////////////////////////////////////////

    /**
     * Move all the messages that were in the journal into long term storage. We
     * just replay and do a checkpoint.
     * 
     * @throws IOException
     * @throws IOException
     * @throws InvalidLocationException
     * @throws IllegalStateException
     */
    private void recover() throws IllegalStateException, IOException {
        indexLock.writeLock().lock();
        try {
            long start = System.currentTimeMillis();
            recovering = true;
            Location recoveryPosition = getRecoveryPosition();
            if (recoveryPosition != null) {
                int redoCounter = 0;
                Transaction uow = null;
                int uowCounter = 0;
                while (recoveryPosition != null) {

                    Buffer data = journal.read(recoveryPosition);
                    if (data.length == 1 && data.data[0] == BEGIN_UNIT_OF_WORK) {
                        uow = pageFile.tx();
                    } else if (data.length == 1 && data.data[0] == END_UNIT_OF_WORK) {
                        rootEntity.setLastUpdate(recoveryPosition);
                        uow.commit();
                        redoCounter += uowCounter;
                        uowCounter = 0;
                        uow = null;
                    } else if (data.length == 1 && data.data[0] == CANCEL_UNIT_OF_WORK) {
                        uow.rollback();
                        uow = null;
                    } else if (data.length == 1 && data.data[0] == FLUSH) {
                    } else {
                        final TypeCreatable message = load(recoveryPosition);
                        final Location location = recoveryPosition;
                        if (uow != null) {
                            updateIndex(uow, message.toType(), (MessageBuffer) message, location);
                            uowCounter++;
                        } else {
                            pageFile.tx().execute(new Transaction.Closure<IOException>() {
                                public void execute(Transaction tx) throws IOException {
                                    updateIndex(tx, message.toType(), (MessageBuffer) message, location);
                                    rootEntity.setLastUpdate(location);
                                }
                            });
                            redoCounter++;
                        }
                    }

                    recoveryPosition = journal.getNextLocation(recoveryPosition);
                }
                long end = System.currentTimeMillis();
                LOG.info("Replayed " + redoCounter + " operations from the journal in " + ((end - start) / 1000.0f) + " seconds.");
            }

            // We may have to undo some index updates.
            pageFile.tx().execute(new Transaction.Closure<IOException>() {
                public void execute(Transaction tx) throws IOException {
                    recoverIndex(tx);
                }
            });
        } finally {
            recovering = false;
            indexLock.writeLock().unlock();
        }
    }

    public void incrementalRecover() throws IOException {
        indexLock.writeLock().lock();
        try {
            recovering = true;
            if (nextRecoveryPosition == null) {
                if (lastRecoveryPosition == null) {
                    nextRecoveryPosition = getRecoveryPosition();
                } else {
                    nextRecoveryPosition = journal.getNextLocation(lastRecoveryPosition);
                }
            }
            while (nextRecoveryPosition != null) {
                lastRecoveryPosition = nextRecoveryPosition;
                rootEntity.setLastUpdate(lastRecoveryPosition);
                final TypeCreatable message = load(lastRecoveryPosition);
                final Location location = lastRecoveryPosition;

                pageFile.tx().execute(new Transaction.Closure<IOException>() {
                    public void execute(Transaction tx) throws IOException {
                        updateIndex(tx, message.toType(), (MessageBuffer) message, location);
                    }
                });

                nextRecoveryPosition = journal.getNextLocation(lastRecoveryPosition);
            }
        } finally {
            recovering = false;
            indexLock.writeLock().unlock();
        }
    }

    protected void recoverIndex(Transaction tx) throws IOException {
        long start = System.currentTimeMillis();
        // It is possible index updates got applied before the journal updates..
        // in that case we need to removed references to messages that are not
        // in the journal
        final Location lastAppendLocation = journal.getLastAppendLocation();
        int undoCounter = rootEntity.recoverIndex(lastAppendLocation, tx);

        long end = System.currentTimeMillis();
        if (undoCounter > 0) {
            // The rolledback operations are basically in flight journal writes.
            // To avoid getting these the end user
            // should do sync writes to the journal.
            LOG.info("Rolled back " + undoCounter + " operations from the index in " + ((end - start) / 1000.0f) + " seconds.");
        }
    }

    private Location getRecoveryPosition() throws IOException {

        if (rootEntity.getLastUpdate() != null) {
            // Start replay at the record after the last one recorded in the
            // index file.
            return journal.getNextLocation(rootEntity.getLastUpdate());
        }

        // This loads the first position.
        return journal.getNextLocation(null);
    }

    protected void checkpointCleanup(final boolean cleanup) {
        try {
            indexLock.writeLock().lock();
            long start = System.currentTimeMillis();

            try {
                if (!opened.get()) {
                    return;
                }
                pageFile.tx().execute(new Transaction.Closure<IOException>() {
                    public void execute(Transaction tx) throws IOException {
                        checkpointUpdate(tx, cleanup);
                    }
                });
            } finally {
                indexLock.writeLock().unlock();
            }
            long end = System.currentTimeMillis();
            if (end - start > 1000) {
                if (cleanup) {
                    LOG.warn("KahaDB Cleanup took " + (end - start));
                } else {
                    LOG.warn("KahaDB CheckPoint took " + (end - start));
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void checkpoint(org.apache.activemq.util.Callback closure) throws Exception {
        indexLock.writeLock().lock();
        try {
            pageFile.tx().execute(new Transaction.Closure<IOException>() {
                public void execute(Transaction tx) throws IOException {
                    checkpointUpdate(tx, false);
                }
            });
            closure.execute();
        } finally {
            indexLock.writeLock().unlock();
        }
    }

    /**
     * @param tx
     * @throws IOException
     */
    private void checkpointUpdate(Transaction tx, boolean cleanup) throws IOException {

        if (LOG.isErrorEnabled()) {
            LOG.debug("Checkpoint started.");
        }

        rootEntity.setState(OPEN_STATE);
        // Set the last update to the next update (otherwise we'll replay the
        // last update
        // since location marshaller doesn't marshal the location's size:
        rootEntity.setLastUpdate(journal.getNextLocation(rootEntity.getLastUpdate()));
        rootEntity.store(tx);
        pageFile.flush();

        if (cleanup) {

            final TreeSet<Integer> gcCandidateSet = new TreeSet<Integer>(journal.getFileMap().keySet());

            // Don't GC files under replication
            if (journalFilesBeingReplicated != null) {
                gcCandidateSet.removeAll(journalFilesBeingReplicated);
            }

            rootEntity.removeGCCandidates(gcCandidateSet, tx);

            if (!gcCandidateSet.isEmpty()) {
                if (LOG.isErrorEnabled()) {
                    LOG.debug("Cleanup removing the data files: " + gcCandidateSet);
                }
                journal.removeDataFiles(gcCandidateSet);
            }
        }

        if (LOG.isErrorEnabled()) {
            LOG.debug("Checkpoint done.");
        }
    }

    public HashSet<Integer> getJournalFilesBeingReplicated() {
        return journalFilesBeingReplicated;
    }

    // /////////////////////////////////////////////////////////////////
    // Store interface
    // /////////////////////////////////////////////////////////////////
    long messageSequence;

    private Location store(TypeCreatable data, Transaction tx) throws IOException {
        return store(data, null, tx);
    }

    /**
     * All updated are are funneled through this method. The updates a converted
     * to a PBMessage which is logged to the journal and then the data from the
     * PBMessage is used to update the index just like it would be done during a
     * recovery process.
     * 
     * @throws IOException
     */
    @SuppressWarnings("unchecked")
    private Location store(final TypeCreatable data, Runnable onFlush, Transaction tx) throws IOException {
        final MessageBuffer message = ((PBMessage) data).freeze();
        int size = message.serializedSizeUnframed();
        DataByteArrayOutputStream os = new DataByteArrayOutputStream(size + 1);
        os.writeByte(data.toType().getNumber());
        message.writeUnframed(os);

        //If we aren't in a transaction acquire the index lock
        if (tx == null) {
            indexLock.writeLock().lock();
        }

        try {

            long start = System.currentTimeMillis();
            final Location location;
            synchronized (journal) {
                location = journal.write(os.toByteSequence(), onFlush);
            }
            long start2 = System.currentTimeMillis();

            if (tx == null) {
                pageFile.tx().execute(new Transaction.Closure<IOException>() {
                    public void execute(Transaction tx) throws IOException {
                        updateIndex(tx, data.toType(), message, location);
                    }
                });
            } else {
                updateIndex(tx, data.toType(), message, location);
            }

            long end = System.currentTimeMillis();
            if (end - start > 1000) {
                LOG.warn("KahaDB long enqueue time: Journal Add Took: " + (start2 - start) + " ms, Index Update took " + (end - start2) + " ms");
            }
            return location;

        } finally {
            if (tx == null)
                indexLock.writeLock().unlock();
        }

    }

    /**
     * Loads a previously stored PBMessage
     * 
     * @param location
     * @return
     * @throws IOException
     */
    private TypeCreatable load(Location location) throws IOException {
        Buffer data = journal.read(location);
        return load(location, data);
    }

    private TypeCreatable load(Location location, Buffer data) throws IOException, InvalidProtocolBufferException {
        DataByteArrayInputStream is = new DataByteArrayInputStream(data);
        byte readByte = is.readByte();
        Type type = Type.valueOf(readByte);
        if (type == null) {
            throw new IOException("Could not load journal record. Invalid location: " + location);
        }
        MessageBuffer message = type.parseUnframed(new Buffer(data.data, data.offset + 1, data.length - 1));
        return (TypeCreatable) message;
    }

    @SuppressWarnings("unchecked")
    public void updateIndex(Transaction tx, Type type, MessageBuffer command, Location location) throws IOException {
        // System.out.println("Updating index" + type.toString() + " loc: " +
        // location);
        switch (type) {
        case MESSAGE_ADD:
            messageAdd(tx, (MessageAdd) command, location);
            break;
        case QUEUE_ADD:
            queueAdd(tx, (QueueAdd) command, location);
            break;
        case QUEUE_REMOVE:
            queueRemove(tx, (QueueRemove) command, location);
            break;
        case QUEUE_ADD_MESSAGE:
            queueAddMessage(tx, (QueueAddMessage) command, location);
            break;
        case QUEUE_REMOVE_MESSAGE:
            queueRemoveMessage(tx, (QueueRemoveMessage) command, location);
            break;
        case SUBSCRIPTION_ADD:
            rootEntity.addSubscription(tx, (SubscriptionAdd) command);
            break;
        case SUBSCRIPTION_REMOVE:
            rootEntity.removeSubscription(tx, ((SubscriptionRemove) command).getName());
            break;
        case TRANSACTION_BEGIN:
        case TRANSACTION_ADD_MESSAGE:
        case TRANSACTION_REMOVE_MESSAGE:
        case TRANSACTION_COMMIT:
        case TRANSACTION_ROLLBACK:
        case MAP_ADD:
            rootEntity.mapAdd(((MapAdd) command).getMapName(), tx);
            break;
        case MAP_REMOVE:
            rootEntity.mapRemove(((MapRemove) command).getMapName(), tx);
            break;
        case MAP_ENTRY_PUT: {
            MapEntryPut p = (MapEntryPut) command;
            rootEntity.mapAddEntry(p.getMapName(), p.getKey(), p.getValue(), tx);
            break;
        }
        case MAP_ENTRY_REMOVE: {
            MapEntryRemove p = (MapEntryRemove) command;
            try {
                rootEntity.mapRemoveEntry(p.getMapName(), p.getKey(), tx);
            } catch (KeyNotFoundException e) {
                //yay, removed.
            }
            break;
        }
        case STREAM_OPEN:
        case STREAM_WRITE:
        case STREAM_CLOSE:
        case STREAM_REMOVE:
            throw new UnsupportedOperationException();
        }
        rootEntity.setLastUpdate(location);
    }

    private void messageAdd(Transaction tx, MessageAdd command, Location location) throws IOException {
        rootEntity.messageAdd(tx, command, location);
    }

    private void queueAdd(Transaction tx, QueueAdd command, Location location) throws IOException {
        QueueDescriptor qd = new QueueDescriptor();
        qd.setQueueName(command.getQueueName());
        qd.setApplicationType((short) command.getApplicationType());
        qd.setQueueType((short) command.getQueueType());
        if (command.hasParentName()) {
            qd.setParent(command.getParentName());
            qd.setPartitionId(command.getPartitionId());
        }

        rootEntity.queueAdd(tx, qd);
    }

    private void queueRemove(Transaction tx, QueueRemove command, Location location) throws IOException {
        QueueDescriptor qd = new QueueDescriptor();
        qd.setQueueName(command.getQueueName());
        rootEntity.queueRemove(tx, qd);
    }

    private void queueAddMessage(Transaction tx, QueueAddMessage command, Location location) throws IOException {
        QueueDescriptor qd = new QueueDescriptor();
        qd.setQueueName(command.getQueueName());
        DestinationEntity destination = rootEntity.getDestination(qd);
        if (destination != null) {
            try {
                destination.add(tx, command);
            } catch (DuplicateKeyException e) {
                if (!recovering) {
                    throw new FatalStoreException(e);
                }
            }
            rootEntity.addMessageRef(tx, command.getQueueName(), command.getMessageKey());
        }
    }

    private void queueRemoveMessage(Transaction tx, QueueRemoveMessage command, Location location) throws IOException {
        QueueDescriptor qd = new QueueDescriptor();
        qd.setQueueName(command.getQueueName());
        DestinationEntity destination = rootEntity.getDestination(qd);
        if (destination != null) {
            if (destination.remove(tx, command.getMessageKey())) {
                rootEntity.removeMessageRef(tx, command.getQueueName(), command.getMessageKey());
            }
        }
    }

    class KahaDBSession implements Session {
        TypeCreatable atomicUpdate = null;
        int updateCount = 0;

        private Transaction tx;

        private Transaction tx() {
            acquireLock();
            return tx;
        }

        public final void commit() {
            commit(null);
        }

        public final void rollback() {
            try {
                if (tx != null) {
                    if (updateCount > 1) {
                        journal.write(CANCEL_UNIT_OF_WORK_DATA, false);
                    }
                    tx.rollback();
                } else {
                    throw new IllegalStateException("Not in Transaction");
                }
            } catch (IOException e) {
                throw new FatalStoreException(e);
            } finally {
                if (tx != null) {
                    tx = null;
                    updateCount = 0;
                    atomicUpdate = null;
                }
            }
        }

        /**
         * Indicates callers intent to start a transaction.
         */
        public final void acquireLock() {
            if (tx == null) {
                indexLock.writeLock().lock();
                tx = pageFile.tx();
            }
        }

        public final void releaseLock() {
            try {
                if (tx != null) {
                    rollback();
                }
            } finally {
                indexLock.writeLock().unlock();
            }
        }

        public void commit(Runnable onFlush) {
            try {

                boolean flush = false;
                if (atomicUpdate != null) {
                    store(atomicUpdate, onFlush, tx);
                } else if (updateCount > 1) {
                    journal.write(END_UNIT_OF_WORK_DATA, onFlush);
                } else {
                    flush = onFlush != null;
                }

                if (tx != null) {
                    tx.commit();
                }

                if (flush) {
                    onFlush.run();
                }

            } catch (IOException e) {
                throw new FatalStoreException(e);
            } finally {
                tx = null;
                updateCount = 0;
                atomicUpdate = null;
            }
        }

        private void storeAtomic() {
            if (atomicUpdate != null) {
                try {
                    journal.write(BEGIN_UNIT_OF_WORK_DATA, false);
                    store(atomicUpdate, null, tx);
                    atomicUpdate = null;
                } catch (IOException ioe) {
                    throw new FatalStoreException(ioe);
                }
            }
        }

        private void addUpdate(TypeCreatable bean) {
            try {
                //As soon as we do more than one update we'll wrap in a unit of 
                //work:
                if (updateCount == 0) {
                    atomicUpdate = bean;
                    updateCount++;
                    return;
                }
                storeAtomic();

                updateCount++;
                store(bean, null, tx);

            } catch (IOException ioe) {
                throw new FatalStoreException(ioe);
            }
        }

        // /////////////////////////////////////////////////////////////
        // Message related methods.
        // /////////////////////////////////////////////////////////////

        public void messageAdd(MessageRecord message) {
            if (message.getKey() < 0) {
                throw new IllegalArgumentException("Key not set");
            }
            MessageAddBean bean = new MessageAddBean();
            bean.setMessageKey(message.getKey());
            bean.setMessageId(message.getMessageId());
            bean.setEncoding(message.getEncoding());
            bean.setMessageSize(message.getSize());
            Buffer buffer = message.getBuffer();
            if (buffer != null) {
                bean.setBuffer(buffer);
            }
            Long streamKey = message.getStreamKey();
            if (streamKey != null) {
                bean.setStreamKey(streamKey);
            }

            addUpdate(bean);
        }

        public MessageRecord messageGetRecord(Long key) throws KeyNotFoundException {
            storeAtomic();
            Location location = rootEntity.messageGetLocation(tx(), key);
            if (location == null) {
                throw new KeyNotFoundException("message key: " + key);
            }
            try {
                MessageAdd bean = (MessageAdd) load(location);
                MessageRecord rc = new MessageRecord();
                rc.setKey(bean.getMessageKey());
                rc.setMessageId(bean.getMessageId());
                rc.setEncoding(bean.getEncoding());
                rc.setSize(bean.getMessageSize());
                if (bean.hasBuffer()) {
                    rc.setBuffer(bean.getBuffer());
                }
                if (bean.hasStreamKey()) {
                    rc.setStreamKey(bean.getStreamKey());
                }
                return rc;
            } catch (IOException e) {
                throw new FatalStoreException(e);
            }
        }

        // /////////////////////////////////////////////////////////////
        // Queue related methods.
        // /////////////////////////////////////////////////////////////
        public void queueAdd(QueueDescriptor descriptor) {
            QueueAddBean update = new QueueAddBean();
            update.setQueueName(descriptor.getQueueName());
            update.setQueueType(descriptor.getQueueType());
            update.setApplicationType(descriptor.getApplicationType());
            AsciiBuffer parent = descriptor.getParent();
            if (parent != null) {
                update.setParentName(parent);
                update.setPartitionId(descriptor.getPartitionKey());
            }
            addUpdate(update);
        }

        public void queueRemove(QueueDescriptor descriptor) {
            addUpdate(new QueueRemoveBean().setQueueName(descriptor.getQueueName()));
        }

        public Iterator<QueueQueryResult> queueListByType(short type, QueueDescriptor firstQueue, int max) {
            storeAtomic();
            try {
                return rootEntity.queueList(tx(), type, firstQueue, max);
            } catch (IOException e) {
                throw new FatalStoreException(e);
            }
        }

        public Iterator<QueueQueryResult> queueList(QueueDescriptor firstQueue, int max) {
            storeAtomic();
            try {
                return rootEntity.queueList(tx(), (short) -1, firstQueue, max);
            } catch (IOException e) {
                throw new FatalStoreException(e);
            }
        }

        public void queueAddMessage(QueueDescriptor queue, QueueRecord record) throws KeyNotFoundException {
            QueueAddMessageBean bean = new QueueAddMessageBean();
            bean.setQueueName(queue.getQueueName());
            bean.setQueueKey(record.getQueueKey());
            bean.setMessageKey(record.getMessageKey());
            bean.setMessageSize(record.getSize());
            if (record.getAttachment() != null) {
                bean.setAttachment(record.getAttachment());
            }
            addUpdate(bean);
        }

        public void queueRemoveMessage(QueueDescriptor queue, Long messageKey) throws KeyNotFoundException {
            QueueRemoveMessageBean bean = new QueueRemoveMessageBean();
            bean.setMessageKey(messageKey);
            bean.setQueueName(queue.getQueueName());
            addUpdate(bean);
        }

        public Iterator<QueueRecord> queueListMessagesQueue(QueueDescriptor queue, Long firstQueueKey, Long maxQueueKey, int max) throws KeyNotFoundException {
            storeAtomic();
            DestinationEntity destination = rootEntity.getDestination(queue);
            if (destination == null) {
                throw new KeyNotFoundException("queue key: " + queue);
            }
            try {
                return destination.listMessages(tx(), firstQueueKey, maxQueueKey, max);
            } catch (IOException e) {
                throw new FatalStoreException(e);
            }
        }

        ////////////////////////////////////////////////////////////////
        //Client related methods
        ////////////////////////////////////////////////////////////////

        /**
         * Adds a subscription to the store.
         * 
         * @throws DuplicateKeyException
         *             if a subscription with the same name already exists
         * 
         */
        public void addSubscription(SubscriptionRecord record) throws DuplicateKeyException {
            storeAtomic();
            SubscriptionRecord old;
            try {
                old = rootEntity.getSubscription(tx, record.getName());
                if (old != null && !old.equals(record)) {
                    throw new DuplicateKeyException("Subscription already exists: " + record.getName());
                } else {
                    updateSubscription(record);
                }
            } catch (IOException e) {
                throw new FatalStoreException(e);
            }
        }

        /**
         * Updates a subscription in the store. If the subscription does not
         * exist then it will simply be added.
         */
        public void updateSubscription(SubscriptionRecord record) {
            SubscriptionAddBean update = new SubscriptionAddBean();
            update.setName(record.getName());
            update.setDestination(record.getDestination());
            update.setDurable(record.getIsDurable());

            if (record.getAttachment() != null) {
                update.setAttachment(record.getAttachment());
            }
            if (record.getSelector() != null) {
                update.setSelector(record.getSelector());
            }
            if (record.getTte() != -1) {
                update.setTte(record.getTte());
            }
            addUpdate(update);
        }

        /**
         * Removes a subscription with the given name from the store.
         */
        public void removeSubscription(AsciiBuffer name) {
            SubscriptionRemoveBean update = new SubscriptionRemoveBean();
            update.setName(name);
            addUpdate(update);
        }

        /**
         * @return A list of subscriptions
         */
        public Iterator<SubscriptionRecord> listSubscriptions() {
            storeAtomic();
            try {
                return rootEntity.listSubsriptions(tx);
            } catch (IOException e) {
                throw new FatalStoreException(e);
            }
        }

        // /////////////////////////////////////////////////////////////
        // Map related methods.
        // /////////////////////////////////////////////////////////////
        public void mapAdd(AsciiBuffer map) {
            MapAddBean update = new MapAddBean();
            update.setMapName(map);
            addUpdate(update);
        }

        public void mapRemove(AsciiBuffer map) {
            MapRemoveBean update = new MapRemoveBean();
            update.setMapName(map);
            addUpdate(update);
        }

        public Iterator<AsciiBuffer> mapList(AsciiBuffer first, int max) {
            storeAtomic();
            return rootEntity.mapList(first, max, tx);
        }

        public void mapEntryPut(AsciiBuffer map, AsciiBuffer key, Buffer value) {
            MapEntryPutBean update = new MapEntryPutBean();
            update.setMapName(map);
            update.setKey(key);
            update.setValue(value);
            addUpdate(update);
        }

        public Buffer mapEntryGet(AsciiBuffer map, AsciiBuffer key) throws KeyNotFoundException {
            storeAtomic();
            try {
                return rootEntity.mapGetEntry(map, key, tx);
            } catch (IOException e) {
                throw new FatalStoreException(e);
            }
        }

        public void mapEntryRemove(AsciiBuffer map, AsciiBuffer key) throws KeyNotFoundException {
            MapEntryRemoveBean update = new MapEntryRemoveBean();
            update.setMapName(map);
            update.setKey(key);
            addUpdate(update);
        }

        public Iterator<AsciiBuffer> mapEntryListKeys(AsciiBuffer map, AsciiBuffer first, int max) throws KeyNotFoundException {
            storeAtomic();
            try {
                return rootEntity.mapListKeys(map, first, max, tx);
            } catch (IOException e) {
                throw new FatalStoreException(e);
            }
        }

        // /////////////////////////////////////////////////////////////
        // Stream related methods.
        // /////////////////////////////////////////////////////////////
        public Long streamOpen() {
            return null;
        }

        public void streamWrite(Long streamKey, Buffer message) throws KeyNotFoundException {
        }

        public void streamClose(Long streamKey) throws KeyNotFoundException {
        }

        public Buffer streamRead(Long streamKey, int offset, int max) throws KeyNotFoundException {
            return null;
        }

        public boolean streamRemove(Long streamKey) {
            return false;
        }

        // /////////////////////////////////////////////////////////////
        // Transaction related methods.
        // /////////////////////////////////////////////////////////////
        public void transactionAdd(Buffer txid) {
        }

        public void transactionAddMessage(Buffer txid, Long messageKey) throws KeyNotFoundException {
        }

        public void transactionCommit(Buffer txid) throws KeyNotFoundException {
        }

        public Iterator<Buffer> transactionList(Buffer first, int max) {
            return null;
        }

        public void transactionRemoveMessage(Buffer txid, QueueDescriptor queueName, Long messageKey) throws KeyNotFoundException {
        }

        public void transactionRollback(Buffer txid) throws KeyNotFoundException {
        }
    }

    public Session getSession() {
        return new KahaDBSession();
    }

    /**
     * Convenienct method for executing a batch of work within a store
     * transaction.
     */
    public <R, T extends Exception> R execute(final Callback<R, T> callback, final Runnable onFlush) throws T {
        KahaDBSession session = new KahaDBSession();
        session.acquireLock();
        try {
            R rc = callback.execute(session);
            session.commit(onFlush);
            return rc;
        } finally {
            session.releaseLock();
        }
    }

    public void flush() {
        try {
            final CountDownLatch done = new CountDownLatch(1);
            synchronized (journal) {
                journal.write(FLUSH_DATA, new Runnable() {
                    public void run() {
                        done.countDown();
                    }
                });
            }

            // Keep trying waiting for the flush to happen unless the store
            // has been stopped.
            while (started.get()) {
                if (done.await(100, TimeUnit.MILLISECONDS)) {
                    return;
                }
            }
        } catch (Exception e) {
            throw new FatalStoreException(e);
        }
    }

    // /////////////////////////////////////////////////////////////////
    // IoC Properties.
    // /////////////////////////////////////////////////////////////////

    protected PageFile createPageFile() {
        PageFile index = new PageFile(directory, "db");
        index.setEnableWriteThread(isEnableIndexWriteAsync());
        index.setWriteBatchSize(getIndexWriteBatchSize());
        return index;
    }

    protected Journal createJournal() {
        Journal manager = new Journal();
        manager.setDirectory(directory);
        manager.setMaxFileLength(getJournalMaxFileLength());
        return manager;
    }

    private PageFile getPageFile() {
        if (pageFile == null) {
            pageFile = createPageFile();
        }
        return pageFile;
    }

    private Journal getJournal() {
        if (journal == null) {
            journal = createJournal();
        }
        return journal;
    }

    public File getDirectory() {
        return directory;
    }

    public File getStoreDirectory() {
        return directory;
    }

    public void setStoreDirectory(File directory) {
        this.directory = directory;
    }

    public boolean isDeleteAllMessages() {
        return deleteAllMessages;
    }

    public void setDeleteAllMessages(boolean deleteAllMessages) {
        this.deleteAllMessages = deleteAllMessages;
    }

    public void setIndexWriteBatchSize(int setIndexWriteBatchSize) {
        this.setIndexWriteBatchSize = setIndexWriteBatchSize;
    }

    public int getIndexWriteBatchSize() {
        return setIndexWriteBatchSize;
    }

    public void setEnableIndexWriteAsync(boolean enableIndexWriteAsync) {
        this.enableIndexWriteAsync = enableIndexWriteAsync;
    }

    boolean isEnableIndexWriteAsync() {
        return enableIndexWriteAsync;
    }

    public boolean isEnableJournalDiskSyncs() {
        return enableJournalDiskSyncs;
    }

    public void setEnableJournalDiskSyncs(boolean syncWrites) {
        this.enableJournalDiskSyncs = syncWrites;
    }

    public long getCheckpointInterval() {
        return checkpointInterval;
    }

    public void setCheckpointInterval(long checkpointInterval) {
        this.checkpointInterval = checkpointInterval;
    }

    public long getCleanupInterval() {
        return cleanupInterval;
    }

    public void setCleanupInterval(long cleanupInterval) {
        this.cleanupInterval = cleanupInterval;
    }

    public void setJournalMaxFileLength(int journalMaxFileLength) {
        this.journalMaxFileLength = journalMaxFileLength;
    }

    public int getJournalMaxFileLength() {
        return journalMaxFileLength;
    }

    public boolean isFailIfDatabaseIsLocked() {
        return failIfDatabaseIsLocked;
    }

    public void setFailIfDatabaseIsLocked(boolean failIfDatabaseIsLocked) {
        this.failIfDatabaseIsLocked = failIfDatabaseIsLocked;
    }

}
