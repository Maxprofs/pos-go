package com.shopify.volumizer.model;

import org.javatuples.Pair;

import java.util.HashMap;
import java.util.Map;

import io.reactivex.Maybe;
import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Consumer;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.BehaviorSubject;
import io.reactivex.subjects.PublishSubject;

import timber.log.Timber;

import static com.shopify.volumizer.model.StateMachine.Transition.Type.ENTERING;
import static com.shopify.volumizer.model.StateMachine.Transition.Type.EXITING;
import static com.shopify.volumizer.model.StateMachine.Transition.Type.STARTING;

class StateMachine<E> implements Consumer<E> {

    private final PublishSubject<E> events = PublishSubject.create();

    // NOTE: This works for now, but there's probably a way to avoid the use of BehaviorSubject.
    private final BehaviorSubject<State<E>> current = BehaviorSubject.create();

    private final Observable<Pair<State<E>, Transition<E>>> processor;


    public StateMachine(final Supplier<State<E>> initialSupplier) {
        this(initialSupplier.get());
    }

    public StateMachine(final State<E> initial) {
        Observable<Pair<State<E>, Transition<E>>> o = Observable.create(emitter -> {
            Timber.i("[%s]", initial);
            emitter.onNext(Pair.with(initial, new Transition<>(STARTING, null))); // NOTE: You can't listen to 'STARTING' transitions, by design.

            Disposable disposable = events
                    .observeOn(Schedulers.single())
                    .doOnNext(e -> Timber.i("\n(%s) *EVENT*", e.toString()))
                    .scan(initial, (oldState, event) -> {
                        final State<E> next = oldState.next(event);
                        if (next != null) {
                            Timber.i("(%1$s) [%2$s] ->", event, oldState);
                            emitter.onNext(Pair.with(oldState, new Transition<>(EXITING, event)));
                            Timber.i("(%1$s) [%2$s] <-", event, next);
                            emitter.onNext(Pair.with(next, new Transition<>(ENTERING, event)));
                            return next;
                        } else {
                            // This silently ignores, emits nothing, but Timber.e() should trigger a Crashlytics report.
                            Timber.e("(%s) [%s] -> \n[>NULL<] // *INVALID EVENT FOR STATE*", oldState, event);
                            return oldState;
                        }
                    })
                    .subscribe();

            // TODO: If we add a 'final' concept in this StateMachine, we'll want to have `emitter.onComplete()` on 'final' state.

            emitter.setDisposable(disposable);
        });
        // Had trouble with the .share typecasting when added to end of above chain.
        this.processor = o.subscribeOn(Schedulers.single()).share();

        // .share() requires a subscription to bootstrap it, and we also will
        // need a BehaviorSubject for 'current state'.
        processor
                .filter(p -> p.getValue1().type != EXITING) // We need to filter out other types of transitions.
                .map(Pair::getValue0)
                .subscribe(current);
    }

    /**
     * This observables will return the latest available state, and the following state changes.
     *
     * @return Observable of states
     */
    public Observable<State<E>> current() {
        return current;
    }

    /**
     * Build a Maybe that emits the next matching transition then completes,
     * or completes silently if the next transition does not match.
     */
    public Maybe<E> enterTrigger(Supplier<State<E>> supplier, E triggerEvent) {
        return trigger(supplier, ENTERING).filter(triggerEvent::equals);
    }

    /**
     * see {@link #enterTrigger(Supplier, Object)} for details.
     */
    public Maybe<E> exitTrigger(Supplier<State<E>> supplier, E triggerEvent) {
        return trigger(supplier, EXITING).filter(triggerEvent::equals);
    }

    public Maybe<E> enterTrigger(Supplier<State<E>> supplier) {
        return trigger(supplier, ENTERING);
    }

    public Maybe<E> exitTrigger(Supplier<State<E>> supplier) {
        return trigger(supplier, EXITING);
    }

    public Maybe<E> trigger(Supplier<State<E>> supplier, Transition.Type type) {
        State<E> state = supplier.get();
        return processor
                .filter(t -> type.equals(t.getValue1().type)) // Decide what 'end' of the transition we want.
                .firstElement() // Only take the first of these
                .filter(t -> t.getValue0().equals(state)) // Only emit it if it's for the desired state
                .map(t -> t.getValue1().event); // Map it to event, since we can infer state+transition
    }

    public Observable<E> entering(Supplier<State<E>> supplier) {
        return transition(supplier.get(), ENTERING);
    }

    public Observable<E> exiting(Supplier<State<E>> supplier) {
        return transition(supplier.get(), EXITING);
    }

    private Observable<E> transition(State<E> state, Transition.Type type) {
        return processor
                .filter(t -> t.getValue0().equals(state) && t.getValue1().type.equals(type))
                .map(t -> t.getValue1().event);
    }

    /**
     * Respecting the Consumer method signature makes it easy to plug in the event listener as a subscriber.
     */
    @Override
    public void accept(E event) {
        events.onNext(event);
    }

    static class Transition<E> {

        final Type type;
        final E event;

        Transition(Type type, E event) {
            this.type = type;
            this.event = event;
        }

        enum Type {
            ENTERING, EXITING, STARTING;
        }
    }

    public static class State<E> {

        private final String name;
        private Map<E, State<E>> transitions = new HashMap<>();

        public State(String name) {
            this.name = name;
        }

        public State<E> transition(E event, State<E> state) {
            transitions.put(event, state);
            return this;
        }

        public State<E> transition(E event, Supplier<State<E>> stateSupplier) {
            transitions.put(event, stateSupplier.get());
            return this;
        }

        State<E> next(E event) {
            return transitions.get(event);
        }

        public String toString() {
            return name;
        }
    }
}