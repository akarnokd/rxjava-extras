package com.github.davidmoten.rx.internal.operators;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import rx.Observable.OnSubscribe;
import rx.Observable.Operator;
import rx.Producer;
import rx.Subscriber;
import rx.Subscription;
import rx.exceptions.Exceptions;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.internal.operators.BackpressureUtils;
import rx.internal.operators.NotificationLite;
import rx.internal.producers.ProducerArbiter;
import rx.internal.util.RxRingBuffer;
import rx.internal.util.UtilityFunctions;
import rx.observables.GroupedObservable;
import rx.observers.Subscribers;
import rx.plugins.RxJavaPlugins;
import rx.subscriptions.Subscriptions;

/**
 * Groups the items emitted by an Observable according to a specified criterion,
 * and emits these grouped items as Observables, one Observable per group.
 * <p>
 * <img width="640" height="360" src=
 * "https://raw.githubusercontent.com/wiki/ReactiveX/RxJava/images/rx-operators/groupBy.png"
 * alt="">
 *
 * @param <K>
 *            the key type
 * @param <T>
 *            the source and group value type
 * @param <R>
 *            the value type of the groups
 */
public final class OperatorGroupBy<T, K, V> implements Operator<GroupedObservable<K, V>, T> {
    final Func1<? super T, ? extends K> keySelector;
    final Func1<? super T, ? extends V> valueSelector;
    final int bufferSize;
    final boolean delayError;
    final Func1<Action1<K>, Map<K, Object>> mapFactory; // nullable

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public OperatorGroupBy(Func1<? super T, ? extends K> keySelector) {
        this(keySelector, (Func1) UtilityFunctions.<T> identity(), RxRingBuffer.SIZE, false, null);
    }

    public OperatorGroupBy(Func1<? super T, ? extends K> keySelector,
            Func1<? super T, ? extends V> valueSelector) {
        this(keySelector, valueSelector, RxRingBuffer.SIZE, false, null);
    }

    public OperatorGroupBy(Func1<? super T, ? extends K> keySelector,
            Func1<? super T, ? extends V> valueSelector,
            Func1<Action1<K>, Map<K, Object>> mapFactory) {
        this(keySelector, valueSelector, RxRingBuffer.SIZE, false, mapFactory);
    }

    public OperatorGroupBy(Func1<? super T, ? extends K> keySelector,
            Func1<? super T, ? extends V> valueSelector, int bufferSize, boolean delayError,
            Func1<Action1<K>, Map<K, Object>> mapFactory) {
        this.keySelector = keySelector;
        this.valueSelector = valueSelector;
        this.bufferSize = bufferSize;
        this.delayError = delayError;
        this.mapFactory = mapFactory;
    }

    @Override
    public Subscriber<? super T> call(Subscriber<? super GroupedObservable<K, V>> child) {
        final GroupBySubscriber<T, K, V> parent;
        try {
            parent = new GroupBySubscriber<T, K, V>(child, keySelector, valueSelector, bufferSize,
                    delayError, mapFactory);
        } catch (Throwable ex) {
            // Can reach here because mapFactory.call() may throw in constructor
            // of GroupBySubscriber
            Exceptions.throwOrReport(ex, child);
            Subscriber<? super T> parent2 = Subscribers.empty();
            parent2.unsubscribe();
            return parent2;
        }

        child.add(Subscriptions.create(new Action0() {
            @Override
            public void call() {
                parent.cancel();
            }
        }));

        child.setProducer(parent.producer);

        return parent;
    }

    public static final class GroupByProducer implements Producer {
        final GroupBySubscriber<?, ?, ?> parent;

        public GroupByProducer(GroupBySubscriber<?, ?, ?> parent) {
            this.parent = parent;
        }

        @Override
        public void request(long n) {
            parent.requestMore(n);
        }
    }

    public static final class GroupBySubscriber<T, K, V> extends Subscriber<T> {
        final Subscriber<? super GroupedObservable<K, V>> actual;
        final Func1<? super T, ? extends K> keySelector;
        final Func1<? super T, ? extends V> valueSelector;
        final int bufferSize;
        final boolean delayError;
        final Map<Object, GroupedUnicast<K, V>> groups;
        final Queue<GroupedObservable<K, V>> queue;
        final GroupByProducer producer;
        final Queue<K> evictedKeys;

        static final Object NULL_KEY = new Object();

        final ProducerArbiter s;

        volatile int cancelled;
        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<GroupBySubscriber> CANCELLED = AtomicIntegerFieldUpdater
                .newUpdater(GroupBySubscriber.class, "cancelled");

        volatile long requested;
        @SuppressWarnings("rawtypes")
        static final AtomicLongFieldUpdater<GroupBySubscriber> REQUESTED = AtomicLongFieldUpdater
                .newUpdater(GroupBySubscriber.class, "requested");

        volatile int groupCount;
        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<GroupBySubscriber> GROUP_COUNT = AtomicIntegerFieldUpdater
                .newUpdater(GroupBySubscriber.class, "groupCount");

        Throwable error;
        volatile boolean done;

        volatile int wip;
        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<GroupBySubscriber> WIP = AtomicIntegerFieldUpdater
                .newUpdater(GroupBySubscriber.class, "wip");

        public GroupBySubscriber(Subscriber<? super GroupedObservable<K, V>> actual,
                Func1<? super T, ? extends K> keySelector,
                Func1<? super T, ? extends V> valueSelector, int bufferSize, boolean delayError,
                Func1<Action1<K>, Map<K, Object>> mapFactory) {
            this.actual = actual;
            this.keySelector = keySelector;
            this.valueSelector = valueSelector;
            this.bufferSize = bufferSize;
            this.delayError = delayError;
            this.queue = new ConcurrentLinkedQueue<GroupedObservable<K, V>>();
            GROUP_COUNT.lazySet(this, 1);
            this.s = new ProducerArbiter();
            this.s.request(bufferSize);
            this.producer = new GroupByProducer(this);
            if (mapFactory == null) {
                this.groups = new ConcurrentHashMap<Object, GroupedUnicast<K, V>>();
                this.evictedKeys = null;
            } else {
                this.evictedKeys = new ConcurrentLinkedQueue<K>();
                this.groups = createMap(mapFactory, new EvictionAction<K>(evictedKeys));
            }
        }

        // declare a class instead of using anonymous class to
        // limit enclosing scope. Is this of value?
        static class EvictionAction<K> implements Action1<K> {

            final Queue<K> evictedKeys;

            EvictionAction(Queue<K> evictedKeys) {
                this.evictedKeys = evictedKeys;
            }

            @Override
            public void call(K key) {
                evictedKeys.offer(key);
            }
        }

        @SuppressWarnings("unchecked")
        private Map<Object, GroupedUnicast<K, V>> createMap(
                Func1<Action1<K>, Map<K, Object>> mapFactory, Action1<K> evictionAction) {
            return (Map<Object, GroupedUnicast<K, V>>) (Map<Object, ?>) mapFactory
                    .call(evictionAction);
        }

        @Override
        public void setProducer(Producer s) {
            this.s.setProducer(s);
        }

        @Override
        public void onNext(T t) {
            if (done) {
                return;
            }

            final Queue<GroupedObservable<K, V>> q = this.queue;
            final Subscriber<? super GroupedObservable<K, V>> a = this.actual;

            K key;
            try {
                key = keySelector.call(t);
            } catch (Throwable ex) {
                unsubscribe();
                errorAll(a, q, ex);
                return;
            }

            boolean notNew = true;
            Object mapKey = key != null ? key : NULL_KEY;
            GroupedUnicast<K, V> group = groups.get(mapKey);
            if (group == null) {
                // if the main has been cancelled, stop creating groups
                // and skip this value
                if (cancelled == 0) {
                    group = GroupedUnicast.createWith(key, bufferSize, this, delayError);
                    groups.put(mapKey, group);

                    GROUP_COUNT.getAndIncrement(this);

                    notNew = false;
                    q.offer(group);
                    drain();
                } else {
                    return;
                }
            }

            V v;
            try {
                v = valueSelector.call(t);
            } catch (Throwable ex) {
                unsubscribe();
                errorAll(a, q, ex);
                return;
            }

            group.onNext(v);

            if (evictedKeys != null) {
                K evictedKey;
                while ((evictedKey = evictedKeys.poll()) != null) {
                    GroupedUnicast<K, V> g = groups.get(evictedKey);
                    if (g != null) {
                        g.onComplete();
                    }
                }
            }

            if (notNew) {
                s.request(1);
            }
        }

        @Override
        public void onError(Throwable t) {
            if (done) {
                RxJavaPlugins.getInstance().getErrorHandler().handleError(t);
                return;
            }
            error = t;
            done = true;
            GROUP_COUNT.decrementAndGet(this);
            drain();
        }

        @Override
        public void onCompleted() {
            if (done) {
                return;
            }

            for (GroupedUnicast<K, V> e : groups.values()) {
                e.onComplete();
            }
            groups.clear();
            if (evictedKeys != null) {
                evictedKeys.clear();
            }

            done = true;
            GROUP_COUNT.decrementAndGet(this);
            drain();
        }

        public void requestMore(long n) {
            if (n < 0) {
                throw new IllegalArgumentException("n >= 0 required but it was " + n);
            }

            BackpressureUtils.getAndAddRequest(REQUESTED, this, n);
            drain();
        }

        public void cancel() {
            // cancelling the main source means we don't want any more groups
            // but running groups still require new values
            if (CANCELLED.compareAndSet(this, 0, 1)) {
                if (GROUP_COUNT.decrementAndGet(this) == 0) {
                    unsubscribe();
                }
            }
        }

        public void cancel(K key) {
            Object mapKey = key != null ? key : NULL_KEY;
            if (groups.remove(mapKey) != null) {
                if (GROUP_COUNT.decrementAndGet(this) == 0) {
                    unsubscribe();
                }
            }
        }

        void drain() {
            if (WIP.getAndIncrement(this) != 0) {
                return;
            }

            int missed = 1;

            final Queue<GroupedObservable<K, V>> q = this.queue;
            final Subscriber<? super GroupedObservable<K, V>> a = this.actual;

            for (;;) {

                if (checkTerminated(done, q.isEmpty(), a, q)) {
                    return;
                }

                long r = requested;
                boolean unbounded = r == Long.MAX_VALUE;
                long e = 0L;

                while (r != 0) {
                    boolean d = done;

                    GroupedObservable<K, V> t = q.poll();

                    boolean empty = t == null;

                    if (checkTerminated(d, empty, a, q)) {
                        return;
                    }

                    if (empty) {
                        break;
                    }

                    a.onNext(t);

                    r--;
                    e--;
                }

                if (e != 0L) {
                    if (!unbounded) {
                        REQUESTED.addAndGet(this, e);
                    }
                    s.request(-e);
                }

                missed = WIP.addAndGet(this, -missed);
                if (missed == 0) {
                    break;
                }
            }
        }

        void errorAll(Subscriber<? super GroupedObservable<K, V>> a, Queue<?> q, Throwable ex) {
            q.clear();
            List<GroupedUnicast<K, V>> list = new ArrayList<GroupedUnicast<K, V>>(groups.values());
            groups.clear();
            if (evictedKeys != null) {
                evictedKeys.clear();
            }

            for (GroupedUnicast<K, V> e : list) {
                e.onError(ex);
            }

            a.onError(ex);
        }

        boolean checkTerminated(boolean d, boolean empty,
                Subscriber<? super GroupedObservable<K, V>> a, Queue<?> q) {
            if (d) {
                Throwable err = error;
                if (err != null) {
                    errorAll(a, q, err);
                    return true;
                } else if (empty) {
                    actual.onCompleted();
                    return true;
                }
            }
            return false;
        }
    }

    static final class GroupedUnicast<K, T> extends GroupedObservable<K, T> {

        public static <T, K> GroupedUnicast<K, T> createWith(K key, int bufferSize,
                GroupBySubscriber<?, K, T> parent, boolean delayError) {
            State<T, K> state = new State<T, K>(bufferSize, parent, key, delayError);
            return new GroupedUnicast<K, T>(key, state);
        }

        final State<T, K> state;

        protected GroupedUnicast(K key, State<T, K> state) {
            super(key, state);
            this.state = state;
        }

        public void onNext(T t) {
            state.onNext(t);
        }

        public void onError(Throwable e) {
            state.onError(e);
        }

        public void onComplete() {
            state.onComplete();
        }
    }

    static final class State<T, K> extends AtomicInteger
            implements Producer, Subscription, OnSubscribe<T> {
        /** */
        private static final long serialVersionUID = -3852313036005250360L;

        final K key;
        final Queue<Object> queue;
        final GroupBySubscriber<?, K, T> parent;
        final boolean delayError;

        volatile long requested;
        @SuppressWarnings("rawtypes")
        static final AtomicLongFieldUpdater<State> REQUESTED = AtomicLongFieldUpdater
                .newUpdater(State.class, "requested");

        volatile boolean done;
        Throwable error;

        volatile int cancelled;
        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<State> CANCELLED = AtomicIntegerFieldUpdater
                .newUpdater(State.class, "cancelled");

        volatile Subscriber<? super T> actual;
        @SuppressWarnings("rawtypes")
        static final AtomicReferenceFieldUpdater<State, Subscriber> ACTUAL = AtomicReferenceFieldUpdater
                .newUpdater(State.class, Subscriber.class, "actual");

        volatile int once;
        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<State> ONCE = AtomicIntegerFieldUpdater
                .newUpdater(State.class, "once");

        public State(int bufferSize, GroupBySubscriber<?, K, T> parent, K key, boolean delayError) {
            this.queue = new ConcurrentLinkedQueue<Object>();
            this.parent = parent;
            this.key = key;
            this.delayError = delayError;
        }

        @Override
        public void request(long n) {
            if (n < 0) {
                throw new IllegalArgumentException("n >= required but it was " + n);
            }
            if (n != 0L) {
                BackpressureUtils.getAndAddRequest(REQUESTED, this, n);
                drain();
            }
        }

        @Override
        public boolean isUnsubscribed() {
            return cancelled != 0;
        }

        @Override
        public void unsubscribe() {
            if (CANCELLED.compareAndSet(this, 0, 1)) {
                if (getAndIncrement() == 0) {
                    parent.cancel(key);
                }
            }
        }

        @Override
        public void call(Subscriber<? super T> s) {
            if (ONCE.compareAndSet(this, 0, 1)) {
                s.add(this);
                s.setProducer(this);
                ACTUAL.lazySet(this, s);
                drain();
            } else {
                s.onError(new IllegalStateException("Only one Subscriber allowed!"));
            }
        }

        public void onNext(T t) {
            if (t == null) {
                error = new NullPointerException();
                done = true;
            } else {
                queue.offer(NotificationLite.instance().next(t));
            }
            drain();
        }

        public void onError(Throwable e) {
            error = e;
            done = true;
            drain();
        }

        public void onComplete() {
            done = true;
            drain();
        }

        void drain() {
            if (getAndIncrement() != 0) {
                return;
            }
            int missed = 1;

            final Queue<Object> q = queue;
            final boolean delayError = this.delayError;
            Subscriber<? super T> a = actual;
            NotificationLite<T> nl = NotificationLite.instance();
            for (;;) {
                if (a != null) {
                    if (checkTerminated(done, q.isEmpty(), a, delayError)) {
                        return;
                    }

                    long r = requested;
                    boolean unbounded = r == Long.MAX_VALUE;
                    long e = 0;

                    while (r != 0L) {
                        boolean d = done;
                        Object v = q.poll();
                        boolean empty = v == null;

                        if (checkTerminated(d, empty, a, delayError)) {
                            return;
                        }

                        if (empty) {
                            break;
                        }

                        a.onNext(nl.getValue(v));

                        r--;
                        e--;
                    }

                    if (e != 0L) {
                        if (!unbounded) {
                            REQUESTED.addAndGet(this, e);
                        }
                        parent.s.request(-e);
                    }
                }

                missed = addAndGet(-missed);
                if (missed == 0) {
                    break;
                }
                if (a == null) {
                    a = actual;
                }
            }
        }

        boolean checkTerminated(boolean d, boolean empty, Subscriber<? super T> a,
                boolean delayError) {
            if (cancelled != 0) {
                queue.clear();
                parent.cancel(key);
                return true;
            }

            if (d) {
                if (delayError) {
                    if (empty) {
                        Throwable e = error;
                        if (e != null) {
                            a.onError(e);
                        } else {
                            a.onCompleted();
                        }
                        return true;
                    }
                } else {
                    Throwable e = error;
                    if (e != null) {
                        queue.clear();
                        a.onError(e);
                        return true;
                    } else if (empty) {
                        a.onCompleted();
                        return true;
                    }
                }
            }

            return false;
        }
    }
}
