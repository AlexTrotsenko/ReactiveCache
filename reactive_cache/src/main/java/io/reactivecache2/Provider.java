/*
 * Copyright 2016 Victor Albertos
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.reactivecache2;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.SingleTransformer;
import io.rx_cache2.ConfigProvider;
import io.rx_cache2.EvictDynamicKey;
import io.rx_cache2.Reply;
import io.rx_cache2.internal.ProcessorProviders;
import java.util.concurrent.TimeUnit;

/**
 * Entry point to manage cache CRUD operations.
 *
 * @param <T> The type of the data to persist.
 */
public class Provider<T> {
  private final ProviderBuilder<T> builder;
  protected final ExceptionAdapter exceptionAdapter;

  Provider(ProviderBuilder<T> builder) {
    this.builder = builder;
    this.exceptionAdapter = new ExceptionAdapter();
  }

  /**
   * Evict all the cached data for this provider.
   */
  public final Completable evict() {
    return Completable.defer(() ->
        Completable.fromObservable(builder.processorProviders
            .process(getConfigProvider(Observable.error(new RuntimeException()),
                new EvictDynamicKey(true), false, false)))
            .onErrorResumeNext(exceptionAdapter::completeOnRxCacheLoaderError)
    );
  }

  /**
   * Replace the cached data by the element emitted from the loader.
   */
  public final SingleTransformer<T, T> replace() {
    return loader ->
        loader.flatMap(data -> Single.fromObservable(builder.processorProviders
            .process(getConfigProvider(Observable.just(data),
                new EvictDynamicKey(true), false, null))));
  }

  /**
   * Read from cache and throw if no data is available.
   */
  public final Single<T> read() {
    return Single.defer(() ->
        Single.fromObservable(builder.processorProviders
            .<T>process(getConfigProvider(exceptionAdapter.placeholderLoader(),
                new EvictDynamicKey(false), false, null))))
        .onErrorResumeNext(exceptionAdapter::stripPlaceholderLoaderException);
  }

  /**
   * Read from cache but if there is not data available then read from the loader and cache its
   * element.
   */
  public final SingleTransformer<T, T> readWithLoader() {
    return loader ->
        Single.fromObservable(builder.processorProviders
            .process(getConfigProvider(loader.toObservable(),
                new EvictDynamicKey(false), false, null)));
  }

  /**
   * Same as {@link Provider#replace()} but wrap the data in a Reply object for debug purposes.
   */
  public final SingleTransformer<T, Reply<T>> replaceAsReply() {
    return loader ->
        loader.flatMap(data -> Single.fromObservable(builder.processorProviders
            .process(getConfigProvider(Observable.just(data), new EvictDynamicKey(true), true,
                null))));
  }

  /**
   * Same as {@link Provider#readWithLoader()} but wrap the data in a Reply object for debug
   * purposes.
   */
  public final SingleTransformer<T, Reply<T>> readWithLoaderAsReply() {
    return loader -> Single.fromObservable(builder.processorProviders
        .process(getConfigProvider(loader.toObservable(), new EvictDynamicKey(false), true, null)));
  }

  private ConfigProvider getConfigProvider(Observable<T> loader,
      EvictDynamicKey evict, boolean detailResponse, Boolean useExpiredDataIfNotLoaderAvailable) {
    Long lifeTime = builder.timeUnit != null ?
        builder.timeUnit.toMillis(builder.duration) : null;

    return new ConfigProvider(builder.key, useExpiredDataIfNotLoaderAvailable, lifeTime,
        detailResponse,
        builder.expirable, builder.encrypted, builder.key,
        "", loader, evict);
  }

  public static class ProviderBuilder<T> {
    protected String key;
    private boolean encrypted, expirable;
    private Long duration;
    private TimeUnit timeUnit;
    private final ProcessorProviders processorProviders;

    ProviderBuilder(ProcessorProviders processorProviders) {
      this.encrypted = false;
      this.expirable = true;
      this.processorProviders = processorProviders;
    }

    /**
     * If called, this provider encrypts its data as long as ReactiveCache has been configured with
     * an encryption key.
     */
    public ProviderBuilder<T> encrypt(boolean encrypt) {
      this.encrypted = encrypt;
      return this;
    }

    /**
     * Make the data associated with this provider eligible to be expired if not enough space
     * remains on disk. By default is true.
     */
    public ProviderBuilder<T> expirable(boolean expirable) {
      this.expirable = expirable;
      return this;
    }

    /**
     * Set the amount of time before the data would be evicted. If life cache is not configured, the
     * data will be never evicted unless it is required explicitly using {@link Provider#evict()} or
     * {@link Provider#replace()}
     */
    public ProviderBuilder<T> lifeCache(long duration, TimeUnit timeUnit) {
      this.duration = duration;
      this.timeUnit = timeUnit;
      return this;
    }

    /**
     * Set the key for the provider.
     */
    public <R extends Provider<T>> R withKey(Object key) {
      this.key = key.toString();
      return (R) new Provider<>(this);
    }
  }
}
