/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.util.indexing;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.*;
import com.intellij.openapi.util.io.BufferExposingByteArrayOutputStream;
import com.intellij.openapi.util.io.ByteSequence;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.cache.impl.id.IdIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.*;
import com.intellij.util.io.*;
import com.intellij.util.io.DataOutputStream;
import gnu.trove.THashMap;
import gnu.trove.TIntObjectHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * @author Eugene Zhuravlev
 *         Date: Dec 10, 2007
 */
public class MapReduceIndex<Key, Value, Input> implements UpdatableIndex<Key,Value, Input> {
  private static final Logger LOG = Logger.getInstance("#com.intellij.util.indexing.MapReduceIndex");
  private static final int NULL_MAPPING = 0;
  @Nullable private final ID<Key, Value> myIndexId;
  private final DataIndexer<Key, Value, Input> myIndexer;
  @NotNull protected final IndexStorage<Key, Value> myStorage;
  private final boolean myHasSnapshotMapping;

  private final DataExternalizer<Value> myValueExternalizer;
  private final DataExternalizer<Collection<Key>> mySnapshotIndexExternalizer;
  private final boolean myIsPsiBackedIndex;
  private final IndexExtension<Key, Value, Input> myExtension;
  private final AtomicBoolean myIsBufferingMode = new AtomicBoolean();
  private final TIntObjectHashMap<Collection<Key>> myInMemoryKeys = new TIntObjectHashMap<Collection<Key>>();

  private PersistentHashMap<Integer, ByteSequence> myContents;
  private PersistentHashMap<Integer, Integer> myInputsSnapshotMapping;
  private PersistentHashMap<Integer, Collection<Key>> myInputsIndex;
  private PersistentHashMap<Integer, String> myIndexingTrace;

  private final ReentrantReadWriteLock myLock = new ReentrantReadWriteLock();

  private final LowMemoryWatcher myLowMemoryFlusher = LowMemoryWatcher.register(new Runnable() {
    @Override
    public void run() {
      try {
        flush();
      } catch (StorageException e) {
        LOG.info(e);
        FileBasedIndex.getInstance().requestRebuild(myIndexId);
      }
    }
  });

  public MapReduceIndex(IndexExtension<Key, Value, Input> extension,
                        @NotNull IndexStorage<Key, Value> storage) throws IOException {
    myIndexId = extension.getName();
    myExtension = extension;
    myIndexer = extension.getIndexer();
    myStorage = storage;
    myHasSnapshotMapping = extension instanceof FileBasedIndexExtension &&
                           ((FileBasedIndexExtension<Key, Value>)extension).hasSnapshotMapping() &&
                           IdIndex.ourSnapshotMappingsEnabled;

    mySnapshotIndexExternalizer = createInputsIndexExternalizer(extension, myIndexId, extension.getKeyDescriptor());
    myValueExternalizer = extension.getValueExternalizer();
    myContents = createContentsIndex();
    myIsPsiBackedIndex = extension instanceof PsiDependentIndex;

    if (myHasSnapshotMapping) {
      myInputsSnapshotMapping = createInputSnapshotMapping();
    }
    myInputsIndex = /*myHasSnapshotMapping ? null :*/ createInputsIndex();
    if (DebugAssertions.EXTRA_SANITY_CHECKS && myHasSnapshotMapping && myIndexId != null) {
      myIndexingTrace = createIndexingTrace();
    }

    if (storage instanceof MemoryIndexStorage) {
      ((MemoryIndexStorage)storage).addBufferingStateListener(new MemoryIndexStorage.BufferingStateListener() {
        @Override
        public void bufferingStateChanged(boolean newState) {
          myIsBufferingMode.set(newState);
        }

        @Override
        public void memoryStorageCleared() {
          synchronized (myInMemoryKeys) {
            myInMemoryKeys.clear();
          }
        }
      });
    }
  }

  private static <K> DataExternalizer<Collection<K>> createInputsIndexExternalizer(IndexExtension<K, ?, ?> extension,
                                                                           ID<K, ?> indexId,
                                                                           KeyDescriptor<K> keyDescriptor) {
    DataExternalizer<Collection<K>> externalizer;
    if (extension instanceof CustomInputsIndexFileBasedIndexExtension) {
      externalizer = ((CustomInputsIndexFileBasedIndexExtension<K>)extension).createExternalizer();
    } else {
      externalizer = new InputIndexDataExternalizer<K>(keyDescriptor, indexId);
    }
    return externalizer;
  }

  @NotNull
  private static <K> PersistentHashMap<Integer, Collection<K>> createIdToDataKeysIndex(@NotNull IndexExtension <K, ?, ?> extension,
                                                                                      @NotNull MemoryIndexStorage<K, ?> storage)
    throws IOException {
    ID<K, ?> indexId = extension.getName();
    KeyDescriptor<K> keyDescriptor = extension.getKeyDescriptor();
    final File indexStorageFile = IndexInfrastructure.getInputIndexStorageFile(indexId);
    final AtomicBoolean isBufferingMode = new AtomicBoolean();
    final TIntObjectHashMap<Collection<K>> tempMap = new TIntObjectHashMap<Collection<K>>();

    // Important! Update IdToDataKeysIndex depending on the sate of "buffering" flag from the MemoryStorage.
    // If buffering is on, all changes should be done in memory (similar to the way it is done in memory storage).
    // Otherwise data in IdToDataKeysIndex will not be in sync with the 'main' data in the index on disk and index updates will be based on the
    // wrong sets of keys for the given file. This will lead to unpredictable results in main index because it will not be
    // cleared properly before updating (removed data will still be present on disk). See IDEA-52223 for illustration of possible effects.

    final PersistentHashMap<Integer, Collection<K>> map = new PersistentHashMap<Integer, Collection<K>>(
      indexStorageFile, EnumeratorIntegerDescriptor.INSTANCE, createInputsIndexExternalizer(extension, indexId, keyDescriptor)
    ) {

      @Override
      protected Collection<K> doGet(Integer integer) throws IOException {
        if (isBufferingMode.get()) {
          final Collection<K> collection = tempMap.get(integer);
          if (collection != null) {
            return collection;
          }
        }
        return super.doGet(integer);
      }

      @Override
      protected void doPut(Integer integer, @Nullable Collection<K> ks) throws IOException {
        if (isBufferingMode.get()) {
          tempMap.put(integer, ks == null ? Collections.<K>emptySet() : ks);
        }
        else {
          super.doPut(integer, ks);
        }
      }

      @Override
      protected void doRemove(Integer integer) throws IOException {
        if (isBufferingMode.get()) {
          tempMap.put(integer, Collections.<K>emptySet());
        }
        else {
          super.doRemove(integer);
        }
      }
    };

    storage.addBufferingStateListener(new MemoryIndexStorage.BufferingStateListener() {
      @Override
      public void bufferingStateChanged(boolean newState) {
        synchronized (map) {
          isBufferingMode.set(newState);
        }
      }

      @Override
      public void memoryStorageCleared() {
        synchronized (map) {
          tempMap.clear();
        }
      }
    });
    return map;
  }

  private PersistentHashMap<Integer, ByteSequence> createContentsIndex() throws IOException {
    final File saved = myHasSnapshotMapping && myIndexId != null ? new File(IndexInfrastructure.getPersistentIndexRootDir(myIndexId), "values") : null;

    if (saved != null) {
      try {
        return new PersistentHashMap<Integer, ByteSequence>(saved, EnumeratorIntegerDescriptor.INSTANCE, ByteSequenceDataExternalizer.INSTANCE);
      } catch (IOException ex) {
        IOUtil.deleteAllFilesStartingWith(saved);
        throw ex;
      }
    } else {
      return null;
    }
  }

  @NotNull
  public IndexStorage<Key, Value> getStorage() {
    return myStorage;
  }

  @Override
  public void clear() throws StorageException {
    try {
      getWriteLock().lock();
      myStorage.clear();
      if (myInputsIndex != null) {
        cleanMapping(myInputsIndex);
        myInputsIndex = createInputsIndex();
      }
      if (myInputsSnapshotMapping != null) {
        cleanMapping(myInputsSnapshotMapping);
        myInputsSnapshotMapping = createInputSnapshotMapping();
      }
      if (myIndexingTrace != null) {
        cleanMapping(myIndexingTrace);
        myIndexingTrace = createIndexingTrace();
      }
      if (myContents != null) {
        cleanMapping(myContents);
        myContents = createContentsIndex();
      }
    }
    catch (StorageException e) {
      LOG.error(e);
    }
    catch (IOException e) {
      LOG.error(e);
    }
    finally {
      getWriteLock().unlock();
    }
  }

  private PersistentHashMap<Integer, Integer> createInputSnapshotMapping() throws IOException {
    assert myIndexId != null;
    final File fileIdToHashIdFile = new File(IndexInfrastructure.getIndexRootDir(myIndexId), "fileIdToHashId");
    try {
      return new PersistentHashMap<Integer, Integer>(fileIdToHashIdFile, EnumeratorIntegerDescriptor.INSTANCE,
                                                     EnumeratorIntegerDescriptor.INSTANCE, 4096) {
        @Override
        protected boolean wantNonnegativeIntegralValues() {
          return true;
        }
      };
    }
    catch (IOException ex) {
      IOUtil.deleteAllFilesStartingWith(fileIdToHashIdFile);
      throw ex;
    }
  }

  private PersistentHashMap<Integer, String> createIndexingTrace() throws IOException {
    assert myIndexId != null;
    final File mapFile = new File(IndexInfrastructure.getIndexRootDir(myIndexId), "indextrace");
    try {
      return new PersistentHashMap<Integer, String>(mapFile, EnumeratorIntegerDescriptor.INSTANCE,
                                                    new DataExternalizer<String>() {
                                                      @Override
                                                      public void save(@NotNull DataOutput out, String value) throws IOException {
                                                        out.write((byte[])CompressionUtil.compressCharSequence(value, Charset.defaultCharset()));
                                                      }

                                                      @Override
                                                      public String read(@NotNull DataInput in) throws IOException {
                                                        byte[] b = new byte[((InputStream)in).available()];
                                                        in.readFully(b);
                                                        return (String)CompressionUtil.uncompressCharSequence(b, Charset.defaultCharset());
                                                      }
                                                    }, 4096);
    }
    catch (IOException ex) {
      IOUtil.deleteAllFilesStartingWith(mapFile);
      throw ex;
    }
  }

  private static void cleanMapping(@NotNull PersistentHashMap<?, ?> index) {
    final File baseFile = index.getBaseFile();
    try {
      index.close();
    }
    catch (Throwable ignored) {
    }

    IOUtil.deleteAllFilesStartingWith(baseFile);
  }

  @Override
  public void flush() throws StorageException{
    try {
      getReadLock().lock();
      doForce(myInputsIndex);
      doForce(myInputsSnapshotMapping);
      doForce(myIndexingTrace);
      doForce(myContents);
      myStorage.flush();
    }
    catch (IOException e) {
      throw new StorageException(e);
    }
    catch (RuntimeException e) {
      final Throwable cause = e.getCause();
      if (cause instanceof StorageException || cause instanceof IOException) {
        throw new StorageException(cause);
      }
      else {
        throw e;
      }
    }
    finally {
      getReadLock().unlock();
    }
  }

  private static void doForce(PersistentHashMap<?, ?> inputsIndex) {
    if (inputsIndex != null && inputsIndex.isDirty()) {
      inputsIndex.force();
    }
  }

  @Override
  public void dispose() {
    myLowMemoryFlusher.stop();
    final Lock lock = getWriteLock();
    try {
      lock.lock();
      try {
        myStorage.close();
      }
      finally {
        doClose(myInputsIndex);
        doClose(myInputsSnapshotMapping);
        doClose(myIndexingTrace);
        doClose(myContents);
      }
    }
    catch (StorageException e) {
      LOG.error(e);
    }
    finally {
      lock.unlock();
    }
  }

  private static void doClose(PersistentHashMap<?, ?> index) {
    if (index != null) {
      try {
        index.close();
      }
      catch (IOException e) {
        LOG.error(e);
      }
    }
  }

  @NotNull
  @Override
  public final Lock getReadLock() {
    return myLock.readLock();
  }

  @NotNull
  @Override
  public final Lock getWriteLock() {
    return myLock.writeLock();
  }

  @Override
  public boolean processAllKeys(@NotNull Processor<Key> processor, @NotNull GlobalSearchScope scope, IdFilter idFilter) throws StorageException {
    final Lock lock = getReadLock();
    try {
      lock.lock();
      return myStorage.processKeys(processor, scope, idFilter);
    }
    finally {
      lock.unlock();
    }
  }

  @Override
  @NotNull
  public ValueContainer<Value> getData(@NotNull final Key key) throws StorageException {
    final Lock lock = getReadLock();
    try {
      lock.lock();
      ValueContainerImpl.ourDebugIndexInfo.set(myIndexId);
      return myStorage.read(key);
    }
    finally {
      ValueContainerImpl.ourDebugIndexInfo.set(null);
      lock.unlock();
    }
  }

  protected PersistentHashMap<Integer, Collection<Key>> createInputsIndex() throws IOException {
    return createIdToDataKeysIndex(myExtension, (MemoryIndexStorage<Key, ?>)myStorage);
  }

  private static final boolean doReadSavedPersistentData = SystemProperties.getBooleanProperty("idea.read.saved.persistent.index", true);

  @NotNull
  @Override
  public final Computable<Boolean> update(final int inputId, @Nullable Input content) {
    final boolean weProcessPhysicalContent = content == null ||
                                             (content instanceof UserDataHolder &&
                                              FileBasedIndexImpl.ourPhysicalContentKey.get((UserDataHolder)content, Boolean.FALSE));

    Map<Key, Value> data = null;
    boolean havePersistentData = false;
    Integer hashId = null;
    boolean skippedReadingPersistentDataButMayHaveIt = false;

    if (myContents != null && weProcessPhysicalContent && content != null) {
      try {
        FileContent fileContent = (FileContent)content;
        hashId = getHashOfContent(fileContent);
        if (doReadSavedPersistentData) {
          if (!myContents.isBusyReading() || DebugAssertions.EXTRA_SANITY_CHECKS) { // avoid blocking read, we can calculate index value
            ByteSequence bytes = myContents.get(hashId);
            if (bytes != null) {
              data = deserializeSavedPersistentData(bytes);
              havePersistentData = true;
              if (DebugAssertions.EXTRA_SANITY_CHECKS) {
                Map<Key, Value> contentData = myIndexer.map(content);
                boolean sameValueForSavedIndexedResultAndCurrentOne = contentData.equals(data);
                if (!sameValueForSavedIndexedResultAndCurrentOne) {
                  DebugAssertions.error(
                    "Unexpected difference in indexing of %s by index %s, file type %s, charset %s\ndiff %s\nprevious indexed info %s",
                    fileContent.getFile(),
                    myIndexId,
                    fileContent.getFileType().getName(),
                    ((FileContentImpl)fileContent).getCharset(),
                    buildDiff(data, contentData),
                    myIndexingTrace.get(hashId)
                  );
                }
              }
            }
          } else {
            skippedReadingPersistentDataButMayHaveIt = true;
          }
        } else {
          havePersistentData = myContents.containsMapping(hashId);
        }
      } catch (IOException ex) {
        // todo:
        throw new RuntimeException(ex);
      }
    }

    if (data == null) {
      data = content != null ? myIndexer.map(content) : Collections.<Key, Value>emptyMap();
      if (DebugAssertions.EXTRA_SANITY_CHECKS) {
        checkValuesHaveProperEqualsAndHashCode(data);
      }
    }

    if (hashId != null && !havePersistentData) {
      boolean saved = savePersistentData(data, hashId, skippedReadingPersistentDataButMayHaveIt);
      if (DebugAssertions.EXTRA_SANITY_CHECKS) {
        if (saved) {

          FileContent fileContent = (FileContent)content;
          try {
            myIndexingTrace.put(hashId, ((FileContentImpl)fileContent).getCharset() + "," + fileContent.getFileType().getName()+"," + fileContent.getFile().getPath() + "," +
                                        ExceptionUtil.getThrowableText(new Throwable()));
          } catch (IOException ex) {
            LOG.error(ex);
          }
        }
      }
    }
    ProgressManager.checkCanceled();

    UpdateData<Key, Value> optimizedUpdateData = null;
    final NotNullComputable<Collection<Key>> oldKeysGetter;
    final int savedInputId;
    if (myHasSnapshotMapping) {
      try {
        final NotNullComputable<Collection<Key>> keysForGivenInputId = new NotNullComputable<Collection<Key>>() {
          @NotNull
          @Override
          public Collection<Key> compute() {
            try {
              Integer currentHashId = myInputsSnapshotMapping.get(inputId);
              Collection<Key> currentKeys;
              if (currentHashId != null) {
                ByteSequence byteSequence = myContents.get(currentHashId);
                currentKeys = byteSequence != null ? deserializeSavedPersistentData(byteSequence).keySet() : Collections.<Key>emptyList();
              }
              else {
                currentKeys = Collections.emptyList();
              }

              return currentKeys;
            }
            catch (IOException e) {
              throw new RuntimeException(e);
            }
          }
        };

        if (weProcessPhysicalContent) {
          if (content instanceof FileContent) {
            savedInputId = getHashOfContent((FileContent)content);
          }
          else {
            savedInputId = NULL_MAPPING;
          }
          oldKeysGetter = keysForGivenInputId;

          if (MapDiffUpdateData.ourDiffUpdateEnabled) {
            final Map<Key, Value> newValue = data;
            optimizedUpdateData = new MapDiffUpdateData<Key, Value>(myIndexId) {
              @Override
              protected Map<Key, Value> getNewValue() {
                return newValue;
              }

              @Override
              protected Map<Key, Value> getCurrentValue() throws IOException {
                Integer currentHashId = myInputsSnapshotMapping.get(inputId);
                Map<Key, Value> currentValue;
                if (currentHashId != null) {
                  ByteSequence byteSequence = myContents.get(currentHashId);
                  currentValue = byteSequence != null ? deserializeSavedPersistentData(byteSequence) : Collections.<Key, Value>emptyMap();
                }
                else {
                  currentValue = Collections.emptyMap();
                }
                return currentValue;
              }

              @Override
              public void save(int inputId) throws IOException {
                myInputsSnapshotMapping.put(inputId, savedInputId);
              }
            };
          }
        } else {
          oldKeysGetter = new NotNullComputable<Collection<Key>>() {
            @NotNull
            @Override
            public Collection<Key> compute() {
              try {
                Collection<Key> oldKeys = readInputKeys(inputId);
                if (oldKeys == null) {
                  return keysForGivenInputId.compute();
                }
                return oldKeys;
              }
              catch (IOException e) {
                throw new RuntimeException(e);
              }
            }
          };
          savedInputId = NULL_MAPPING;
        }
      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }
    } else {
      oldKeysGetter = new NotNullComputable<Collection<Key>>() {
        @NotNull
        @Override
        public Collection<Key> compute() {
          try {
            Collection<Key> oldKeys = readInputKeys(inputId);
            return oldKeys == null? Collections.<Key>emptyList() : oldKeys;
          }
          catch (IOException e) {
            throw new RuntimeException(e);
          }
        }
      };
      savedInputId = inputId;
    }

    // do not depend on content!
    final UpdateData<Key, Value> updateData = optimizedUpdateData != null ? optimizedUpdateData : new SimpleUpdateData(myIndexId, savedInputId, data, oldKeysGetter);
    return new Computable<Boolean>() {
      @Override
      public Boolean compute() {
        final Ref<StorageException> exRef = new Ref<StorageException>(null);
        ProgressManager.getInstance().executeNonCancelableSection(new Runnable() {
          @Override
          public void run() {
            try {
              updateWithMap(inputId, updateData);
            }
            catch (StorageException ex) {
              exRef.set(ex);
            }
          }
        });

        //noinspection ThrowableResultOfMethodCallIgnored
        StorageException nestedException = exRef.get();
        if (nestedException != null) {
          LOG.info("Exception during updateWithMap:" + nestedException);
          FileBasedIndex.getInstance().requestRebuild(myIndexId, nestedException);
          return Boolean.FALSE;
        }
        return Boolean.TRUE;
      }
    };
  }

  private Collection<Key> readInputKeys(int inputId) throws IOException {
    boolean inMemory = myStorage instanceof MemoryIndexStorage && ((MemoryIndexStorage)myStorage).isBufferingEnabled();

    if (DebugAssertions.DEBUG) {
      DebugAssertions.assertTrue(inMemory == myIsBufferingMode.get());
    }
    if (inMemory) {
      synchronized (myInMemoryKeys) {
        Collection<Key> keys = myInMemoryKeys.get(inputId);
        if (keys != null) {
          if (myInputsIndex != null && DebugAssertions.DEBUG) {
            DebugAssertions.assertTrue(equals(keys, myInputsIndex.get(inputId)));
          }
          return keys;
        }
      }
    }
    if (myHasSnapshotMapping) {
      if (myInputsIndex != null && DebugAssertions.DEBUG) {
        DebugAssertions.assertTrue(equals(null, myInputsIndex.get(inputId)));
      }
      return null;
    }
    return myInputsIndex.get(inputId);
  }

  private static <Key> boolean equals(Collection<Key> keys, Collection<Key> keys2) {
    if (keys == null && keys2 == null) return true;
    if (keys == null || keys2 == null || keys.size() != keys2.size()) return false;
    return new HashSet<Key>(keys).equals(new HashSet<>(keys2));
  }

  private void checkValuesHaveProperEqualsAndHashCode(Map<Key, Value> data) {
    for(Map.Entry<Key, Value> e: data.entrySet()) {
      final Value value = e.getValue();
      if (!(Comparing.equal(value, value) && (value == null || value.hashCode() == value.hashCode()))) {
        LOG.error("Index " + myIndexId.toString() + " violates equals / hashCode contract for Value parameter");
      }

      if (myValueExternalizer != null) {
        try {
          final BufferExposingByteArrayOutputStream out = new BufferExposingByteArrayOutputStream();
          DataOutputStream outputStream = new DataOutputStream(out);
          myValueExternalizer.save(outputStream, value);
          outputStream.close();
          final Value deserializedValue =
            myValueExternalizer.read(new DataInputStream(new UnsyncByteArrayInputStream(out.getInternalBuffer(), 0, out.size())));

          if (!(Comparing.equal(value, deserializedValue) && (value == null || value.hashCode() == deserializedValue.hashCode()))) {
            LOG.error("Index " + myIndexId.toString() + " violates equals / hashCode contract for Value parameter");
          }
        } catch (IOException ex) {
          LOG.error(ex);
        }
      }
    }
  }

  private StringBuilder buildDiff(Map<Key, Value> data, Map<Key, Value> contentData) {
    StringBuilder moreInfo = new StringBuilder();
    if (contentData.size() != data.size()) {
      moreInfo.append("Indexer has different number of elements, previously ").append(data.size()).append(" after ")
        .append(contentData.size()).append("\n");
    } else {
      moreInfo.append("total ").append(contentData.size()).append(" entries\n");
    }

    for(Map.Entry<Key, Value> keyValueEntry:contentData.entrySet()) {
      if (!data.containsKey(keyValueEntry.getKey())) {
        moreInfo.append("Previous data doesn't contain:").append(keyValueEntry.getKey()).append( " with value ").append(keyValueEntry.getValue()).append("\n");
      }
      else {
        Value value = data.get(keyValueEntry.getKey());
        if (!Comparing.equal(keyValueEntry.getValue(), value)) {
          moreInfo.append("Previous data has different value for key:").append(keyValueEntry.getKey()).append( ", new value ").append(keyValueEntry.getValue()).append( ", oldValue:").append(value).append("\n");
        }
      }
    }

    for(Map.Entry<Key, Value> keyValueEntry:data.entrySet()) {
      if (!contentData.containsKey(keyValueEntry.getKey())) {
        moreInfo.append("New data doesn't contain:").append(keyValueEntry.getKey()).append( " with value ").append(keyValueEntry.getValue()).append("\n");
      }
      else {
        Value value = contentData.get(keyValueEntry.getKey());
        if (!Comparing.equal(keyValueEntry.getValue(), value)) {
          moreInfo.append("New data has different value for key:").append(keyValueEntry.getKey()).append( " new value ").append(value).append( ", oldValue:").append(keyValueEntry.getValue()).append("\n");
        }
      }
    }
    return moreInfo;
  }

  private Map<Key, Value> deserializeSavedPersistentData(ByteSequence bytes) throws IOException {
    DataInputStream stream = new DataInputStream(new UnsyncByteArrayInputStream(bytes.getBytes(), bytes.getOffset(), bytes.getLength()));
    int pairs = DataInputOutputUtil.readINT(stream);
    if (pairs == 0) return Collections.emptyMap();
    Map<Key, Value> result = new THashMap<Key, Value>(pairs);
    while (stream.available() > 0) {
      Value value = myValueExternalizer.read(stream);
      Collection<Key> keys = mySnapshotIndexExternalizer.read(stream);
      for(Key k:keys) result.put(k, value);
    }
    return result;
  }

  private Integer getHashOfContent(FileContent content) throws IOException {
    if (myIsPsiBackedIndex && myHasSnapshotMapping && content instanceof FileContentImpl) {
      // psi backed index should use existing psi to build index value (FileContentImpl.getPsiFileForPsiDependentIndex())
      // so we should use different bytes to calculate hash(Id)
      Integer previouslyCalculatedUncommittedHashId = content.getUserData(ourSavedUncommittedHashIdKey);

      if (previouslyCalculatedUncommittedHashId == null) {
        Document document = FileDocumentManager.getInstance().getCachedDocument(content.getFile());

        if (document != null) {  // if document is not committed
          PsiDocumentManager psiDocumentManager = PsiDocumentManager.getInstance(content.getProject());

          if (psiDocumentManager.isUncommited(document)) {
            PsiFile file = psiDocumentManager.getCachedPsiFile(document);
            Charset charset = ((FileContentImpl)content).getCharset();

            if (file != null) {
              previouslyCalculatedUncommittedHashId = ContentHashesSupport
                .calcContentHashIdWithFileType(file.getText().getBytes(charset), charset,
                                               content.getFileType());
              content.putUserData(ourSavedUncommittedHashIdKey, previouslyCalculatedUncommittedHashId);
            }
          }
        }
      }
      if (previouslyCalculatedUncommittedHashId != null) return previouslyCalculatedUncommittedHashId;
    }

    Integer previouslyCalculatedContentHashId = content.getUserData(ourSavedContentHashIdKey);
    if (previouslyCalculatedContentHashId == null) {
      byte[] hash = content instanceof FileContentImpl ? ((FileContentImpl)content).getHash():null;
      if (hash == null) {
        Charset charset = content instanceof FileContentImpl ? ((FileContentImpl)content).getCharset() : null;
        previouslyCalculatedContentHashId = ContentHashesSupport
          .calcContentHashIdWithFileType(content.getContent(), charset, content.getFileType());
      } else {
        previouslyCalculatedContentHashId =  ContentHashesSupport.enumerateHash(hash);
      }
      content.putUserData(ourSavedContentHashIdKey, previouslyCalculatedContentHashId);
    }
    return previouslyCalculatedContentHashId;
  }

  private static final ThreadLocalCachedByteArray ourSpareByteArray = new ThreadLocalCachedByteArray();

  private boolean savePersistentData(Map<Key, Value> data, int id, boolean delayedReading) {
    try {
      if (delayedReading && myContents.containsMapping(id)) return false;
      BufferExposingByteArrayOutputStream out = new BufferExposingByteArrayOutputStream(ourSpareByteArray.getBuffer(4 * data.size()));
      DataOutputStream stream = new DataOutputStream(out);
      int size = data.size();
      DataInputOutputUtil.writeINT(stream, size);

      if (size > 0) {
        THashMap<Value, List<Key>> values = new THashMap<Value, List<Key>>();
        List<Key> keysForNullValue = null;
        for (Map.Entry<Key, Value> e : data.entrySet()) {
          Value value = e.getValue();

          List<Key> keys = value != null ? values.get(value):keysForNullValue;
          if (keys == null) {
            if (value != null) values.put(value, keys = new SmartList<Key>());
            else keys = keysForNullValue = new SmartList<Key>();
          }
          keys.add(e.getKey());
        }

        if (keysForNullValue != null) {
          myValueExternalizer.save(stream, null);
          mySnapshotIndexExternalizer.save(stream, keysForNullValue);
        }

        for(Value value:values.keySet()) {
          myValueExternalizer.save(stream, value);
          mySnapshotIndexExternalizer.save(stream, values.get(value));
        }
      }

      myContents.put(id, new ByteSequence(out.getInternalBuffer(), 0, out.size()));
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
    return true;
  }

  private static final com.intellij.openapi.util.Key<Integer> ourSavedContentHashIdKey = com.intellij.openapi.util.Key.create("saved.content.hash.id");
  private static final com.intellij.openapi.util.Key<Integer> ourSavedUncommittedHashIdKey = com.intellij.openapi.util.Key.create("saved.uncommitted.hash.id");

  public class SimpleUpdateData extends UpdateData<Key, Value> {
    private final int savedInputId;
    private final @NotNull Map<Key, Value> newData;
    private final @NotNull NotNullComputable<Collection<Key>> oldKeysGetter;

    public SimpleUpdateData(ID<Key,Value> indexId, int id, @NotNull Map<Key, Value> data, @NotNull NotNullComputable<Collection<Key>> getter) {
      super(indexId);
      savedInputId = id;
      newData = data;
      oldKeysGetter = getter;
    }

    public void iterateRemovedOrUpdatedKeys(int inputId, RemovedOrUpdatedKeyProcessor<Key> consumer) throws StorageException {
      MapDiffUpdateData.iterateRemovedKeys(oldKeysGetter.compute(), inputId, consumer);
    }

    public void iterateAddedKeys(final int inputId, final AddedKeyProcessor<Key, Value> consumer) throws StorageException {
      MapDiffUpdateData.iterateAddedKeyAndValues(inputId, consumer, newData);
    }

    @Override
    public void save(int inputId) throws IOException {
      boolean physical = !(myStorage instanceof MemoryIndexStorage) || !((MemoryIndexStorage)myStorage).isBufferingEnabled();
      Set<Key> newKeys = newData.keySet();

      if (DebugAssertions.DEBUG) {
        DebugAssertions.assertTrue(!physical == myIsBufferingMode.get());
      }
      if (myIsBufferingMode.get()) {
        synchronized (myInMemoryKeys) {
          myInMemoryKeys.put(inputId, newKeys);
        }
      }

      if (myHasSnapshotMapping && physical) {
        myInputsSnapshotMapping.put(inputId, savedInputId);
      } else if (myInputsIndex != null) {
        if (newKeys.size() > 0) {
          myInputsIndex.put(inputId, newKeys);
        }
        else {
          myInputsIndex.remove(inputId);
        }
      }
    }

    public @NotNull Map<Key, Value> getNewData() {
      return newData;
    }
  }

  private final MapDiffUpdateData.RemovedOrUpdatedKeyProcessor<Key>
    myRemoveStaleKeyOperation = new MapDiffUpdateData.RemovedOrUpdatedKeyProcessor<Key>() {
    @Override
    public void process(Key key, int inputId) throws StorageException {
      myStorage.removeAllValues(key, inputId);
    }
  };

  private final MapDiffUpdateData.AddedKeyProcessor<Key, Value> myAddedKeyProcessor = new MapDiffUpdateData.AddedKeyProcessor<Key, Value>() {
    @Override
    public void process(Key key, Value value, int inputId) throws StorageException {
      myStorage.addValue(key, inputId, value);
    }
  };

  protected void updateWithMap(final int inputId,
                               @NotNull UpdateData<Key, Value> updateData) throws StorageException {
    getWriteLock().lock();
    try {
      try {
        ValueContainerImpl.ourDebugIndexInfo.set(myIndexId);
        updateData.iterateRemovedOrUpdatedKeys(inputId, myRemoveStaleKeyOperation);
        updateData.iterateAddedKeys(inputId, myAddedKeyProcessor);
        updateData.save(inputId);
      }
      catch (ProcessCanceledException pce) {
        throw pce; // extra care
      }
      catch (Throwable e) { // e.g. IOException, AssertionError
        throw new StorageException(e);
      }
      finally {
        ValueContainerImpl.ourDebugIndexInfo.set(null);
      }
    }
    finally {
      getWriteLock().unlock();
    }
  }
}
